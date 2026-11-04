"""Общие функции auth/control-plane GuardWire."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import tempfile
import time
from pathlib import Path


class AuthError(RuntimeError):
    pass


def hash_password(password: str, salt: bytes | None = None) -> dict[str, str]:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(
        password.encode("utf-8"),
        salt=salt,
        n=2**14,
        r=8,
        p=1,
        dklen=32,
    )
    return {
        "salt": base64.b64encode(salt).decode("ascii"),
        "hash": base64.b64encode(digest).decode("ascii"),
    }


def verify_password(password: str, stored: dict[str, str]) -> bool:
    try:
        salt = base64.b64decode(stored["salt"])
        expected = base64.b64decode(stored["hash"])
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=salt,
            n=2**14,
            r=8,
            p=1,
            dklen=32,
        )
        return secrets.compare_digest(actual, expected)
    except Exception:
        return False


def new_token() -> str:
    return secrets.token_urlsafe(32)


def write_token_file(path: str | Path, token: str, username: str, expires_at: float) -> None:
    file_path = Path(path)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "token": token,
        "username": username,
        "expires_at": expires_at,
    }
    fd, temp_name = tempfile.mkstemp(prefix=".guardwire-token.", dir=str(file_path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as file:
            json.dump(payload, file)
        os.chmod(temp_name, 0o600)
        os.replace(temp_name, file_path)
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def load_token_record(path: str | Path) -> dict | None:
    try:
        with open(path, encoding="utf-8") as file:
            payload = json.load(file)
        if float(payload.get("expires_at", 0)) <= time.time():
            return None
        if not payload.get("token"):
            return None
        return payload
    except (OSError, ValueError, TypeError):
        return None


def load_active_token(path: str | Path) -> str | None:
    record = load_token_record(path)
    return record["token"] if record else None
