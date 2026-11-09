#!/usr/bin/env bash
set -euo pipefail

# One-command demo:
#   ./tools/demo-up.sh [LAN_IP]
#
# Поднимает HTTPS auth-сервер и SelfMadeGuard demo-сервер без root.
# Demo-сервер проверяет handshake/кадры, но не маршрутизирует интернет.
# Для реального NAT используйте selfmadeguard-linux-server.py (см. README).

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PYTHON="${PYTHON:-python3}"
if [[ -x "$ROOT/.venv/bin/python" ]]; then
    PYTHON="$ROOT/.venv/bin/python"
fi

if ! "$PYTHON" -c "import cryptography" >/dev/null 2>&1; then
    echo "Не найден Python-пакет cryptography."
    echo "Запустите ./tools/setup-python.sh и повторите."
    exit 1
fi

if [[ $# -gt 0 ]]; then
    LAN_IP="$1"
else
    LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
    LAN_IP="${LAN_IP:-127.0.0.1}"
fi

AUTH_PORT="${AUTH_PORT:-8090}"
DEMO_PORT="${DEMO_PORT:-8444}"
TOKEN_FILE="$ROOT/tools/.selfmadeguard-demo-token"
SESSION_FILE="$ROOT/tools/.selfmadeguard-demo-sessions.json"
CERT_DIR="$ROOT/tools/.selfmadeguard-certs"

if [[ -e "$TOKEN_FILE" && ! -w "$TOKEN_FILE" ]]; then
    echo "Нет прав на запись $TOKEN_FILE."
    echo "Остановите старый root-сервер и удалите файл:"
    echo "  sudo fuser -k $AUTH_PORT/tcp"
    echo "  sudo rm -f $TOKEN_FILE"
    exit 1
fi

if [[ ! -f "$CERT_DIR/auth-cert.pem" || ! -f "$CERT_DIR/auth-key.pem" ]]; then
    echo "Генерирую локальный TLS-сертификат для $LAN_IP…"
    "$ROOT/tools/gen-auth-cert.sh" "$LAN_IP" >/dev/null
fi

if command -v ss >/dev/null 2>&1; then
    if ss -lnt 2>/dev/null | grep -q ":$AUTH_PORT "; then
        echo "Порт $AUTH_PORT уже занят. Остановите старый auth-сервер:"
        echo "  sudo fuser -k $AUTH_PORT/tcp"
        exit 1
    fi
    if ss -lnt 2>/dev/null | grep -q ":$DEMO_PORT "; then
        echo "Порт $DEMO_PORT уже занят. Укажите другой: DEMO_PORT=8444 ./tools/demo-up.sh"
        exit 1
    fi
fi

AUTH_LOG="$ROOT/tools/.selfmadeguard-auth.log"
DEMO_LOG="$ROOT/tools/.selfmadeguard-demo.log"
: > "$AUTH_LOG"
: > "$DEMO_LOG"

AUTH_PID=""
DEMO_PID=""

cleanup() {
    [[ -n "$DEMO_PID" ]] && kill "$DEMO_PID" 2>/dev/null || true
    [[ -n "$AUTH_PID" ]] && kill "$AUTH_PID" 2>/dev/null || true
    wait "$DEMO_PID" "$AUTH_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

"$PYTHON" tools/selfmadeguard-auth-server.py \
    --host 0.0.0.0 --port "$AUTH_PORT" \
    --user demo:demo \
    --disable-wireguard \
    --access-ttl 3600 \
    --refresh-ttl 2592000 \
    --token-file "$TOKEN_FILE" \
    --session-file "$SESSION_FILE" \
    >"$AUTH_LOG" 2>&1 &
AUTH_PID=$!

"$PYTHON" tools/selfmadeguard-demo-server.py \
    --host 0.0.0.0 --port "$DEMO_PORT" \
    --auth-token-file "$TOKEN_FILE" \
    >"$DEMO_LOG" 2>&1 &
DEMO_PID=$!

for _ in $(seq 1 40); do
    if curl -fsS --cacert "$ROOT/core/data/src/main/res/raw/auth_ca.pem" \
        "https://127.0.0.1:$AUTH_PORT/auth/health" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "$AUTH_PID" 2>/dev/null; then
        echo "Auth-сервер не запустился:"
        cat "$AUTH_LOG"
        exit 1
    fi
    sleep 0.5
done

echo
echo "SelfMadeGuard demo запущен."
echo "  Auth (HTTPS):  https://$LAN_IP:$AUTH_PORT"
echo "  SelfMadeGuard:     $LAN_IP:$DEMO_PORT"
echo "  Логин:         demo / demo"
echo
echo "В приложении укажите auth-сервер https://$LAN_IP:$AUTH_PORT,"
echo "а в SelfMadeGuard-режиме — host $LAN_IP и порт $DEMO_PORT."
echo "Demo-сервер не маршрутизирует интернет: проверяются handshake, кадры и UI."
echo
echo "Логи: tools/.selfmadeguard-auth.log, tools/.selfmadeguard-demo.log"
echo "Остановка: Ctrl+C"
echo

wait -n "$AUTH_PID" "$DEMO_PID"
