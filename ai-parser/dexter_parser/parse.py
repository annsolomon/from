"""The parse pipeline: PDF -> text lines -> PAN redaction -> layout detection -> extraction -> validation.

Redaction runs on the raw lines before any extractor sees them, so nothing downstream (the result, the event,
logs) can carry a full card number. The result is the ``data`` shape of ``ai-parser.statement.parsed`` v1 minus the
fields the worker adds (upload and card ids, parsedAt).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from . import PARSER_VERSION
from .formats import Statement, detect
from .pdftext import PdfError, extract_lines
from .redact import redact_all

MAX_LINES = 2000


@dataclass
class Failure(Exception):
    code: str
    detail: str


def _iso(d: date | None) -> str | None:
    return d.isoformat() if d else None


def failed(code: str, detail: str, redactions: int = 0) -> dict:
    return {"status": "failed", "errorCode": code, "errorDetail": detail[:200], "parserVersion": PARSER_VERSION,
            "format": None, "issuer": None, "last4": None, "periodStart": None, "periodEnd": None, "dueDate": None,
            "totalDuePaise": None, "minDuePaise": None, "redactions": redactions, "lines": []}


def parse_pdf(data: bytes) -> dict:
    try:
        raw = extract_lines(data)
    except PdfError as e:
        return failed(e.code, str(e))
    lines, redactions = redact_all(raw)
    if not lines:
        return failed("NO_TEXT", "the PDF has no text layer (scanned image); OCR is not available yet")
    extractor = detect(lines)
    if extractor is None:
        return failed("UNKNOWN_FORMAT", "not a statement layout this parser knows", redactions)
    try:
        st = extractor.parse(lines)
        validate(st)
    except Failure as e:
        return failed(e.code, e.detail, redactions)
    except ValueError as e:
        return failed("UNREADABLE_VALUES", str(e), redactions)
    return result(st, redactions)


def validate(st: Statement) -> None:
    if not st.lines:
        raise Failure("NO_TRANSACTIONS", "no transaction rows found")
    if len(st.lines) > MAX_LINES:
        raise Failure("TOO_MANY_LINES", f"at most {MAX_LINES} transactions per statement")
    if st.period_start and st.period_end and st.period_end < st.period_start:
        raise Failure("BAD_PERIOD", "the statement period ends before it starts")
    if st.due_date and st.period_end and st.due_date <= st.period_end:
        raise Failure("BAD_PERIOD", "the due date is not after the period end")
    for line in st.lines:
        if not 2000 <= line.posted_on.year <= 2100:
            raise Failure("UNREADABLE_VALUES", f"line {line.line_no}: implausible date")


def result(st: Statement, redactions: int) -> dict:
    return {
        "status": "parsed", "errorCode": None, "errorDetail": None, "parserVersion": PARSER_VERSION,
        "format": st.format, "issuer": st.issuer, "last4": st.last4,
        "periodStart": _iso(st.period_start), "periodEnd": _iso(st.period_end), "dueDate": _iso(st.due_date),
        "totalDuePaise": st.total_due_paise, "minDuePaise": st.min_due_paise, "redactions": redactions,
        "lines": [{"lineNo": x.line_no, "postedOn": x.posted_on.isoformat(), "description": x.description,
                   "amountPaise": x.amount_paise, "direction": x.direction, "kind": x.kind} for x in st.lines],
    }
