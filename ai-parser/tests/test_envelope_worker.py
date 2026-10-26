import asyncio
import hashlib
import http.server
import json
import threading
import uuid
from datetime import date

import pytest
import schema

from dexter_parser import envelope
from dexter_parser.fetch import FetchError, fetch
from dexter_parser.samples import statement
from dexter_parser.worker import Settings, Transient, Worker

ACCOUNT = "0192f1e2-0000-7000-8000-0000000000a1"
UPLOAD = "0192f1e2-0000-7000-8000-0000000000f1"
CARD = "0192f1e2-0000-7000-8000-0000000000b1"


def submitted_event(url="http://127.0.0.1:8333/b/k.pdf", sha="0" * 64, size=100, event_id=None):
    return {"id": event_id or str(uuid.uuid4()), "type": envelope.IN_TYPE, "version": 1, "source": "portfolio",
            "time": "2026-10-26T10:00:00Z", "accountId": ACCOUNT, "aggregateId": UPLOAD, "aggregateVersion": 1,
            "traceparent": None,
            "data": {"uploadId": UPLOAD, "userCardId": CARD, "last4": "4321", "fileUrl": url, "fileSha256": sha,
                     "sizeBytes": size, "contentType": "application/pdf", "urlExpiresAt": "2026-10-26T16:00:00Z",
                     "submittedAt": "2026-10-26T10:00:00Z"}}


def test_the_submitted_sample_matches_its_contract():
    ev = submitted_event()
    assert schema.validate(schema.load("envelope.v1.json"), ev) == []
    assert schema.validate(schema.load("portfolio.statement.submitted.v1.json"), ev["data"]) == []


def run(coro):
    return asyncio.run(coro)


def worker_with(pdf_or_error):
    def fetcher(url, sha, size, allowed, max_bytes):
        if isinstance(pdf_or_error, Exception):
            raise pdf_or_error
        return pdf_or_error
    return Worker(Settings(), fetcher=fetcher)


@pytest.mark.parametrize("fmt", ["saffron", "indus", "deccan"])
def test_parsed_events_match_the_contract(fmt):
    pdf, expected = statement(fmt, "4321", date(2026, 10, 5))
    out = run(worker_with(pdf).handle(envelope.encode(submitted_event())))
    assert schema.validate(schema.load("envelope.v1.json"), out) == []
    assert schema.validate(schema.load("ai-parser.statement.parsed.v1.json"), out["data"]) == []
    assert out["type"] == "ai-parser.statement.parsed" and out["source"] == "ai-parser"
    assert out["accountId"] == ACCOUNT and out["aggregateId"] == UPLOAD
    assert out["data"]["status"] == "parsed" and out["data"]["lines"] == expected["lines"]


def test_failed_results_match_the_contract_too():
    out = run(worker_with(b"not a pdf").handle(envelope.encode(submitted_event())))
    assert out["data"]["status"] == "failed" and out["data"]["errorCode"] == "NOT_A_PDF"
    assert schema.validate(schema.load("ai-parser.statement.parsed.v1.json"), out["data"]) == []
    out = run(worker_with(FetchError("FILE_UNAVAILABLE", "expired")).handle(envelope.encode(submitted_event())))
    assert out["data"]["errorCode"] == "FILE_UNAVAILABLE"
    assert schema.validate(schema.load("ai-parser.statement.parsed.v1.json"), out["data"]) == []


def test_a_redelivered_input_gives_the_same_event_id():
    pdf, _ = statement("indus", "4321", date(2026, 10, 5))
    ev = envelope.encode(submitted_event(event_id="0192f1e2-0000-7000-8000-00000000e001"))
    a, b = run(worker_with(pdf).handle(ev)), run(worker_with(pdf).handle(ev))
    assert a["id"] == b["id"]
    other = run(worker_with(pdf).handle(envelope.encode(submitted_event())))
    assert other["id"] != a["id"]


def test_unreachable_store_is_retried_not_published():
    with pytest.raises(Transient):
        run(worker_with(FetchError("FILE_UNREACHABLE", "down")).handle(envelope.encode(submitted_event())))


def test_other_types_are_ignored_and_garbage_is_poison():
    ev = submitted_event()
    ev["type"] = "portfolio.card.added"
    assert run(worker_with(b"").handle(envelope.encode(ev))) is None
    for bad in (b"{", b"[]", b'{"id":"x","data":{}}', envelope.encode({**submitted_event(), "accountId": None})):
        with pytest.raises(envelope.Poison):
            run(worker_with(b"").handle(bad))


# ------------------------------------------------------------------------------------------------ fetch

@pytest.fixture(scope="module")
def store():
    pdf, _ = statement("saffron", "4321", date(2026, 10, 5))

    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/b/ok.pdf":
                self.send_response(200)
                self.end_headers()
                self.wfile.write(pdf)
            elif self.path == "/b/moved.pdf":
                self.send_response(302)
                self.send_header("Location", "http://169.254.169.254/latest/meta-data")
                self.end_headers()
            else:
                self.send_response(403)
                self.end_headers()

        def log_message(self, *a):
            pass

    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_port}", pdf, hashlib.sha256(pdf).hexdigest()
    srv.shutdown()


def test_fetch_checks_origin_size_redirects_and_checksum(store):
    base, pdf, sha = store
    allowed = {base}
    assert fetch(base + "/b/ok.pdf", sha, len(pdf), allowed, 10_000_000) == pdf
    cases = [
        (lambda: fetch(base + "/b/ok.pdf", "1" * 64, len(pdf), allowed, 10_000_000), "FILE_CHANGED"),
        (lambda: fetch(base + "/b/ok.pdf", sha, len(pdf), allowed, 100), "FILE_TOO_LARGE"),
        (lambda: fetch(base + "/b/ok.pdf", sha, 50, allowed, 100), "FILE_TOO_LARGE"),
        (lambda: fetch(base + "/b/expired.pdf", sha, len(pdf), allowed, 10_000_000), "FILE_UNAVAILABLE"),
        (lambda: fetch(base + "/b/moved.pdf", sha, len(pdf), allowed, 10_000_000), "FILE_UNAVAILABLE"),
        (lambda: fetch("http://169.254.169.254/x", sha, len(pdf), allowed, 10_000_000), "FILE_UNAVAILABLE"),
        (lambda: fetch("http://127.0.0.1:1/x.pdf", sha, 5, {"http://127.0.0.1:1"}, 100, 2), "FILE_UNREACHABLE"),
    ]
    for call, code in cases:
        with pytest.raises(FetchError) as e:
            call()
        assert e.value.code == code


def test_settings_from_env(monkeypatch):
    monkeypatch.setenv("KAFKA_BOOTSTRAP", "kafka:9092")
    monkeypatch.setenv("PARSER_OBJECTSTORE_ORIGINS", "http://S3.internal:8333/, http://127.0.0.1:8333")
    s = Settings.from_env()
    assert s.bootstrap == "kafka:9092" and s.allowed == {"http://s3.internal:8333", "http://127.0.0.1:8333"}


def test_health(monkeypatch):
    from fastapi.testclient import TestClient
    from dexter_parser.app import app
    with TestClient(app) as c:
        r = c.get("/health")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "UP" and body["worker"]["state"] == "disabled"
        assert c.get("/docs").status_code == 404
    assert json.loads(json.dumps(body))
