#!/usr/bin/env python3
"""Минимальный GuardWire-сервер для проверки демо-приложения.

Сервер выполняет только рукопожатие и разбирает кадры. Он НЕ маршрутизирует
IP-пакеты в интернет, поэтому приложение покажет статус "Защищено", но
реальный выход в сеть не появится. Это удобно для проверки UI, permission-
диалогов, foreground-сервиса и жизненного цикла туннеля.

Запуск:
    python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
"""

from __future__ import annotations

import argparse
import socket
import struct
import threading
import time

MAGIC = b"GUARDWIRE"
VERSION = 1

FRAME_DATA = 0x01
FRAME_KEEP_ALIVE = 0x02
FRAME_DISCONNECT = 0x03
FRAME_PING = 0x04
FRAME_PONG = 0x05

FRAME_NAMES = {
    FRAME_DATA: "DATA",
    FRAME_KEEP_ALIVE: "KEEP_ALIVE",
    FRAME_DISCONNECT: "DISCONNECT",
    FRAME_PING: "PING",
    FRAME_PONG: "PONG",
}


def recv_exact(conn: socket.socket, size: int) -> bytes | None:
    data = b""
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            return None
        data += chunk
    return data


def send_frame(conn: socket.socket, frame_type: int, payload: bytes = b"") -> None:
    conn.sendall(struct.pack("!BH", frame_type, len(payload)) + payload)


def read_handshake(conn: socket.socket) -> tuple[str, int] | None:
    header = recv_exact(conn, len(MAGIC) + 3)
    if header is None:
        return None

    magic = header[: len(MAGIC)]
    version = header[len(MAGIC)]
    token_length = struct.unpack("!H", header[len(MAGIC) + 1 :])[0]

    token = recv_exact(conn, token_length)
    mtu_raw = recv_exact(conn, 2)
    if token is None or mtu_raw is None:
        return None

    if magic != MAGIC:
        print("[!] Клиент прислал неверный magic")
        return None
    if version != VERSION:
        print(f"[!] Неподдерживаемая версия: {version}")
        return None

    mtu = struct.unpack("!H", mtu_raw)[0]
    return token.decode("utf-8", errors="replace"), mtu


def send_handshake_response(conn: socket.socket, accepted: bool, message: str) -> None:
    body = message.encode("utf-8")
    conn.sendall(
        MAGIC
        + bytes([VERSION, 0 if accepted else 1])
        + struct.pack("!H", len(body))
        + body
    )


def handle_client(conn: socket.socket, address: tuple[str, int], expected_token: str) -> None:
    peer = f"{address[0]}:{address[1]}"
    print(f"[+] Подключение: {peer}")

    with conn:
        handshake = read_handshake(conn)
        if handshake is None:
            print(f"[-] {peer}: рукопожатие не удалось")
            return

        token, mtu = handshake
        if expected_token and token != expected_token:
            send_handshake_response(conn, False, "bad token")
            print(f"[-] {peer}: неверный токен")
            return

        send_handshake_response(conn, True, "welcome")
        print(f"[+] {peer}: GuardWire/1, MTU={mtu}")

        bytes_in = 0
        packets_in = 0
        last_report = time.monotonic()

        while True:
            header = recv_exact(conn, 3)
            if header is None:
                print(f"[-] {peer}: соединение закрыто")
                return

            frame_type, length = struct.unpack("!BH", header)
            payload = recv_exact(conn, length) if length else b""
            if payload is None:
                print(f"[-] {peer}: обрезанный кадр")
                return

            if frame_type == FRAME_DATA:
                bytes_in += length
                packets_in += 1
            elif frame_type == FRAME_PING:
                send_frame(conn, FRAME_PONG)
            elif frame_type == FRAME_KEEP_ALIVE:
                pass
            elif frame_type == FRAME_DISCONNECT:
                print(f"[.] {peer}: DISCONNECT")
                return
            else:
                print(f"[?] {peer}: неизвестный кадр 0x{frame_type:02x}")

            now = time.monotonic()
            if now - last_report >= 5.0:
                name = FRAME_NAMES.get(frame_type, f"0x{frame_type:02x}")
                print(
                    f"[i] {peer}: пакетов={packets_in}, байт={bytes_in}, "
                    f"последний кадр={name}"
                )
                last_report = now


def main() -> None:
    parser = argparse.ArgumentParser(description="GuardWire demo server")
    parser.add_argument("--host", default="0.0.0.0", help="адрес прослушивания")
    parser.add_argument("--port", type=int, default=8443, help="TCP-порт")
    parser.add_argument("--token", default="demo", help="ожидаемый токен")
    args = parser.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen(8)
        print(
            f"GuardWire demo server слушает {args.host}:{args.port}, "
            f"токен={args.token!r}"
        )
        print("IP-пакеты не маршрутизируются: это сервер только для проверки туннеля.")

        while True:
            conn, address = server.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            thread = threading.Thread(
                target=handle_client,
                args=(conn, address, args.token),
                daemon=True,
            )
            thread.start()


if __name__ == "__main__":
    main()
