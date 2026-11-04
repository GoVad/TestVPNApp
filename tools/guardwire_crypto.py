"""Криптография GuardWire/2 для Python-серверов.

Схема полностью совпадает с Kotlin-клиентом:

* эфемерный ECDH P-256;
* токен — PSK, доказывается через HMAC-SHA256 (по сети не передаётся);
* HKDF-SHA256 выводит AES-256 ключ;
* каждый кадр шифруется AES-256-GCM со случайным IV.

Требуется пакет `cryptography`:
    python3 -m pip install cryptography
"""

from __future__ import annotations

import hashlib
import hmac as std_hmac
import os
import socket
import struct
from dataclasses import dataclass

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ModuleNotFoundError as error:
    raise SystemExit(
        "Не найден пакет cryptography. Создайте venv:\n"
        "  ./tools/setup-python.sh\n"
        "  .venv/bin/python tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo\n"
        "или установите системный пакет: sudo apt install python3-cryptography"
    ) from error

MAGIC = b"GUARDWIRE"
VERSION = 2

NONCE_SIZE = 16
PROOF_SIZE = 32
GCM_IV_SIZE = 12
GCM_TAG_SIZE = 16
AES_KEY_SIZE = 32
HKDF_INFO = b"GuardWire/2 session key"


class CryptoError(RuntimeError):
    pass


@dataclass
class Session:
    """Сессионный AES-256-GCM ключ."""

    key: bytes
    mtu: int

    def __post_init__(self) -> None:
        if len(self.key) != AES_KEY_SIZE:
            raise CryptoError("Ожидается AES-256 ключ")

    def encrypt(self, frame_type: int, payload: bytes) -> bytes:
        iv = os.urandom(GCM_IV_SIZE)
        ciphertext = AESGCM(self.key).encrypt(iv, payload, bytes([frame_type]))
        return iv + ciphertext

    def decrypt(self, frame_type: int, encrypted_payload: bytes) -> bytes:
        if len(encrypted_payload) < GCM_IV_SIZE + GCM_TAG_SIZE:
            raise CryptoError("Слишком короткий зашифрованный кадр")
        iv = encrypted_payload[:GCM_IV_SIZE]
        ciphertext = encrypted_payload[GCM_IV_SIZE:]
        try:
            return AESGCM(self.key).decrypt(iv, ciphertext, bytes([frame_type]))
        except Exception as error:
            raise CryptoError(f"Не удалось расшифровать кадр: {error}") from error


def recv_exact(conn: socket.socket, size: int) -> bytes | None:
    data = b""
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def _token_bytes(token: str) -> bytes:
    if not token:
        raise CryptoError("PSK-токен не должен быть пустым")
    return token.encode("utf-8")


def request_transcript(mtu: int, client_nonce: bytes, client_public: bytes) -> bytes:
    return (
        MAGIC
        + bytes([VERSION])
        + struct.pack("!H", mtu)
        + client_nonce
        + struct.pack("!H", len(client_public))
        + client_public
    )


def response_transcript(
    accepted: bool,
    client_nonce: bytes,
    client_public: bytes,
    server_nonce: bytes,
    server_public: bytes,
) -> bytes:
    return (
        MAGIC
        + bytes([VERSION, 0 if accepted else 1])
        + client_nonce
        + struct.pack("!H", len(client_public))
        + client_public
        + server_nonce
        + struct.pack("!H", len(server_public))
        + server_public
    )


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    return std_hmac.new(key, data, hashlib.sha256).digest()


def hkdf_sha256(ikm: bytes, salt: bytes, info: bytes, length: int = AES_KEY_SIZE) -> bytes:
    prk = hmac_sha256(salt, ikm)
    output = b""
    previous = b""
    counter = 1
    while len(output) < length:
        previous = hmac_sha256(prk, previous + info + bytes([counter]))
        output += previous
        counter += 1
    return output[:length]


def _public_bytes(key: ec.EllipticCurvePublicKey) -> bytes:
    return key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def _send_handshake_response(
    conn: socket.socket,
    accepted: bool,
    message: str,
    server_nonce: bytes,
    server_public: bytes,
    proof: bytes,
) -> None:
    message_bytes = message.encode("utf-8")
    conn.sendall(
        MAGIC
        + bytes([VERSION, 0 if accepted else 1])
        + struct.pack("!H", len(message_bytes))
        + message_bytes
        + server_nonce
        + struct.pack("!H", len(server_public))
        + server_public
        + proof
    )


