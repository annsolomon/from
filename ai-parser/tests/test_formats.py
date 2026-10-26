import json
from datetime import date

import pytest

from dexter_parser.formats import EXTRACTORS, Statement, kind_of, paise
from dexter_parser.parse import MAX_LINES, Failure, parse_pdf, validate
from dexter_parser.pdftext import write_pdf
from dexter_parser.samples import FORMATS, TEST_PAN, statement


@pytest.mark.parametrize("fmt", FORMATS)
@pytest.mark.parametrize("end, last4, n, seed", [(date(2026, 10, 5), "4321", 12, 7), (date(2025, 12, 31), "0042", 40, 3),
                                                 (date(2026, 2, 28), "9999", 1, 11)])
def test_every_layout_parses_exactly(fmt, end, last4, n, seed):
    pdf, expected = statement(fmt, last4, end, n, seed)
    r = parse_pdf(pdf)
    assert r["status"] == "parsed", r
    for k, v in expected.items():
        assert r[k] == v, k
    assert r["format"] == fmt + "-v1"
    assert r["redactions"] == 1
    assert len(r["lines"]) == n + 5


@pytest.mark.parametrize("fmt", FORMATS)
def test_no_full_card_number_survives(fmt):
    pdf, _ = statement(fmt, "4321", date(2026, 10, 5))
    text = json.dumps(parse_pdf(pdf))
    assert TEST_PAN not in text and TEST_PAN.replace(" ", "") not in text


def test_multi_page_statement():
    pdf, expected = statement("saffron", "1234", date(2026, 9, 30), 150, 5)
    r = parse_pdf(pdf)
    assert r["status"] == "parsed" and len(r["lines"]) == 155 == len(expected["lines"])
    assert [x["lineNo"] for x in r["lines"]] == list(range(1, 156))


@pytest.mark.parametrize("text, expected", [("1,234.50", 123450), ("1,23,456.78", 12345678), ("0.05", 5),
                                             ("1000", 100000), ("1,000", 100000), ("12,34,56,789.00", 12345678900)])
def test_amounts_are_exact_paise(text, expected):
    assert paise(text) == expected


@pytest.mark.parametrize("text", ["12.5", "1,2,3.00", "abc", "", "1.234", "-5.00"])
def test_bad_amounts(text):
    with pytest.raises(ValueError):
        paise(text)


@pytest.mark.parametrize("desc, direction, kind", [
    ("SWIGGY*BANGALORE", "debit", "purchase"), ("CAFE COFFEE DAY", "debit", "purchase"),
    ("ANNUAL FEE", "debit", "fee"), ("GST ON ANNUAL FEE", "debit", "fee"), ("FUEL SURCHARGE", "debit", "fee"),
    ("LATE PAYMENT FEE", "debit", "fee"), ("FINANCE CHARGES", "debit", "interest"), ("INTEREST ON RETAIL", "debit", "interest"),
    ("EMI 3/12 IPHONE", "debit", "emi"), ("PAYMENT RECEIVED THANK YOU", "credit", "payment"),
    ("BBPS PAYMENT", "credit", "payment"), ("CASHBACK CREDIT", "credit", "cashback"),
    ("REFUND AMAZON", "credit", "refund"), ("REVERSAL UBER", "credit", "refund"),
])
def test_kind_classification(desc, direction, kind):
    assert kind_of(desc, direction) == kind


def test_detection_is_by_marker_only():
    for e in EXTRACTORS:
        assert not e.detect(["Some Other Bank", "07/09/2026 X 1.00"])


def test_validation_limits():
    st = Statement(format="x", issuer="y")
    with pytest.raises(Failure) as e:
        validate(st)
    assert e.value.code == "NO_TRANSACTIONS"
    from dexter_parser.formats import Line
    st.lines = [Line(i, date(2026, 1, 1), "X", 100, "debit", "purchase") for i in range(1, MAX_LINES + 2)]
    with pytest.raises(Failure) as e:
        validate(st)
    assert e.value.code == "TOO_MANY_LINES"
    st.lines = st.lines[:1]
    st.period_start, st.period_end = date(2026, 2, 1), date(2026, 1, 1)
    with pytest.raises(Failure) as e:
        validate(st)
    assert e.value.code == "BAD_PERIOD"


@pytest.mark.parametrize("data, code", [
    (b"PK\x03\x04 not a pdf", "NOT_A_PDF"),
    (b"%PDF-1.4\n garbage without objects", "PDF_UNREADABLE"),
])
def test_unreadable_files(data, code):
    r = parse_pdf(data)
    assert r["status"] == "failed" and r["errorCode"] == code and r["lines"] == []


def test_empty_text_layer_and_unknown_layout_and_no_rows():
    assert parse_pdf(write_pdf([]))["errorCode"] == "NO_TEXT"
    assert parse_pdf(write_pdf([[(40, "Some Other Bank statement")]]))["errorCode"] == "UNKNOWN_FORMAT"
    r = parse_pdf(write_pdf([[(40, "Saffron Bank (MOCK) - Credit Card Statement")], [(40, "no rows")]]))
    assert r["errorCode"] == "NO_TRANSACTIONS"


def test_encrypted_pdf_is_refused():
    pypdf = pytest.importorskip("pypdf")
    import io
    w = pypdf.PdfWriter(clone_from=pypdf.PdfReader(io.BytesIO(statement("saffron", "4321", date(2026, 10, 5))[0])))
    try:
        w.encrypt("secret", algorithm="RC4-128")
    except Exception as e:  # pragma: no cover - depends on optional crypto support
        pytest.skip(f"pypdf cannot encrypt here: {e}")
    out = io.BytesIO()
    w.write(out)
    assert parse_pdf(out.getvalue())["errorCode"] == "PDF_ENCRYPTED"
