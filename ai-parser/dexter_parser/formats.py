"""Statement layouts (MASTER_PLAN P12): three fictional MOCK_ issuers, each a deterministic rule-based extractor.

Every extractor reads the redacted text lines of one statement and returns a :class:`Statement`. A real OCR or
LLM-assisted extractor plugs in behind the same :class:`Extractor` interface (``detect`` + ``parse``) and the rest
of the pipeline (redaction first, validation, the event) stays the same.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Protocol

MAX_DESCRIPTION = 200


@dataclass(frozen=True)
class Line:
    line_no: int
    posted_on: date
    description: str
    amount_paise: int
    direction: str  # debit | credit
    kind: str       # purchase | refund | payment | fee | interest | cashback | emi


@dataclass
class Statement:
    format: str
    issuer: str
    last4: str | None = None
    period_start: date | None = None
    period_end: date | None = None
    due_date: date | None = None
    total_due_paise: int | None = None
    min_due_paise: int | None = None
    lines: list[Line] = field(default_factory=list)


class Extractor(Protocol):
    code: str
    issuer: str

    def detect(self, lines: list[str]) -> bool: ...

    def parse(self, lines: list[str]) -> Statement: ...


# ------------------------------------------------------------------------------------------------ helpers

_AMOUNT = re.compile(r"^\d{1,3}(?:,\d{2,3})*(?:\.\d{2})?$|^\d+(?:\.\d{2})?$")


def paise(text: str) -> int:
    """'1,23,456.78' (Indian or western grouping) -> 12345678. Exact (Decimal); ValueError when not an amount."""
    s = text.strip()
    if not _AMOUNT.match(s):
        raise ValueError(f"not an amount: {text!r}")
    try:
        return int((Decimal(s.replace(",", "")) * 100).to_integral_exact())
    except InvalidOperation as e:
        raise ValueError(f"not an amount: {text!r}") from e


_CREDIT_KINDS = [
    (re.compile(r"\b(PAYMENT|THANK YOU|AUTOPAY|BBPS)\b"), "payment"),
    (re.compile(r"\bCASH ?BACK\b"), "cashback"),
]
_DEBIT_KINDS = [
    (re.compile(r"\b(FINANCE CHARGES?|INTEREST)\b"), "interest"),
    (re.compile(r"\bEMI\b"), "emi"),
    (re.compile(r"\b(FEES?|GST|IGST|CGST|SGST|SURCHARGE|LATE PAYMENT|OVERLIMIT)\b"), "fee"),
]


def kind_of(description: str, direction: str) -> str:
    d = description.upper()
    for pattern, kind in _CREDIT_KINDS if direction == "credit" else _DEBIT_KINDS:
        if pattern.search(d):
            return kind
    return "refund" if direction == "credit" else "purchase"


def clean(description: str) -> str:
    return " ".join(description.split())[:MAX_DESCRIPTION].strip()


def _first(lines: list[str], pattern: re.Pattern) -> re.Match | None:
    for line in lines:
        m = pattern.search(line)
        if m:
            return m
    return None


def _amount_of(lines: list[str], pattern: re.Pattern) -> int | None:
    m = _first(lines, pattern)
    return paise(m.group(1)) if m else None


class _Base:
    code = ""
    issuer = ""
    marker = ""
    date_format = ""
    card: re.Pattern
    period: re.Pattern
    due: re.Pattern
    total: re.Pattern
    minimum: re.Pattern
    row: re.Pattern

    def detect(self, lines: list[str]) -> bool:
        return any(self.marker in line for line in lines[:15])

    def day(self, text: str) -> date:
        return datetime.strptime(text, self.date_format).date()

    def row_parts(self, m: re.Match) -> tuple[str, str, str, bool]:
        """(date, description, amount, credit?) of a matched transaction row."""
        raise NotImplementedError

    def parse(self, lines: list[str]) -> Statement:
        st = Statement(format=self.code, issuer=self.issuer)
        m = _first(lines, self.card)
        st.last4 = m.group(1) if m else None
        m = _first(lines, self.period)
        if m:
            st.period_start, st.period_end = self.day(m.group(1)), self.day(m.group(2))
        m = _first(lines, self.due)
        st.due_date = self.day(m.group(1)) if m else None
        st.total_due_paise = _amount_of(lines, self.total)
        st.min_due_paise = _amount_of(lines, self.minimum)
        n = 0
        for line in lines:
            m = self.row.match(line)
            if not m:
                continue
            day, description, amount, credit = self.row_parts(m)
            try:
                value, posted = paise(amount), self.day(day)
            except ValueError:
                continue
            description = clean(description)
            if value <= 0 or not description:
                continue
            n += 1
            direction = "credit" if credit else "debit"
            st.lines.append(Line(n, posted, description, value, direction, kind_of(description, direction)))
        return st


# ------------------------------------------------------------------------------------------------ the three issuers

class Saffron(_Base):
    """Saffron Bank (MOCK): dd/mm/yyyy, amounts in a right column, credits suffixed 'Cr'."""
    code = "saffron-v1"
    issuer = "MOCK_SAFFRON"
    marker = "Saffron Bank (MOCK)"
    date_format = "%d/%m/%Y"
    card = re.compile(r"Card Number:\s*(?:X{4}[\s-]?){3}(\d{4})")
    period = re.compile(r"Statement Period:\s*(\d{2}/\d{2}/\d{4}) to (\d{2}/\d{2}/\d{4})")
    due = re.compile(r"Payment Due Date:\s*(\d{2}/\d{2}/\d{4})")
    total = re.compile(r"Total Amount Due:\s*Rs\.?\s*([\d,]+(?:\.\d{2})?)")
    minimum = re.compile(r"Minimum Amount Due:\s*Rs\.?\s*([\d,]+(?:\.\d{2})?)")
    row = re.compile(r"^(\d{2}/\d{2}/\d{4}) (.+?) ([\d,]+\.\d{2})( Cr)?$")

    def row_parts(self, m):
        return m.group(1), m.group(2), m.group(3), m.group(4) is not None


class Indus(_Base):
    """Indus Valley Bank (MOCK): pipe-separated rows, dd-Mon-yyyy, a DR/CR column."""
    code = "indus-v1"
    issuer = "MOCK_INDUS"
    marker = "INDUS VALLEY BANK (MOCK)"
    date_format = "%d-%b-%Y"
    card = re.compile(r"card ending (\d{4})")
    period = re.compile(r"Billing cycle: (\d{2}-[A-Za-z]{3}-\d{4}) - (\d{2}-[A-Za-z]{3}-\d{4})")
    due = re.compile(r"Due date: (\d{2}-[A-Za-z]{3}-\d{4})")
    total = re.compile(r"Total due: INR ([\d,]+(?:\.\d{2})?)")
    minimum = re.compile(r"Min due: INR ([\d,]+(?:\.\d{2})?)")
    row = re.compile(r"^(\d{2}-[A-Za-z]{3}-\d{4}) \| (.+?) \| (DR|CR) \| ([\d,]+\.\d{2})$")

    def row_parts(self, m):
        return m.group(1), m.group(2), m.group(4), m.group(3) == "CR"


class Deccan(_Base):
    """Deccan Commercial Bank (MOCK): 'dd Mon yy', amounts followed by D (debit) or C (credit)."""
    code = "deccan-v1"
    issuer = "MOCK_DECCAN"
    marker = "Deccan Commercial Bank (MOCK)"
    date_format = "%d %b %y"
    card = re.compile(r"Primary card: [\dX]{4}-[\dX]{4}-[\dX]{4}-(\d{4})")
    period = re.compile(r"From (\d{2} [A-Za-z]{3} \d{2}) To (\d{2} [A-Za-z]{3} \d{2})")
    due = re.compile(r"Pay by (\d{2} [A-Za-z]{3} \d{2})")
    total = re.compile(r"Outstanding INR ([\d,]+(?:\.\d{2})?)")
    minimum = re.compile(r"Minimum INR ([\d,]+(?:\.\d{2})?)")
    row = re.compile(r"^(\d{2} [A-Za-z]{3} \d{2}) (.+?) ([\d,]+\.\d{2}) ([DC])$")

    def row_parts(self, m):
        return m.group(1), m.group(2), m.group(3), m.group(4) == "C"


EXTRACTORS: list[Extractor] = [Saffron(), Indus(), Deccan()]


def detect(lines: list[str]) -> Extractor | None:
    for e in EXTRACTORS:
        if e.detect(lines):
            return e
    return None
