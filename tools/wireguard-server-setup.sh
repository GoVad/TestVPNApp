#!/usr/bin/env bash
# Разовая настройка тестового WireGuard-сервера на этом Linux-ПК.
#
# Требует root и установленный wireguard-tools:
#   sudo apt install wireguard-tools
#   sudo ./tools/wireguard-server-setup.sh
#
# Скрипт создаёт /etc/wireguard/wg0.conf и клиентский конфиг в домашней
# директории пользователя (не в репозитории), чтобы приватный ключ клиента
# не попал в git.
set -euo pipefail

PORT="${WG_PORT:-51820}"
DNS="${WG_DNS:-1.1.1.1}"
CLIENT_NAME="${WG_CLIENT_NAME:-phone}"
LAN_IFACE="${WG_LAN_IFACE:-}"
WG_IFACE="wg0"
WG_SERVER_IP="10.66.0.1"
WG_CLIENT_IP="10.66.0.2"
WG_NETWORK="10.66.0.0/24"
CONFIG="/etc/wireguard/${WG_IFACE}.conf"

log() { printf '\n[+] %s\n' "$*"; }
err() { printf '\n[!] %s\n' "$*" >&2; }

if [[ ${EUID} -ne 0 ]]; then
    err "Запустите через sudo: sudo $0"
    exit 1
fi

if ! command -v wg >/dev/null || ! command -v wg-quick >/dev/null; then
    err "Не установлен wireguard-tools. Установите:"
    echo "  sudo apt install wireguard-tools"
    exit 1
fi

if [[ -e "$CONFIG" ]]; then
    err "$CONFIG уже существует. Удалите/переименуйте его вручную, чтобы не потерять настройки."
    exit 1
fi

if [[ -z "$LAN_IFACE" ]]; then
    LAN_IFACE="$(ip -4 route show default | awk '/dev/ {for (i=1;i<=NF;i++) if ($i=="dev") {print $(i+1); exit}}')"
fi
if [[ -z "$LAN_IFACE" ]]; then
    err "Не удалось определить LAN-интерфейс. Укажите: WG_LAN_IFACE=enp3s0 sudo -E $0"
    exit 1
fi

LAN_IP="$(ip -4 -o addr show dev "$LAN_IFACE" scope global | awk '{print $4}' | cut -d/ -f1 | head -1)"
if [[ -z "$LAN_IP" ]]; then
    err "Не удалось определить IPv4-адрес интерфейса $LAN_IFACE"
    exit 1
fi

if ss -lun 2>/dev/null | awk '{print $4}' | grep -q ":${PORT}$"; then
    err "UDP-порт $PORT уже занят. Выберите другой: WG_PORT=51821 sudo -E $0"
    exit 1
fi

log "Генерирую ключи WireGuard"
SERVER_PRIVATE="$(wg genkey)"
SERVER_PUBLIC="$(printf '%s' "$SERVER_PRIVATE" | wg pubkey)"
CLIENT_PRIVATE="$(wg genkey)"
CLIENT_PUBLIC="$(printf '%s' "$CLIENT_PRIVATE" | wg pubkey)"

log "Пишу $CONFIG"
umask 077
cat > "$CONFIG" <<EOF
[Interface]
Address = ${WG_SERVER_IP}/24
ListenPort = ${PORT}
PrivateKey = ${SERVER_PRIVATE}
MTU = 1420

PostUp = sysctl -w net.ipv4.ip_forward=1
PostUp = iptables -I FORWARD 1 -i %i -o ${LAN_IFACE} -j ACCEPT
PostUp = iptables -I FORWARD 1 -i ${LAN_IFACE} -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
PostUp = iptables -t nat -A POSTROUTING -s ${WG_NETWORK} -o ${LAN_IFACE} -j MASQUERADE
PostUp = iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

PostDown = iptables -D FORWARD -i %i -o ${LAN_IFACE} -j ACCEPT || true
PostDown = iptables -D FORWARD -i ${LAN_IFACE} -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT || true
PostDown = iptables -t nat -D POSTROUTING -s ${WG_NETWORK} -o ${LAN_IFACE} -j MASQUERADE || true
PostDown = iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu || true

[Peer]
# ${CLIENT_NAME}
PublicKey = ${CLIENT_PUBLIC}
AllowedIPs = ${WG_CLIENT_IP}/32
EOF
chmod 600 "$CONFIG"

log "Запускаю wg-quick@${WG_IFACE}"
systemctl enable --now "wg-quick@${WG_IFACE}"

# Клиентский конфиг кладём в домашнюю директорию пользователя, а не в repo.
SUDO_USER_NAME="${SUDO_USER:-root}"
if [[ "$SUDO_USER_NAME" == "root" ]]; then
    CLIENT_HOME="/root"
else
    CLIENT_HOME="$(getent passwd "$SUDO_USER_NAME" | cut -d: -f6)"
fi
CLIENT_DIR="${CLIENT_HOME}/guardwire-wireguard"
CLIENT_CONF="${CLIENT_DIR}/${CLIENT_NAME}.conf"

mkdir -p "$CLIENT_DIR"
cat > "$CLIENT_CONF" <<EOF
[Interface]
PrivateKey = ${CLIENT_PRIVATE}
Address = ${WG_CLIENT_IP}/32
DNS = ${DNS}
MTU = 1420

[Peer]
PublicKey = ${SERVER_PUBLIC}
Endpoint = ${LAN_IP}:${PORT}
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
chmod 700 "$CLIENT_DIR"
chmod 600 "$CLIENT_CONF"
if [[ "$SUDO_USER_NAME" != "root" ]]; then
    chown -R "${SUDO_USER_NAME}:${SUDO_USER_NAME}" "$CLIENT_DIR"
fi

log "Готово"
echo "Сервер:   ${LAN_IP}:${PORT}/udp"
echo "Клиент:   ${CLIENT_CONF}"
echo
echo "Если включён ufw, откройте порт:"
echo "  sudo ufw allow ${PORT}/udp"
echo
echo "Импортируйте клиентский конфиг в официальный WireGuard-клиент:"
echo "  - через QR: установите qrencode и выполните"
echo "    qrencode -t ansiutf8 < ${CLIENT_CONF}"
echo "  - или скопируйте файл на телефон и импортируйте из файла"
echo
echo "Проверка на сервере:"
echo "  sudo wg show"
echo "  ip -brief addr show ${WG_IFACE}"
echo
echo "После подключения с телефона проверьте:"
echo "  ping 10.66.0.1"
echo "  ping 8.8.8.8"
