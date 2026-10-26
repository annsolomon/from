"""Events in the platform envelope (MASTER_PLAN 6.1, dexter-events EnvelopeCodec).

In: ``portfolio.statement.submitted`` v1 on ``dexter.portfolio.statement.v1``.
Out: ``ai-parser.statement.parsed`` v1 on ``dexter.ai-parser.statement.v1`` (key = account id).

The output event id is derived from the input event id (UUIDv5), so a redelivered input produces the same output
event and portfolio's inbox drops the duplicate.
"""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

IN_TYPE = "portfolio.statement.submitted"
IN_TOPIC = "dexter.portfolio.statement.v1"
OUT_TYPE = "ai-parser.statement.parsed"
OUT_TOPIC = "dexter.ai-parser.statement.v1"
SOURCE = "ai-parser"
_NS = uuid.UUID("8f5d4b0e-2c1a-5e3b-9d47-6a0b1c2d3e4f")


class Poison(ValueError):
    """Not a usable envelope (bad JSON, missing or mistyped fields): skipped, never retried."""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def decode(value: bytes) -> dict:
    try:
        env = json.loads(value)
    except (ValueError, TypeError) as e:
        raise Poison("not JSON") from e
    if not isinstance(env, dict) or not isinstance(env.get("data"), dict):
        raise Poison("envelope without a data object")
    for f in ("id", "type", "source", "time", "aggregateId"):
        if not isinstance(env.get(f), str):
            raise Poison(f"envelope field {f} missing")
    try:
        uuid.UUID(env["id"])
    except ValueError as e:
        raise Poison("envelope id is not a UUID") from e
    return env


def submitted(env: dict) -> dict:
    """The submitted event's data, checked."""
    d = env["data"]
    need = {"uploadId": str, "userCardId": str, "fileUrl": str, "fileSha256": str, "sizeBytes": int}
    for k, t in need.items():
        if not isinstance(d.get(k), t):
            raise Poison(f"submitted event without {k}")
    if not isinstance(env.get("accountId"), str):
        raise Poison("submitted event without accountId")
    return d


def parsed(env: dict, result: dict) -> dict:
    d = env["data"]
    data = {"uploadId": d["uploadId"], "userCardId": d["userCardId"], **result, "parsedAt": now_iso()}
    return {"id": str(uuid.uuid5(_NS, env["id"])), "type": OUT_TYPE, "version": 1, "source": SOURCE,
            "time": data["parsedAt"], "accountId": env["accountId"], "aggregateId": d["uploadId"],
            "aggregateVersion": 1, "traceparent": env.get("traceparent"), "data": data}


def encode(env: dict) -> bytes:
    return json.dumps(env, separators=(",", ":"), ensure_ascii=False).encode()
