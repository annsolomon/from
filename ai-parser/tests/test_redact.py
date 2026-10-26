import pytest

from dexter_parser.redact import luhn_ok, redact, redact_all

VISA = "4111111111111111"
AMEX = "378282246310005"
MASTER = "5555555555554444"


def test_luhn_reference_numbers():
    assert luhn_ok(VISA) and luhn_ok(AMEX) and luhn_ok(MASTER) and luhn_ok("79927398713")
    assert not luhn_ok("4111111111111112")


@pytest.mark.parametrize("text, last4", [
    ("PAYMENT FROM 4111111111111111 THANK YOU", "1111"),
    ("card 4111 1111 1111 1111 used", "1111"),
    ("card 4111-1111-1111-1111 used", "1111"),
    ("AMEX 3782 822463 10005", "0005"),
    ("REF4111111111111111X", "1111"),                # letters touching the number
    ("4111 1111 1111 1111 20", "1111"),              # a short group after the PAN
    ("12 4111 1111 1111 1111", "1111"),              # a short group before it
    ("on 5555 5555 5555 4444 at 10:42", "4444"),
])
def test_luhn_valid_card_numbers_are_masked(text, last4):
    out, n = redact(text)
    assert n == 1
    assert "XXXX XXXX XXXX " + last4 in out
    digits = "".join(c for c in out if c.isdigit())
    for pan in (VISA, AMEX, MASTER):
        assert pan not in digits.replace(" ", "")


@pytest.mark.parametrize("text", [
    "4111111111111112",                 # fails Luhn
    "UPI REF 612345678901",             # 12 digits
    "CALL 9876543210",                  # phone
    "07/09/2026 SWIGGY 1,234.50",       # dates and amounts
    "12345678901234567890123",          # a long digit run is not a card number
    "XXXX XXXX XXXX 4321",              # already masked
])
def test_other_numbers_are_left_alone(text):
    assert redact(text) == (text, 0)


def test_two_numbers_in_one_line_and_totals():
    lines, n = redact_all([f"A {VISA} B {MASTER}", "nothing here", f"C {AMEX}"])
    assert n == 3
    assert lines[1] == "nothing here"
    assert lines[0] == "A XXXX XXXX XXXX 1111 B XXXX XXXX XXXX 4444"
