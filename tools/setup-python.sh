#!/usr/bin/env bash
# Создаёт .venv и ставит зависимости для Python-серверов SelfMadeGuard.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV_DIR="$ROOT_DIR/.venv"

python3 -m venv "$VENV_DIR" || {
    echo "Не удалось создать venv. На Debian/Ubuntu установите:" >&2
    echo "  sudo apt install python3-venv" >&2
    exit 1
}
"$VENV_DIR/bin/python" -m pip install --upgrade pip
"$VENV_DIR/bin/python" -m pip install cryptography

echo
echo "Готово. Используйте интерпретатор из venv:"
echo "  $VENV_DIR/bin/python tools/selfmadeguard-demo-server.py --host 0.0.0.0 --port 8443 --token demo"
echo "  sudo $VENV_DIR/bin/python tools/selfmadeguard-linux-server.py --host 0.0.0.0 --port 8443 --token demo"
