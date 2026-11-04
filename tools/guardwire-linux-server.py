#!/usr/bin/env python3
"""GuardWire/2 server для Linux с настоящим TUN + NAT.

В отличие от guardwire-demo-server.py этот сервер не выбрасывает IP-пакеты,
а заводит их в TUN-интерфейс Linux. Ядро маршрутизирует их в интернет через
NAT, а ответы возвращает обратно в TUN и, соответственно, на телефон.

Требуется root (CAP_NET_ADMIN) и iptables.

Запуск:
    sudo python3 tools/guardwire-linux-server.py --host 0.0.0.0 --port 8443 --token demo

Телефон должен быть в той же локальной сети, а в приложении нужно указать
LAN-IP компьютера, например 192.168.100.4.

Посмотреть план команд без выполнения:
    python3 tools/guardwire-linux-server.py --dry-run

Если включён ufw, откройте порт:
    sudo ufw allow 8443/tcp
"""

from __future__ import annotations

import argparse
import atexit
import fcntl
import os
import select
import signal
import socket
import struct
import subprocess
import sys
import time

import guardwire_auth
import guardwire_crypto as gw

TUNSETIFF = 0x400454CA
IFF_TUN = 0x0001
IFF_NO_PI = 0x1000

FRAME_DATA = 0x01
FRAME_KEEP_ALIVE = 0x02
FRAME_DISCONNECT = 0x03
FRAME_PING = 0x04
FRAME_PONG = 0x05


class SetupError(RuntimeError):
    pass


def run(
    cmd: list[str],
    *,
    dry_run: bool = False,
    check: bool = True,
) -> subprocess.CompletedProcess | None:
    printable = " ".join(cmd)
    print(f"  $ {printable}")
    if dry_run:
        return None
    result = subprocess.run(cmd, text=True, capture_output=True)
    if check and result.returncode != 0:
        raise SetupError(
            f"Команда завершилась с кодом {result.returncode}: {printable}\n"
            f"{result.stderr.strip()}"
        )
    return result


def detect_lan_iface() -> str:
    """Определяет интерфейс default-маршрута (enp3s0, wlan0 и т.п.)."""
    result = subprocess.run(
        ["ip", "-4", "route", "show", "default"],
        text=True,
        capture_output=True,
    )
    for line in result.stdout.splitlines():
        parts = line.split()
        if "dev" in parts:
            return parts[parts.index("dev") + 1]

    result = subprocess.run(
        ["ip", "-4", "route", "get", "1.1.1.1"],
        text=True,
        capture_output=True,
    )
    parts = result.stdout.split()
    if "dev" in parts:
        return parts[parts.index("dev") + 1]

    raise SetupError("Не удалось определить LAN-интерфейс. Укажите --lan-iface вручную.")


def detect_lan_ips(exclude: set[str] | None = None) -> list[tuple[str, str]]:
    excluded = exclude or set()
    result = subprocess.run(
        ["ip", "-4", "-o", "addr", "show", "scope", "global"],
        text=True,
        capture_output=True,
    )
    addresses: list[tuple[str, str]] = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) < 4 or "inet" not in parts:
            continue
        iface = parts[1].rstrip(":")
        if iface in excluded or iface.startswith("docker"):
            continue
        addresses.append((iface, parts[parts.index("inet") + 1].split("/")[0]))
    return addresses


