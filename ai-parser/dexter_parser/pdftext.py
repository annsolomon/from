"""PDF bytes -> text lines (pypdf), and a minimal PDF writer for the mock statements.

The writer exists so the samples, the tests, the API smoke and the trail all use real PDF files with a text layer,
built deterministically without a PDF library: one page per 48 rows, Helvetica, each row's cells drawn as separate
text objects at fixed x positions (as bank statements lay out their columns).
"""
from __future__ import annotations

import io
import logging

ROWS_PER_PAGE = 48
MAX_PAGES = 40

logging.getLogger("pypdf").setLevel(logging.ERROR)


class PdfError(Exception):
    """The file is not a readable PDF (code: NOT_A_PDF, PDF_ENCRYPTED, PDF_UNREADABLE, TOO_MANY_PAGES)."""

    def __init__(self, code: str, detail: str):
        super().__init__(detail)
        self.code = code


def extract_lines(data: bytes) -> list[str]:
    """Every non-blank text line of the PDF, page by page, whitespace collapsed to single spaces."""
    from pypdf import PdfReader  # imported here: the writer (samples, smoke) needs no pypdf
    from pypdf.errors import PdfReadError

    if not data.startswith(b"%PDF-"):
        raise PdfError("NOT_A_PDF", "the file does not start with %PDF-")
    try:
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            raise PdfError("PDF_ENCRYPTED", "password-protected PDFs are not supported; upload the unlocked file")
        if len(reader.pages) > MAX_PAGES:
            raise PdfError("TOO_MANY_PAGES", f"at most {MAX_PAGES} pages")
        out: list[str] = []
        for page in reader.pages:
            text = page.extract_text() or ""
            for raw in text.splitlines():
                line = " ".join(raw.split())
                if line:
                    out.append(line)
        return out
    except PdfError:
        raise
    except (PdfReadError, ValueError, KeyError, TypeError, IndexError, AttributeError) as e:
        raise PdfError("PDF_UNREADABLE", f"cannot read the PDF: {type(e).__name__}") from e


# ------------------------------------------------------------------------------------------------ writer

def _escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def write_pdf(rows: list[list[tuple[float, str]]]) -> bytes:
    """A PDF whose page rows are lists of (x, text) cells; ASCII text only (Helvetica, WinAnsi)."""
    pages = [rows[i:i + ROWS_PER_PAGE] for i in range(0, max(len(rows), 1), ROWS_PER_PAGE)] or [[]]
    objects: list[bytes] = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)

    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    pages_id = len(objects) + 1 + 2 * len(pages)   # after the content + page objects
    page_ids = []
    for page_rows in pages:
        ops = []
        y = 800
        for cells in page_rows:
            for x, text in cells:
                text.encode("latin-1")  # fails loudly on characters Helvetica/WinAnsi cannot show
                ops.append(f"BT /F1 9 Tf {x:.0f} {y} Td ({_escape(text)}) Tj ET")
            y -= 16
        stream = "\n".join(ops).encode("latin-1")
        content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        page_ids.append(add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Contents %d 0 R"
                            b" /Resources << /Font << /F1 %d 0 R >> >> >>" % (pages_id, content, font)))
    kids = " ".join(f"{p} 0 R" for p in page_ids).encode()
    assert add(b"<< /Type /Pages /Kids [" + kids + b"] /Count %d >>" % len(page_ids)) == pages_id
    catalog = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)

    out = io.BytesIO()
    out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(b"%d 0 obj\n" % i + body + b"\nendobj\n")
    xref = out.tell()
    out.write(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1))
    for off in offsets:
        out.write(b"%010d 00000 n \n" % off)
    out.write(b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog, xref))
    return out.getvalue()
