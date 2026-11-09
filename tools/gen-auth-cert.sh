#!/usr/bin/env bash
set -euo pipefail

# Генерирует локальный CA и TLS-сертификат для auth-сервера.
# CA-сертификат копируется в Android-ресурсы, чтобы приложение доверяло
# только этому тестовому CA (фактически pinning).
#
# Использование:
#   ./tools/gen-auth-cert.sh [LAN_IP]
# По умолчанию: 192.168.100.4. Также в SAN добавляются 10.0.2.2, 127.0.0.1
# и localhost.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/tools/.selfmadeguard-certs"
IP="${1:-192.168.100.4}"

mkdir -p "$OUT" "$ROOT/core/data/src/main/res/raw"

if [[ ! -f "$OUT/ca-key.pem" || ! -f "$OUT/ca-cert.pem" ]]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
        -keyout "$OUT/ca-key.pem" \
        -out "$OUT/ca-cert.pem" \
        -subj "/CN=SelfMadeGuard Local CA"
fi

openssl req -newkey rsa:2048 -nodes \
    -keyout "$OUT/auth-key.pem" \
    -out "$OUT/auth.csr" \
    -subj "/CN=SelfMadeGuard Auth"

cat > "$OUT/auth.ext" <<EOF
subjectAltName=IP:$IP,IP:10.0.2.2,IP:127.0.0.1,DNS:localhost
extendedKeyUsage=serverAuth
EOF

openssl x509 -req \
    -in "$OUT/auth.csr" \
    -CA "$OUT/ca-cert.pem" \
    -CAkey "$OUT/ca-key.pem" \
    -CAcreateserial \
    -days 825 \
    -out "$OUT/auth-cert.pem" \
    -extfile "$OUT/auth.ext"

cp "$OUT/ca-cert.pem" "$ROOT/core/data/src/main/res/raw/auth_ca.pem"
chmod 600 "$OUT"/*.pem

echo "CA:     $OUT/ca-cert.pem"
echo "Server: $OUT/auth-cert.pem"
echo "Key:    $OUT/auth-key.pem"
echo "Android trust anchor: core/data/src/main/res/raw/auth_ca.pem"
echo
echo "Запуск auth-сервера:"
echo "  sudo python3 tools/selfmadeguard-auth-server.py \\"
echo "      --host 0.0.0.0 --port 8090 --user demo:demo \\"
echo "      --wg-interface wg0 --wg-endpoint $IP:51820"