class Server:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.tun_fd: int | None = None
        self.tun_name = args.tun
        if args.no_nat:
            self.lan_iface = args.lan_iface or "—"
        else:
            self.lan_iface = args.lan_iface or detect_lan_iface()
        self.client_network = args.client_network
        self.rules_added: list[tuple[list[str], list[str]]] = []
        self.old_ip_forward: str | None = None
        self.running = True
        self.cleaned = False
        self.stats = {
            "packets_from_phone": 0,
            "bytes_from_phone": 0,
            "packets_to_phone": 0,
            "bytes_to_phone": 0,
        }

    # ---------- setup / cleanup ----------

    def setup(self) -> None:
        if os.geteuid() != 0:
            script = os.path.abspath(sys.argv[0])
            raise SetupError(
                "Нужен root: запустите через sudo.\n"
                f"  sudo python3 {script} --host 0.0.0.0 --port {self.args.port} "
                f"--token {self.args.token}"
            )

        atexit.register(self.cleanup)
        self._remove_stale_tun()
        self._open_tun()
        self._configure_tun()

        self.old_ip_forward = self._read_ip_forward()
        if self.old_ip_forward != "1":
            print("\n[+] Включаю IPv4 forwarding")
            run(["sysctl", "-w", "net.ipv4.ip_forward=1"])
        else:
            print("\n[+] IPv4 forwarding уже включён")

        if not self.args.no_nat:
            self._add_nat_rules()

    def _read_ip_forward(self) -> str:
        try:
            with open("/proc/sys/net/ipv4/ip_forward", encoding="utf-8") as file:
                return file.read().strip()
        except OSError:
            return "1"

    def _remove_stale_tun(self) -> None:
        result = subprocess.run(
            ["ip", "link", "show", self.tun_name],
            text=True,
            capture_output=True,
        )
        if result.returncode == 0:
            print(f"[!] Удаляю старый TUN {self.tun_name}")
            run(["ip", "link", "delete", self.tun_name], check=False)

    def _open_tun(self) -> None:
        print(f"\n[+] Создаю TUN {self.tun_name}")
        fd = os.open("/dev/net/tun", os.O_RDWR)
        ifr = struct.pack("16sH", self.tun_name.encode("utf-8"), IFF_TUN | IFF_NO_PI)
        try:
            fcntl.ioctl(fd, TUNSETIFF, ifr)
        except OSError as error:
            os.close(fd)
            raise SetupError(f"Не удалось создать TUN: {error}") from error
        self.tun_fd = fd

    def _configure_tun(self) -> None:
        run(["ip", "addr", "add", self.args.server_ip + "/24", "dev", self.tun_name])
        run(["ip", "link", "set", self.tun_name, "mtu", str(self.args.mtu), "up"])

    def _add_nat_rules(self) -> None:
        print(f"[+] Включаю NAT через {self.lan_iface} для {self.client_network}")
        comment = ["-m", "comment", "--comment", "guardwire-demo"]

        rules = [
            (
                [
                    "iptables", "-t", "nat", "-A", "POSTROUTING",
                    "-s", self.client_network, "-o", self.lan_iface,
                    *comment, "-j", "MASQUERADE",
                ],
                [
                    "iptables", "-t", "nat", "-D", "POSTROUTING",
                    "-s", self.client_network, "-o", self.lan_iface,
                    *comment, "-j", "MASQUERADE",
                ],
            ),
            (
                [
                    "iptables", "-I", "FORWARD", "1",
                    "-i", self.tun_name, "-o", self.lan_iface,
                    *comment, "-j", "ACCEPT",
                ],
                [
                    "iptables", "-D", "FORWARD",
                    "-i", self.tun_name, "-o", self.lan_iface,
                    *comment, "-j", "ACCEPT",
                ],
            ),
            (
                [
                    "iptables", "-I", "FORWARD", "1",
                    "-i", self.lan_iface, "-o", self.tun_name,
                    "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED",
                    *comment, "-j", "ACCEPT",
                ],
                [
                    "iptables", "-D", "FORWARD",
                    "-i", self.lan_iface, "-o", self.tun_name,
                    "-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED",
                    *comment, "-j", "ACCEPT",
                ],
            ),
        ]

        for add_rule, delete_rule in rules:
            run(add_rule)
            self.rules_added.append((add_rule, delete_rule))

    def cleanup(self) -> None:
        if self.cleaned:
            return
        self.cleaned = True

        if self.rules_added:
            print("\n[+] Убираю NAT-правила и TUN")
            for _add_rule, delete_rule in reversed(self.rules_added):
                run(delete_rule, check=False)
            self.rules_added.clear()

        had_tun = self.tun_fd is not None
        if self.tun_fd is not None:
            os.close(self.tun_fd)
            self.tun_fd = None
        if had_tun:
            run(["ip", "link", "del", self.tun_name], check=False)

        if self.old_ip_forward == "0":
            run(["sysctl", "-w", "net.ipv4.ip_forward=0"], check=False)
        self.old_ip_forward = None

    def dry_run(self) -> None:
        print("План настройки (ничего не выполняется):")
        print(f"  TUN:          {self.tun_name}")
        print(f"  server IP:    {self.args.server_ip}/24")
        print(f"  client net:   {self.client_network}")
        print(f"  MTU:          {self.args.mtu}")
        print(f"  LAN iface:    {self.lan_iface}")
        print(f"  root:         требуется")
        print()
        print(f"  ioctl TUNSETIFF: {self.tun_name} (IFF_TUN|IFF_NO_PI)")
        run(["ip", "addr", "add", self.args.server_ip + "/24", "dev", self.tun_name], dry_run=True)
        run(["ip", "link", "set", self.tun_name, "mtu", str(self.args.mtu), "up"], dry_run=True)
        run(["sysctl", "-w", "net.ipv4.ip_forward=1"], dry_run=True)
        if not self.args.no_nat:
            print(
                f"  iptables -t nat -A POSTROUTING -s {self.client_network} "
                f"-o {self.lan_iface} -j MASQUERADE"
            )
            print(f"  iptables -I FORWARD 1 -i {self.tun_name} -o {self.lan_iface} -j ACCEPT")
            print(
                f"  iptables -I FORWARD 1 -i {self.lan_iface} -o {self.tun_name} "
                "-m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT"
            )

    # ---------- protocol ----------

    def serve_forever(self) -> None:
        assert self.tun_fd is not None
        socks = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        socks.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

        try:
            socks.bind((self.args.host, self.args.port))
            socks.listen(1)
            socks.settimeout(1.0)
        except OSError as error:
            socks.close()
            raise SetupError(
                f"Не удалось занять {self.args.host}:{self.args.port}: {error}"
            ) from error

        print("\n[+] Готово. Подключайтесь с телефона:")
        lan_addresses = detect_lan_ips(exclude={self.tun_name, "lo"})
        for iface, ip in lan_addresses:
            print(f"    {iface}: host={ip}  port={self.args.port}  (PSK задан)")
        if not lan_addresses:
            print("    host=<LAN-IP компьютера>")
        print("    Порт должен быть доступен в firewall/ufw.")
        print("    Если ufw включён: sudo ufw allow {}/tcp".format(self.args.port))
        print("\nОжидаю клиента… (Ctrl+C для остановки)")

        try:
            while self.running:
                try:
                    conn, address = socks.accept()
                except (socket.timeout, TimeoutError):
                    continue
                except OSError:
                    break

                print(f"\n[+] Клиент {address[0]}:{address[1]}")
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                with conn:
                    conn.settimeout(10.0)
                    token = self.args.token
                    if self.args.auth_token_file:
                        token = guardwire_auth.load_active_token(self.args.auth_token_file) or ""
                        if not token:
                            print("[-] Нет активного auth-токена, клиент отклонён")
                            continue
                    try:
                        session = gw.server_handshake(conn, token)
                    except (TimeoutError, OSError, gw.CryptoError) as error:
                        print(f"[-] Handshake error: {error}")
                        session = None

                    if session is not None:
                        print("[+] Трафик шифруется AES-256-GCM")
                        self.handle_session(conn, session)

                print("[-] Клиент отключён, жду следующего")
                self.stats = {
                    "packets_from_phone": 0,
                    "bytes_from_phone": 0,
                    "packets_to_phone": 0,
                    "bytes_to_phone": 0,
                }
        except KeyboardInterrupt:
            print("\n[!] Остановка по Ctrl+C")
        finally:
            self.running = False
            socks.close()

    def handle_session(self, conn: socket.socket, session: gw.Session) -> None:
        assert self.tun_fd is not None
        conn.settimeout(2.0)
        last_stats = time.monotonic()

        while self.running:
            try:
                readable, _, _ = select.select([conn, self.tun_fd], [], [], 1.0)
            except (OSError, ValueError):
                return

            for ready in readable:
                if ready == self.tun_fd:
                    try:
                        packet = os.read(self.tun_fd, 65_535)
                    except OSError:
                        return
                    if not packet:
                        continue
                    try:
                        gw.send_frame(conn, session, FRAME_DATA, packet)
                    except (OSError, gw.CryptoError):
                        return
                    self.stats["packets_to_phone"] += 1
                    self.stats["bytes_to_phone"] += len(packet)

                elif ready == conn:
                    try:
                        frame = gw.read_frame(conn, session)
                    except (TimeoutError, OSError, gw.CryptoError) as error:
                        print(f"[-] Ошибка чтения кадра: {error}")
                        return
                    if frame is None:
                        return
                    frame_type, payload = frame
                    if frame_type == FRAME_DATA:
                        try:
                            os.write(self.tun_fd, payload)
                        except OSError as error:
                            print(f"[!] TUN write error: {error}")
                            return
                        self.stats["packets_from_phone"] += 1
                        self.stats["bytes_from_phone"] += len(payload)
                    elif frame_type == FRAME_PING:
                        try:
                            gw.send_frame(conn, session, FRAME_PONG)
                        except (OSError, gw.CryptoError):
                            return
                    elif frame_type == FRAME_DISCONNECT:
                        print("[.] Клиент прислал DISCONNECT")
                        return
                    elif frame_type not in (FRAME_KEEP_ALIVE, FRAME_PONG):
                        print(f"[?] Неизвестный кадр 0x{frame_type:02x}")

            now = time.monotonic()
            if now - last_stats >= 5.0:
                print(
                    f"[i] телефон→ПК: {self.stats['packets_from_phone']} пакетов/"
                    f"{self.stats['bytes_from_phone']} байт; "
                    f"ПК→телефон: {self.stats['packets_to_phone']} пакетов/"
                    f"{self.stats['bytes_to_phone']} байт"
                )
                last_stats = now


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GuardWire/2 server with Linux TUN + NAT",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--host", default="0.0.0.0", help="адрес прослушивания")
    parser.add_argument("--port", type=int, default=8443, help="TCP-порт")
    parser.add_argument("--token", default="demo", help="статический PSK-токен")
    parser.add_argument("--auth-token-file", default=None, help="файл активного auth-токена")
    parser.add_argument("--tun", default="guardwire0", help="имя TUN-интерфейса")
    parser.add_argument("--server-ip", default="10.8.0.1", help="IP сервера внутри туннеля")
    parser.add_argument("--client-network", default="10.8.0.0/24", help="подсеть клиентов")
    parser.add_argument("--mtu", type=int, default=1400, help="MTU TUN")
    parser.add_argument("--lan-iface", default=None, help="LAN-интерфейс (по умолчанию — default route)")
    parser.add_argument("--no-nat", action="store_true", help="не добавлять iptables/NAT")
    parser.add_argument("--dry-run", action="store_true", help="показать план и выйти")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.token:
        print("[!] --token не должен быть пустым: это PSK для GuardWire/2", file=sys.stderr)
        return 2

    server = Server(args)

    if args.dry_run:
        server.dry_run()
        return 0

    def stop(_signum: int, _frame: object) -> None:
        server.running = False

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)

    try:
        server.setup()
        server.serve_forever()
    except SetupError as error:
        print(f"\n[!] {error}", file=sys.stderr)
        return 1
    finally:
        server.cleanup()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