def server_handshake(conn: socket.socket, token: str) -> Session | None:
    """Выполняет серверную часть handshake. Возвращает сессию или None."""
    if not token:
        raise CryptoError("PSK-токен не должен быть пустым")

    header = recv_exact(conn, len(MAGIC) + 3)
    if header is None:
        return None

    magic = header[: len(MAGIC)]
    version = header[len(MAGIC)]
    mtu = struct.unpack("!H", header[len(MAGIC) + 1:])[0]
    if magic != MAGIC or version != VERSION:
        print(f"[-] Неподдерживаемый клиент: magic={magic!r}, version={version}")
        return None

    client_nonce = recv_exact(conn, NONCE_SIZE)
    if client_nonce is None:
        return None

    public_key_length_raw = recv_exact(conn, 2)
    if public_key_length_raw is None:
        return None
    public_key_length = struct.unpack("!H", public_key_length_raw)[0]
    if not 1 <= public_key_length <= 1_024:
        print(f"[-] Некорректная длина публичного ключа: {public_key_length}")
        return None

    client_public = recv_exact(conn, public_key_length)
    proof = recv_exact(conn, PROOF_SIZE)
    if client_public is None or proof is None:
        return None

    try:
        token_bytes = _token_bytes(token)
        expected_proof = hmac_sha256(
            token_bytes,
            request_transcript(mtu, client_nonce, client_public),
        )
        accepted = std_hmac.compare_digest(expected_proof, proof)
    except CryptoError as error:
        print(f"[-] {error}")
        accepted = False

    server_private = ec.generate_private_key(ec.SECP256R1())
    server_public = _public_bytes(server_private.public_key())
    server_nonce = os.urandom(NONCE_SIZE)

    session: Session | None = None
    message = "welcome"
    if accepted:
        try:
            client_public_key = serialization.load_der_public_key(client_public)
            if not isinstance(client_public_key, ec.EllipticCurvePublicKey):
                raise CryptoError("Клиент прислал не EC-ключ")
            shared = server_private.exchange(ec.ECDH(), client_public_key)
            key = hkdf_sha256(
                ikm=shared,
                salt=client_nonce + server_nonce,
                info=HKDF_INFO,
            )
            session = Session(key=key, mtu=mtu)
        except Exception as error:
            accepted = False
            message = "crypto error"
            print(f"[-] Ошибка ECDH: {error}")
    else:
        message = "bad psk"

    server_proof = hmac_sha256(
        _token_bytes(token),
        response_transcript(
            accepted=accepted,
            client_nonce=client_nonce,
            client_public=client_public,
            server_nonce=server_nonce,
            server_public=server_public,
        ),
    )
    _send_handshake_response(
        conn=conn,
        accepted=accepted,
        message=message,
        server_nonce=server_nonce,
        server_public=server_public,
        proof=server_proof,
    )

    if accepted:
        print(f"[+] Handshake OK: ECDH P-256, AES-256-GCM, MTU={mtu}")
        return session

    print("[-] Handshake отклонён: неверный PSK или ключ")
    return None


def client_handshake(conn: socket.socket, token: str, mtu: int = 1_400) -> Session:
    """Клиентская часть handshake — нужна для автотестов Python-серверов."""
    token_bytes = _token_bytes(token)
    client_private = ec.generate_private_key(ec.SECP256R1())
    client_public = _public_bytes(client_private.public_key())
    client_nonce = os.urandom(NONCE_SIZE)
    proof = hmac_sha256(
        token_bytes,
        request_transcript(mtu, client_nonce, client_public),
    )

    conn.sendall(
        MAGIC
        + bytes([VERSION])
        + struct.pack("!H", mtu)
        + client_nonce
        + struct.pack("!H", len(client_public))
        + client_public
        + proof
    )

    header = recv_exact(conn, len(MAGIC) + 2)
    if header is None or header[: len(MAGIC)] != MAGIC or header[len(MAGIC)] != VERSION:
        raise CryptoError("Некорректный ответ сервера")
    status = header[len(MAGIC) + 1]

    message_length_raw = recv_exact(conn, 2)
    if message_length_raw is None:
        raise CryptoError("Сервер закрыл соединение")
    message_length = struct.unpack("!H", message_length_raw)[0]
    message = recv_exact(conn, message_length) if message_length else b""
    if message is None:
        raise CryptoError("Сервер закрыл соединение")

    server_nonce = recv_exact(conn, NONCE_SIZE)
    if server_nonce is None:
        raise CryptoError("Сервер закрыл соединение")
    public_key_length_raw = recv_exact(conn, 2)
    if public_key_length_raw is None:
        raise CryptoError("Сервер закрыл соединение")
    public_key_length = struct.unpack("!H", public_key_length_raw)[0]
    server_public = recv_exact(conn, public_key_length)
    server_proof = recv_exact(conn, PROOF_SIZE)
    if server_public is None or server_proof is None:
        raise CryptoError("Сервер закрыл соединение")

    if status != 0:
        raise CryptoError(f"Сервер отклонил подключение: {message.decode('utf-8', 'replace')}")

    expected = hmac_sha256(
        token_bytes,
        response_transcript(
            accepted=True,
            client_nonce=client_nonce,
            client_public=client_public,
            server_nonce=server_nonce,
            server_public=server_public,
        ),
    )
    if not std_hmac.compare_digest(expected, server_proof):
        raise CryptoError("Сервер не подтвердил знание PSK")

    try:
        server_public_key = serialization.load_der_public_key(server_public)
        if not isinstance(server_public_key, ec.EllipticCurvePublicKey):
            raise CryptoError("Сервер прислал не EC-ключ")
        shared = client_private.exchange(ec.ECDH(), server_public_key)
    except CryptoError:
        raise
    except Exception as error:
        raise CryptoError(f"Ошибка ECDH: {error}") from error

    key = hkdf_sha256(
        ikm=shared,
        salt=client_nonce + server_nonce,
        info=HKDF_INFO,
    )
    return Session(key=key, mtu=mtu)


def send_frame(conn: socket.socket, session: Session, frame_type: int, payload: bytes = b"") -> None:
    encrypted = session.encrypt(frame_type, payload)
    conn.sendall(struct.pack("!BH", frame_type, len(encrypted)) + encrypted)


def read_frame(conn: socket.socket, session: Session) -> tuple[int, bytes] | None:
    header = recv_exact(conn, 3)
    if header is None:
        return None
    frame_type, length = struct.unpack("!BH", header)
    encrypted = recv_exact(conn, length) if length else b""
    if encrypted is None:
        return None
    return frame_type, session.decrypt(frame_type, encrypted)
