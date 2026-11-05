#!/usr/bin/env python3
"""Auth/control-plane сервер GuardWire.

Простой логин по username/password:
* проверяет пароль по scrypt-хешу;
* выдаёт короткий access-токен и длинный refresh-токен;
* access-токен пишется в файл, который читает GuardWire-сервер;
* refresh-токен хранится только в виде SHA-256 и позволяет продлить сессию;
* при наличии прав root добавляет WireGuard peer и отдаёт клиенту параметры
  для сборки WireGuard-конфига;
* logout отзывает refresh-сессию, удаляет WireGuard peer и активный токен.

Запуск для проверки только логина/GuardWire (без WireGuard):
    python3 tools/guardwire-auth-server.py --user demo:demo --disable-wireguard

Запуск с выдачей WireGuard-конфига (нужен root и поднятый wg0):
    sudo python3 tools/guardwire-auth-server.py --user demo:demo --wg-interface wg0

По умолчанию сервер работает по HTTPS с локальным CA из
tools/.guardwire-certs. Перед первым запуском:
    ./tools/gen-auth-cert.sh 192.168.100.4

Для отладки можно отключить TLS: --no-tls.
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import ssl
import subprocess
import threading
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
        self._lock = threading.RLock()
        self.sessions_path = Path(args.session_file)
        self.sessions = self._load_sessions()
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
        payload = auth.load_json_file(self.state_path, {"users": {}})
        if not isinstance(payload.get("users"), dict):
            return {"users": {}}
        return payload

    def _save_state(self) -> None:
        auth.write_json_file(self.state_path, self.state)

    def _load_sessions(self) -> dict[str, dict]:
        payload = auth.load_json_file(self.sessions_path, {"sessions": {}})
        raw = payload.get("sessions", {})
        if not isinstance(raw, dict):
            return {}
        now = time.time()
        sessions = {
            key: record
            for key, record in raw.items()
            if isinstance(record, dict)
            and float(record.get("refresh_expires_at", 0)) > now
        }
        if len(sessions) != len(raw):
            auth.write_json_file(self.sessions_path, {"sessions": sessions})
        return sessions

    def _save_sessions(self) -> None:
        auth.write_json_file(self.sessions_path, {"sessions": self.sessions})

    @property
    def wireguard_enabled(self) -> bool:
        return bool(self.args.wg_interface) and not self.args.disable_wireguard

    def authenticate(self, username: str, password: str) -> bool:
        stored = self.users.get(username)
        return bool(stored) and auth.verify_password(password, stored)

    def create_session(self, username: str) -> dict:
        """Создаёт единственную активную сессию и пишет access-токен для GuardWire."""
        with self._lock:
            self.sessions.clear()
            access_token = auth.new_token()
            refresh_token = auth.new_refresh_token()
            now = time.time()
            access_expires_at = now + self.args.access_ttl
            refresh_expires_at = now + self.args.refresh_ttl

            self.sessions[auth.token_digest(refresh_token)] = {
                "username": username,
                "access_hash": auth.token_digest(access_token),
                "access_expires_at": access_expires_at,
                "refresh_expires_at": refresh_expires_at,
                "created_at": now,
            }
            self._save_sessions()
            auth.write_token_file(
                self.args.token_file,
                token=access_token,
                username=username,
                expires_at=access_expires_at,
            )

            return {
                "username": username,
                "access_token": access_token,
                "refresh_token": refresh_token,
                "access_expires_at": access_expires_at,
                "refresh_expires_at": refresh_expires_at,
            }

    def refresh_session(self, refresh_token: str) -> dict | None:
        """Меняет refresh-токен и выдаёт новый короткий access-токен."""
        with self._lock:
            old_digest = auth.token_digest(refresh_token)
            record = self.sessions.pop(old_digest, None)
            now = time.time()
            if record is None or float(record.get("refresh_expires_at", 0)) <= now:
                self._save_sessions()
                return None

            access_token = auth.new_token()
            new_refresh_token = auth.new_refresh_token()
            access_expires_at = now + self.args.access_ttl
            refresh_expires_at = now + self.args.refresh_ttl
            record.update(
                {
                    "access_hash": auth.token_digest(access_token),
                    "access_expires_at": access_expires_at,
                    "refresh_expires_at": refresh_expires_at,
                }
            )
            self.sessions[auth.token_digest(new_refresh_token)] = record
            self._save_sessions()
            auth.write_token_file(
                self.args.token_file,
                token=access_token,
                username=record["username"],
                expires_at=access_expires_at,
            )

            return {
                "username": record["username"],
                "access_token": access_token,
                "refresh_token": new_refresh_token,
                "access_expires_at": access_expires_at,
                "refresh_expires_at": refresh_expires_at,
            }

    def revoke_session(self, refresh_token: str) -> bool:
        """Отзывает сессию, удаляет peer и активный access-токен."""
        with self._lock:
            record = self.sessions.pop(auth.token_digest(refresh_token), None)
            if record is None:
                return False

            active = auth.load_token_record(self.args.token_file)
            if active and active.get("username") == record.get("username"):
                if auth.token_digest(active.get("token", "")) == record.get("access_hash"):
                    auth.clear_token_file(self.args.token_file)

            self._save_sessions()
            self.deprovision_wireguard(record["username"])
            return True

    def provision_wireguard(self, username: str, public_key: str) -> dict | None:
        if not self.wireguard_enabled:
            return None
        if not public_key:
            raise auth.AuthError("WireGuard public key is missing")

        with self._lock:
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
                raise auth.AuthError(f"Failed to add WireGuard peer: {result.stderr.strip()}")

            record["public_key"] = public_key
            self._save_state()

            return {
                "server_public_key": self.wg_server_public_key,
                "endpoint": self.args.wg_endpoint,
                "client_ip": f"{record['ip']}/32",
                "dns": self.args.wg_dns,
                "mtu": self.args.wg_mtu,
            }

    def deprovision_wireguard(self, username: str) -> None:
        if not self.wireguard_enabled:
            return

        with self._lock:
            users = self.state.setdefault("users", {})
            record = users.pop(username, None)
            public_key = record.get("public_key") if record else None
            if public_key:
                subprocess.run(
                    ["wg", "set", self.args.wg_interface, "peer", public_key, "remove"],
                    capture_output=True,
                )
            self._save_state()

    def _allocate_ip(self, users: dict) -> str:
        used = {record.get("ip") for record in users.values()}
        base = self.args.wg_client_ip_base.rstrip(".")
        for octet in range(self.args.wg_client_ip_start, 255):
            ip = f"{base}.{octet}"
            if ip not in used:
                return ip
        raise auth.AuthError("WireGuard IP pool exhausted")


CONFIG: AuthServerConfig


class Handler(BaseHTTPRequestHandler):
    server_version = "GuardWireAuth/2.1"

    def do_GET(self) -> None:
        if self.path == "/auth/health":
            self._json(200, {
                "status": "ok",
                "wireguard": CONFIG.wireguard_enabled,
                "users": sorted(CONFIG.users.keys()),
                "sessions": len(CONFIG.sessions),
            })
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if self.path not in {"/auth/login", "/auth/refresh", "/auth/logout", "/auth/wireguard"}:
            self._json(404, {"error": "not found"})
            return

        try:
            payload = self._read_json()
        except Exception as error:
            self._json(400, {"error": f"Invalid JSON: {error}"})
            return

        if self.path == "/auth/login":
            self._handle_login(payload)
        elif self.path == "/auth/refresh":
            self._handle_refresh(payload)
        elif self.path == "/auth/wireguard":
            self._handle_wireguard(payload)
        else:
            self._handle_logout(payload)

    def _handle_login(self, payload: dict) -> None:
        username = str(payload.get("username", "")).strip()
        password = str(payload.get("password", ""))
        public_key = str(payload.get("wg_public_key", "")).strip()

        if not CONFIG.authenticate(username, password):
            print(f"[-] Неудачный вход: {username!r}")
            self._json(401, {"error": "Invalid username or password"})
            return

        try:
            wireguard = CONFIG.provision_wireguard(username, public_key)
        except auth.AuthError as error:
            print(f"[-] WireGuard provisioning: {error}")
            self._json(500, {"error": str(error)})
            return

        session = CONFIG.create_session(username)
        print(
            f"[+] Вход: {username!r}, access до "
            f"{time.strftime('%H:%M:%S', time.localtime(session['access_expires_at']))}, "
            f"refresh до "
            f"{time.strftime('%d.%m %H:%M', time.localtime(session['refresh_expires_at']))}"
        )
        self._json(200, {**session, "wireguard": wireguard})

    def _handle_refresh(self, payload: dict) -> None:
        refresh_token = str(payload.get("refresh_token", "")).strip()
        if not refresh_token:
            self._json(400, {"error": "refresh_token is missing"})
            return

        session = CONFIG.refresh_session(refresh_token)
        if session is None:
            self._json(401, {"error": "Refresh token is invalid or expired"})
            return

        print(f"[+] Сессия продлена: {session['username']!r}")
        self._json(200, session)

    def _handle_wireguard(self, payload: dict) -> None:
        if not CONFIG.wireguard_enabled:
            self._json(503, {"error": "WireGuard is disabled on the auth server"})
            return

        username = self._access_username()
        if username is None:
            self._json(401, {"error": "Access token is invalid or expired"})
            return

        public_key = str(payload.get("wg_public_key", "")).strip()
        try:
            wireguard = CONFIG.provision_wireguard(username, public_key)
        except auth.AuthError as error:
            self._json(500, {"error": str(error)})
            return

        print(f"[+] WireGuard-профиль выдан: {username!r}")
        self._json(200, {"wireguard": wireguard})

    def _access_username(self) -> str | None:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return None
        token = header.removeprefix("Bearer ").strip()
        record = auth.load_token_record(CONFIG.args.token_file)
        if not record or not hmac.compare_digest(record.get("token", ""), token):
            return None
        return str(record.get("username", "")) or None

    def _handle_logout(self, payload: dict) -> None:
        refresh_token = str(payload.get("refresh_token", "")).strip()
        if refresh_token:
            CONFIG.revoke_session(refresh_token)
        self._json(200, {"status": "ok"})

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 16_384:
            raise ValueError("Invalid request size")
        payload = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("Expected a JSON object")
        return payload

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
    parser.add_argument(
        "--access-ttl",
        "--token-ttl",
        dest="access_ttl",
        type=int,
        default=15 * 60,
        help="время жизни access-токена, секунды",
    )
    parser.add_argument(
        "--refresh-ttl",
        type=int,
        default=30 * 24 * 3600,
        help="время жизни refresh-токена, секунды",
    )
    parser.add_argument(
        "--session-file",
        default="tools/.guardwire-sessions.json",
        help="файл refresh-сессий (хранятся только SHA-256)",
    )
    parser.add_argument("--wg-interface", default=None, help="например wg0; требует root")
    parser.add_argument("--wg-endpoint", default=None, help="например 192.168.100.4:51820")
    parser.add_argument("--wg-dns", default="1.1.1.1")
    parser.add_argument("--wg-mtu", type=int, default=1420)
    parser.add_argument("--wg-client-ip-base", default="10.66.0")
    parser.add_argument("--wg-client-ip-start", type=int, default=3)
    parser.add_argument("--wg-state-file", default="tools/.guardwire-wg-state.json")
    parser.add_argument("--disable-wireguard", action="store_true")
    parser.add_argument(
        "--tls-cert",
        default="tools/.guardwire-certs/auth-cert.pem",
        help="TLS-сертификат auth-сервера",
    )
    parser.add_argument(
        "--tls-key",
        default="tools/.guardwire-certs/auth-key.pem",
        help="TLS-ключ auth-сервера",
    )
    parser.add_argument(
        "--no-tls",
        action="store_true",
        help="запустить без TLS (только для отладки)",
    )
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

    scheme = "http"
    if not args.no_tls:
        cert_path = Path(args.tls_cert)
        key_path = Path(args.tls_key)
        if not cert_path.is_file() or not key_path.is_file():
            print(
                "TLS-сертификат не найден. Сгенерируйте его:\n"
                "  ./tools/gen-auth-cert.sh 192.168.100.4\n"
                "или запустите с --no-tls (только для отладки).",
                file=sys.stderr,
            )
            server.server_close()
            return 1
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=str(cert_path), keyfile=str(key_path))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        scheme = "https"

    print(f"GuardWire auth server: {scheme}://{args.host}:{args.port}")
    print(f"Пользователи: {', '.join(sorted(CONFIG.users))}")
    print(f"WireGuard: {'включён, ' + str(args.wg_interface) if CONFIG.wireguard_enabled else 'выключен'}")
    print(f"Access TTL: {args.access_ttl} сек, refresh TTL: {args.refresh_ttl} сек")
    print(f"Token file: {args.token_file}")
    print(f"Session file: {args.session_file}")
    if CONFIG.wireguard_enabled:
        print(f"WireGuard endpoint: {args.wg_endpoint}")
    if args.no_tls:
        print("ВНИМАНИЕ: TLS отключён (--no-tls), только для отладки.")
    else:
        print(f"TLS certificate: {args.tls_cert}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nОстановка auth-сервера")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
