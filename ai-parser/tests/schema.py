"""The JSON Schema subset of contracts/events (same keywords as contracts' MiniSchema.java; unknown ones fail)."""
from __future__ import annotations

import json
import os
import re
import uuid
from datetime import datetime

KNOWN = {"$schema", "$id", "title", "description", "type", "required", "properties", "additionalProperties",
         "format", "pattern", "minimum", "maxLength", "minLength", "items", "enum"}
EVENTS = os.path.join(os.path.dirname(__file__), "..", "..", "contracts", "events")


def load(name: str) -> dict:
    with open(os.path.join(EVENTS, name)) as f:
        return json.load(f)


def _type(v) -> str:
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, int):
        return "integer"
    if isinstance(v, float):
        return "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    return "object"


def validate(schema: dict, value, path: str = "$") -> list[str]:
    errors: list[str] = []
    unknown = set(schema) - KNOWN
    if unknown:
        raise ValueError(f"unsupported schema keywords {unknown} at {path}")
    t = schema.get("type")
    if t is not None:
        allowed = set(t) if isinstance(t, list) else {t}
        actual = _type(value)
        if actual not in allowed and not (actual == "integer" and "number" in allowed):
            return [f"{path}: expected {sorted(allowed)} but was {actual}"]
    if value is None:
        return errors
    if "enum" in schema and value not in schema["enum"]:
        errors.append(f"{path}: not in enum")
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{path}: shorter than minLength")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append(f"{path}: longer than maxLength")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(f"{path}: does not match {schema['pattern']}")
        fmt = schema.get("format")
        try:
            if fmt == "uuid":
                uuid.UUID(value)
            elif fmt == "date-time":
                if not value.endswith("Z"):
                    raise ValueError("not UTC")
                datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            errors.append(f"{path}: not a {fmt}")
    if isinstance(value, int) and not isinstance(value, bool) and "minimum" in schema and value < schema["minimum"]:
        errors.append(f"{path}: below minimum")
    if isinstance(value, dict):
        props = schema.get("properties", {})
        for r in schema.get("required", []):
            if r not in value:
                errors.append(f"{path}: missing {r}")
        if schema.get("additionalProperties") is False:
            for k in value:
                if k not in props:
                    errors.append(f"{path}: unexpected {k}")
        for k, sub in props.items():
            if k in value:
                errors.extend(validate(sub, value[k], f"{path}.{k}"))
    if isinstance(value, list) and "items" in schema:
        for i, item in enumerate(value):
            errors.extend(validate(schema["items"], item, f"{path}[{i}]"))
    return errors
