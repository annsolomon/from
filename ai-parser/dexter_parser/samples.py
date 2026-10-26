"""Deterministic mock statements in the three layouts (tests, API smoke, trail fixtures).

``statement(fmt, last4, period_end, n, seed)`` returns the PDF bytes and what a correct parse must produce. The
payment row carries a full (Luhn-valid) test card number, so every sample also exercises PAN redaction.

CLI: python -m dexter_parser.samples OUTDIR [--last4 4321] [--end 2026-10-05] [--lines 12] [--seed 7]
"""
from __future__ import annotations

import argparse
import json
import os
import random
from datetime import date, timedelta

from .formats import kind_of
from .pdftext import write_pdf

# A Luhn-valid test PAN (Visa test number). It must never survive into a parse result.
TEST_PAN = "4111 1111 1111 1111"

MERCHANTS = ["SWIGGY*BANGALORE", "AMAZON PAY INDIA", "UBER INDIA SYSTEMS", "BIGBASKET.COM", "IRCTC E-TICKETING",
             "ZOMATO ONLINE ORDER", "BESCOM ELECTRICITY BILL", "JIO PREPAID RECHARGE", "FLIPKART INTERNET",
             "HP PETROL PUMP", "DECATHLON SPORTS", "CROMA RETAIL", "PVR CINEMAS", "APOLLO PHARMACY"]
FORMATS = ("saffron", "indus", "deccan")


def _rupees(paise: int) -> str:
    return f"{paise // 100:,}.{paise % 100:02d}"


