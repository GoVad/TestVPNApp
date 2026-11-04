#!/usr/bin/env python3
"""Минимальный GuardWire-сервер для проверки демо-приложения.

Сервер выполняет рукопожатие GuardWire/2 с ECDH + AES-GCM и разбирает
зашифрованные кадры. Он НЕ маршрутизирует IP-пакеты в интернет, поэтому
приложение покажет статус "Защищено", но реальный выход в сеть не появится.
Это удобно для проверки UI, permission-диалогов, foreground-сервиса и
жизненного цикла туннеля.

Требуется:
    python3 -m pip install cryptography

Запуск:
    python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
"""

from __future__ import annotations

import argparse
import socket
import threading
import time

import guardwire_auth
import guardwire_crypto as gw

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


def handle_client(conn: socket.socket, address: tuple[str, int], static_token: str, auth_token_file: str | None) -> None:
    peer = f"{address[0]}:{address[1]}"
    print(f"[+] Подключение: {peer}")

    with conn:
        token = static_token
        if auth_token_file:
            token = guardwire_auth.load_active_token(auth_token_file) or ""
            if not token:
                print(f"[-] {peer}: нет активного auth-токена")
                return
        conn.settimeout(10.0)
        try:
            session = gw.server_handshake(conn, token)
        except (TimeoutError, OSError) as error:
            print(f"[-] {peer}: handshake error: {error}")
            return

        if session is None:
            print(f"[-] {peer}: рукопожатие не удалось")
            return

        conn.settimeout(None)
        print(f"[+] {peer}: GuardWire/2, MTU={session.mtu}, трафик шифруется")

        bytes_in = 0
        packets_in = 0
        last_report = time.monotonic()

        while True:
            try:
                frame = gw.read_frame(conn, session)
            except (TimeoutError, OSError, gw.CryptoError) as error:
                print(f"[-] {peer}: {error}")
                return

            if frame is None:
                print(f"[-] {peer}: соединение закрыто")
                return

            frame_type, payload = frame
            if frame_type == FRAME_DATA:
                bytes_in += len(payload)
                packets_in += 1
            elif frame_type == FRAME_PING:
                gw.send_frame(conn, session, FRAME_PONG)
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
    parser.add_argument("--token", default="demo", help="статический PSK-токен")
    parser.add_argument("--auth-token-file", default=None, help="файл активного auth-токена")
    args = parser.parse_args()

    if not args.token:
        parser.error("--token не должен быть пустым: это PSK для GuardWire/2")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen(8)
        print(f"GuardWire/2 demo server слушает {args.host}:{args.port}")
        print("Трафик шифруется AES-256-GCM. IP-пакеты не маршрутизируются.")
        if args.auth_token_file:
            print(f"Auth: ожидаю токен из {args.auth_token_file}")

        while True:
            conn, address = server.accept()
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            thread = threading.Thread(
                target=handle_client,
                args=(conn, address, args.token, args.auth_token_file),
                daemon=True,
            )
            thread.start()


if __name__ == "__main__":
    main()
