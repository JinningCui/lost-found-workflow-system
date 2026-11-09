#!/usr/bin/env python3
"""Create local-only random credentials for Docker Compose."""

from base64 import b64encode
from pathlib import Path
from secrets import token_bytes, token_urlsafe

project = Path(__file__).resolve().parents[1]
target = project / ".env"
if target.exists():
    print(".env already exists; credentials were left unchanged")
else:
    names = (
        "MYSQL_ROOT_PASSWORD",
        "ITEM_DB_PASSWORD",
        "CLAIM_DB_PASSWORD",
        "VERIFICATION_DB_PASSWORD",
        "HANDOVER_DB_PASSWORD",
        "WORKFLOW_DB_PASSWORD",
        "LF_INTERNAL_TOKEN",
    )
    lines = [f"{name}={token_urlsafe(24)}" for name in names]
    lines.append(f"LF_CLUE_KEY={b64encode(token_bytes(32)).decode()}")
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    target.chmod(0o600)
    print("Created .env with random local credentials")