def rows_for(last4: str, period_end: date, n: int, seed: int) -> tuple[date, list[dict]]:
    """The statement's transactions: n purchases, a refund, a fee + GST, a cashback and the payment (with a PAN)."""
    rng = random.Random(seed * 7919 + int(last4))
    start = period_end - timedelta(days=29)
    out = []
    for i in range(n):
        day = start + timedelta(days=rng.randrange(30))
        out.append({"postedOn": day, "description": MERCHANTS[rng.randrange(len(MERCHANTS))],
                    "amountPaise": rng.randrange(5_000, 900_000), "direction": "debit"})
    first = out[0]
    out.append({"postedOn": min(first["postedOn"] + timedelta(days=2), period_end),
                "description": "REFUND " + first["description"], "amountPaise": max(first["amountPaise"] // 4, 100),
                "direction": "credit"})
    out.append({"postedOn": start + timedelta(days=3), "description": "ANNUAL FEE", "amountPaise": 50_000,
                "direction": "debit"})
    out.append({"postedOn": start + timedelta(days=3), "description": "GST ON ANNUAL FEE", "amountPaise": 9_000,
                "direction": "debit"})
    out.append({"postedOn": start + timedelta(days=10), "description": "CASHBACK CREDIT", "amountPaise": 12_345,
                "direction": "credit"})
    out.append({"postedOn": start + timedelta(days=5), "description": f"PAYMENT RECEIVED {TEST_PAN} THANK YOU",
                "amountPaise": rng.randrange(100_000, 2_000_000), "direction": "credit"})
    out.sort(key=lambda r: (r["postedOn"], r["description"]))
    return start, out


def statement(fmt: str, last4: str, period_end: date, n: int = 12, seed: int = 7) -> tuple[bytes, dict]:
    start, rows = rows_for(last4, period_end, n, seed)
    due = period_end + timedelta(days=20)
    debits = sum(r["amountPaise"] for r in rows if r["direction"] == "debit")
    credits = sum(r["amountPaise"] for r in rows if r["direction"] == "credit")
    total = max(debits - credits, 0)
    minimum = min(total, max(10_000, (total * 5 + 99) // 100))
    pdf_rows = {"saffron": _saffron, "indus": _indus, "deccan": _deccan}[fmt](last4, start, period_end, due, total,
                                                                               minimum, rows)
    expected = {
        "issuer": {"saffron": "MOCK_SAFFRON", "indus": "MOCK_INDUS", "deccan": "MOCK_DECCAN"}[fmt],
        "last4": last4, "periodStart": start.isoformat(), "periodEnd": period_end.isoformat(),
        "dueDate": due.isoformat(), "totalDuePaise": total, "minDuePaise": minimum,
        "lines": [{"lineNo": i, "postedOn": r["postedOn"].isoformat(),
                   "description": r["description"].replace(TEST_PAN, "XXXX XXXX XXXX 1111"),
                   "amountPaise": r["amountPaise"], "direction": r["direction"],
                   "kind": kind_of(r["description"], r["direction"])} for i, r in enumerate(rows, start=1)],
    }
    return write_pdf(pdf_rows), expected


def _saffron(last4, start, end, due, total, minimum, rows):
    d = "%d/%m/%Y"
    out = [[(40, "Saffron Bank (MOCK) - Credit Card Statement")],
           [(40, f"Card Number: XXXX XXXX XXXX {last4}")],
           [(40, f"Statement Period: {start.strftime(d)} to {end.strftime(d)}")],
           [(40, f"Payment Due Date: {due.strftime(d)}")],
           [(40, f"Total Amount Due: Rs. {_rupees(total)}"), (300, f"Minimum Amount Due: Rs. {_rupees(minimum)}")],
           [(40, "Date"), (120, "Transaction Details"), (440, "Amount (Rs.)")]]
    for r in rows:
        amount = _rupees(r["amountPaise"]) + (" Cr" if r["direction"] == "credit" else "")
        out.append([(40, r["postedOn"].strftime(d)), (120, r["description"]), (440, amount)])
    out.append([(40, "This is a computer generated statement of a fictional bank.")])
    return out


def _indus(last4, start, end, due, total, minimum, rows):
    d = "%d-%b-%Y"
    out = [[(40, "INDUS VALLEY BANK (MOCK)")],
           [(40, f"Statement for card ending {last4}")],
           [(40, f"Billing cycle: {start.strftime(d)} - {end.strftime(d)}")],
           [(40, f"Due date: {due.strftime(d)}")],
           [(40, f"Total due: INR {_rupees(total)}"), (260, f"Min due: INR {_rupees(minimum)}")],
           [(40, "TXN DATE | DESCRIPTION | DR/CR | AMOUNT")]]
    for r in rows:
        mark = "CR" if r["direction"] == "credit" else "DR"
        out.append([(40, f"{r['postedOn'].strftime(d)} | {r['description']} | {mark} | {_rupees(r['amountPaise'])}")])
    return out


def _deccan(last4, start, end, due, total, minimum, rows):
    d = "%d %b %y"
    out = [[(40, "Deccan Commercial Bank (MOCK) Card Statement")],
           [(40, f"Primary card: 5123-45XX-XXXX-{last4}")],
           [(40, f"From {start.strftime(d)} To {end.strftime(d)}"), (300, f"Pay by {due.strftime(d)}")],
           [(40, f"Outstanding INR {_rupees(total)}"), (260, f"Minimum INR {_rupees(minimum)}")]]
    for r in rows:
        mark = "C" if r["direction"] == "credit" else "D"
        out.append([(40, r["postedOn"].strftime(d)), (110, r["description"]),
                    (440, _rupees(r["amountPaise"])), (520, mark)])
    return out


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("outdir")
    ap.add_argument("--last4", default="4321")
    ap.add_argument("--end", default=(date.today() - timedelta(days=1)).isoformat())
    ap.add_argument("--lines", type=int, default=12)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args(argv)
    os.makedirs(a.outdir, exist_ok=True)
    for fmt in FORMATS:
        pdf, expected = statement(fmt, a.last4, date.fromisoformat(a.end), a.lines, a.seed)
        with open(os.path.join(a.outdir, f"{fmt}.pdf"), "wb") as f:
            f.write(pdf)
        with open(os.path.join(a.outdir, f"{fmt}.expected.json"), "w") as f:
            json.dump(expected, f, indent=1)
    print(f"wrote {', '.join(f + '.pdf' for f in FORMATS)} to {a.outdir}")


if __name__ == "__main__":
    main()
