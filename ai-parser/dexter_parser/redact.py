"""PAN redaction (MASTER_PLAN 7.4): runs on every extracted line before anything is parsed, stored or published.

A card number is 13-19 digits, optionally grouped by single spaces or dashes, that passes the Luhn check. It is
replaced by ``XXXX XXXX XXXX <last4>``. Numbers that fail Luhn (references, phone numbers, amounts) are left alone;
a reference that happens to pass Luhn is masked too, which is the safe direction.
"""
from __future__ import annotations

import re

# 13-19 digits, groups separated by one space or dash, not part of a longer digit run (letters may touch it:
# "REF4111111111111111" is still masked)
_CANDIDATE = re.compile(r"(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)")


def luhn_ok(digits: str) -> bool:
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = ord(ch) - 48
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def _windows(groups: list[str]) -> list[tuple[int, int]]:
    """Non-overlapping runs of consecutive digit groups (13-19 digits) that pass Luhn: longest first, leftmost."""
    found, i = [], 0
    while i < len(groups):
        best = None
        total = 0
        for j in range(i, len(groups)):
            total += len(groups[j])
            if total > 19:
                break
            if total >= 13 and luhn_ok("".join(groups[i:j + 1])):
                best = j
        if best is None:
            i += 1
        else:
            found.append((i, best))
            i = best + 1
    return found


def redact(line: str) -> tuple[str, int]:
    """The line with every Luhn-valid card number masked, and how many were masked.

    A candidate run like ``4111 1111 1111 1111 2026`` is split into its digit groups and every window of
    consecutive groups is checked, so a card number followed (or preceded) by another short number is still found.
    """
    count = 0

    def mask(m: re.Match) -> str:
        nonlocal count
        parts = re.split(r"([ -])", m.group(0))   # groups at even indexes, separators at odd ones
        groups = parts[0::2]
        seps = parts[1::2]
        windows = _windows(groups)
        if not windows:
            return m.group(0)
        out, g = [], 0
        for start, end in windows:
            while g < start:
                out.append(groups[g] + (seps[g] if g < len(seps) else ""))
                g += 1
            digits = "".join(groups[start:end + 1])
            out.append("XXXX XXXX XXXX " + digits[-4:] + (seps[end] if end < len(seps) else ""))
            g = end + 1
            count += 1
        while g < len(groups):
            out.append(groups[g] + (seps[g] if g < len(seps) else ""))
            g += 1
        return "".join(out)

    return _CANDIDATE.sub(mask, line), count


def redact_all(lines: list[str]) -> tuple[list[str], int]:
    out, total = [], 0
    for line in lines:
        r, n = redact(line)
        out.append(r)
        total += n
    return out, total
