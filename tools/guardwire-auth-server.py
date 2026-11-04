#!/usr/bin/env python3
"""Auth/control-plane сервер GuardWire.

Простой логин по username/password:
* проверяет пароль по scrypt-хешу;
* выдаёт случайный сессионный токен;
* пишет активный токен в файл, который читает GuardWire-сервер;
* при наличии прав root добавляет WireGuard peer и отдаёт клиенту параметры
  для сборки WireGuard-конфига.

Запуск для проверки только логина/GuardWire (без WireGuard):
    python3 tools/guardwire-auth-server.py --user demo:demo --disable-wireguard

Запуск с выдачей WireGuard-конфига (нужен root и поднятый wg0):
    sudo python3 tools/guardwire-auth-server.py --user demo:demo --wg-interface wg0

HTTP без TLS предназначен только для теста в доверенной локальной сети.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import guardwire_auth as auth


class AuthServerConfig:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.users: dict[str, dict[str, str]] = {}
        self.state_path = Path(args.wg_state_file)
        self.state = self._load_state()
        self.wg_server_public_key: str | None = None

        for item in args.user:
            if ":" not in item:
                raise SystemExit(f"Неверный --user (нужно username:password): {item}")
            username, password = item.split(":", 1)
            self.users[username] = auth.hash_password(password)

        if not args.disable_wireguard and args.wg_interface:
            if os.geteuid() != 0:
                raise SystemExit(
                    "Для --wg-interface нужен root: запустите через sudo "
                    "или добавьте --disable-wireguard"
                )
            result = subprocess.run(
                ["wg", "show", args.wg_interface, "public-key"],
                text=True,
                capture_output=True,
            )
            if result.returncode != 0:
                raise SystemExit(f"WireGuard-интерфейс {args.wg_interface} недоступен: {result.stderr.strip()}")
            self.wg_server_public_key = result.stdout.strip()

    def _load_state(self) -> dict:
        try:
            return json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {"users": {}}

    def _save_state(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        self.state_path.write_text(
            json.dumps(self.state, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    @property
    def wireguard_enabled(self) -> bool:
        return bool(self.args.wg_interface) and not self.args.disable_wireguard

    def authenticate(self, username: str, password: str) -> bool:
        stored = self.users.get(username)
        return bool(stored) and auth.verify_password(password, stored)

    def provision_wireguard(self, username: str, public_key: str) -> dict | None:
        if not self.wireguard_enabled:
            return None
        if not public_key:
            raise auth.AuthError("Не передан WireGuard public key")

        interface = self.args.wg_interface
        users = self.state.setdefault("users", {})
        record = users.get(username)
        if record is None:
            record = {"ip": self._allocate_ip(users)}
            users[username] = record

        old_key = record.get("public_key")
        if old_key and old_key != public_key:
            subprocess.run(
                ["wg", "set", interface, "peer", old_key, "remove"],
                capture_output=True,
            )

        result = subprocess.run(
            [
                "wg", "set", interface,
                "peer", public_key,
                "allowed-ips", f"{record['ip']}/32",
            ],
            text=True,
            capture_output=True,
        )
        if result.returncode != 0:
            raise auth.AuthError(f"Не удалось добавить WireGuard peer: {result.stderr.strip()}")

        record["public_key"] = public_key
        self._save_state()

        return {
            "server_public_key": self.wg_server_public_key,
            "endpoint": self.args.wg_endpoint,
            "client_ip": f"{record['ip']}/32",
            "dns": self.args.wg_dns,
            "mtu": self.args.wg_mtu,
        }

    def _allocate_ip(self, users: dict) -> str:
        used = {record.get("ip") for record in users.values()}
        base = self.args.wg_client_ip_base.rstrip(".")
        for octet in range(self.args.wg_client_ip_start, 255):
            ip = f"{base}.{octet}"
            if ip not in used:
                return ip
        raise auth.AuthError("Закончились адреса в WireGuard-пуле")


CONFIG: AuthServerConfig


class Handler(BaseHTTPRequestHandler):
    server_version = "GuardWireAuth/1.0"

    def do_GET(self) -> None:
        if self.path == "/auth/health":
            self._json(200, {
                "status": "ok",
                "wireguard": CONFIG.wireguard_enabled,
                "users": sorted(CONFIG.users.keys()),
            })
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path != "/auth/login":
            self._json(404, {"error": "not found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 16_384:
                raise ValueError("Некорректный размер запроса")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            username = str(payload.get("username", "")).strip()
            password = str(payload.get("password", ""))
            public_key = str(payload.get("wg_public_key", "")).strip()
        except Exception as error:
            self._json(400, {"error": f"Некорректный JSON: {error}"})
            return

        if not CONFIG.authenticate(username, password):
            print(f"[-] Неудачный вход: {username!r}")
            self._json(401, {"error": "Неверный логин или пароль"})
            return

        try:
            wireguard = CONFIG.provision_wireguard(username, public_key)
        except auth.AuthError as error:
            print(f"[-] WireGuard provisioning: {error}")
            self._json(500, {"error": str(error)})
            return

        token = auth.new_token()
        expires_at = time.time() + CONFIG.args.token_ttl
        auth.write_token_file(
            CONFIG.args.token_file,
            token=token,
            username=username,
            expires_at=expires_at,
        )
        print(f"[+] Вход: {username!r}, токен выдан до {time.strftime('%H:%M:%S', time.localtime(expires_at))}")
        self._json(200, {
            "token": token,
            "username": username,
            "expires_at": expires_at,
            "wireguard": wireguard,
        })

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: object) -> None:
        print(f"[HTTP] {self.address_string()} {fmt % args}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GuardWire auth/control plane")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument(
        "--user",
        action="append",
        default=[],
        help="username:password; можно повторять (по умолчанию demo:demo)",
    )
    parser.add_argument("--token-file", default="tools/.guardwire-token")
    parser.add_argument("--token-ttl", type=int, default=12 * 3600)
    parser.add_argument("--wg-interface", default=None, help="например wg0; требует root")
    parser.add_argument("--wg-endpoint", default=None, help="например 192.168.100.4:51820")
    parser.add_argument("--wg-dns", default="1.1.1.1")
    parser.add_argument("--wg-mtu", type=int, default=1420)
    parser.add_argument("--wg-client-ip-base", default="10.66.0")
    parser.add_argument("--wg-client-ip-start", type=int, default=3)
    parser.add_argument("--wg-state-file", default="tools/.guardwire-wg-state.json")
    parser.add_argument("--disable-wireguard", action="store_true")
    return parser.parse_args()


def main() -> int:
    global CONFIG
    args = parse_args()
    if not args.user:
        args.user = ["demo:demo"]

    if args.wg_interface and not args.wg_endpoint:
        # Определяем LAN-IP по default route.
        result = subprocess.run(["ip", "-4", "route", "get", "1.1.1.1"], text=True, capture_output=True)
        parts = result.stdout.split()
        src = parts[parts.index("src") + 1] if "src" in parts else "127.0.0.1"
        args.wg_endpoint = f"{src}:51820"

    CONFIG = AuthServerConfig(args)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"GuardWire auth server: http://{args.host}:{args.port}")
    print(f"Пользователи: {', '.join(sorted(CONFIG.users))}")
    print(f"WireGuard: {'включён, ' + str(args.wg_interface) if CONFIG.wireguard_enabled else 'выключен'}")
    print(f"Token file: {args.token_file}")
    if CONFIG.wireguard_enabled:
        print(f"WireGuard endpoint: {args.wg_endpoint}")
    print("HTTP без TLS: только для теста в доверенной сети.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nОстановка auth-сервера")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
