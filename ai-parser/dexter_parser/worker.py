"""The Kafka worker: consumes ``portfolio.statement.submitted``, fetches + parses the PDF, publishes
``ai-parser.statement.parsed``.

Delivery: at least once. The input offset is committed only after the output is acknowledged (idempotent producer,
acks=all); the output event id is derived from the input id, so a redelivery is a duplicate portfolio's inbox drops.
A file the parser cannot read is a *result* (status failed + code), not an error. Only infrastructure trouble
(object store unreachable, Kafka down) is retried: the partition is rewound to the message and tried again after a
delay. A message that is not a valid envelope is logged and skipped.
"""
from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass, field

from .envelope import IN_TOPIC, IN_TYPE, OUT_TOPIC, Poison, decode, encode, parsed, submitted
from .fetch import FetchError, fetch
from .parse import failed, parse_pdf

log = logging.getLogger("dexter_parser.worker")


class Transient(Exception):
    """Retry later (nothing is published or committed)."""


@dataclass
class Settings:
    bootstrap: str = "127.0.0.1:9092"
    group: str = "ai-parser"
    allowed: set[str] = field(default_factory=lambda: {"http://127.0.0.1:8333", "http://localhost:8333"})
    max_bytes: int = 10 * 1024 * 1024
    retry_delay: float = 5.0

    @staticmethod
    def from_env() -> "Settings":
        s = Settings()
        port = os.environ.get("KAFKA_PORT", "9092")
        s.bootstrap = os.environ.get("KAFKA_BOOTSTRAP", f"127.0.0.1:{port}")
        origins = os.environ.get("PARSER_OBJECTSTORE_ORIGINS")
        if origins:
            s.allowed = {o.strip().rstrip("/").lower() for o in origins.split(",") if o.strip()}
        else:
            p = os.environ.get("OBJECTSTORE_PORT", "8333")
            s.allowed = {f"http://127.0.0.1:{p}", f"http://localhost:{p}"}
        s.max_bytes = int(os.environ.get("PARSER_MAX_BYTES", s.max_bytes))
        return s


class Worker:
    def __init__(self, settings: Settings, fetcher=fetch, parser=parse_pdf):
        self.s = settings
        self.fetcher = fetcher
        self.parser = parser
        self.stats = {"state": "starting", "processed": 0, "parsed": 0, "failed": 0, "skipped": 0, "retries": 0,
                      "lastError": None}

    async def handle(self, value: bytes) -> dict | None:
        """The envelope to publish for one input message, or None when the message is not for this worker."""
        env = decode(value)
        if env["type"] != IN_TYPE:
            return None
        d = submitted(env)
        try:
            data = await asyncio.to_thread(self.fetcher, d["fileUrl"], d["fileSha256"], d["sizeBytes"], self.s.allowed,
                                           self.s.max_bytes)
        except FetchError as e:
            if e.code == "FILE_UNREACHABLE":
                raise Transient(str(e)) from e
            return parsed(env, failed(e.code, str(e)))
        return parsed(env, await asyncio.to_thread(self.parser, data))

    def _count(self, out: dict | None) -> None:
        self.stats["processed"] += 1
        if out is None:
            self.stats["skipped"] += 1
        elif out["data"]["status"] == "parsed":
            self.stats["parsed"] += 1
        else:
            self.stats["failed"] += 1

    async def run(self, stop: asyncio.Event) -> None:
        from aiokafka import AIOKafkaConsumer, AIOKafkaProducer  # imported here: tests of handle() need no Kafka

        while not stop.is_set():
            consumer = AIOKafkaConsumer(IN_TOPIC, bootstrap_servers=self.s.bootstrap, group_id=self.s.group,
                                        client_id="ai-parser", enable_auto_commit=False,
                                        auto_offset_reset="earliest", metadata_max_age_ms=10_000)
            producer = AIOKafkaProducer(bootstrap_servers=self.s.bootstrap, client_id="ai-parser",
                                        enable_idempotence=True, acks="all")
            try:
                await producer.start()
                await consumer.start()
                self.stats["state"] = "consuming"
                log.info("PARSER_CONSUMING topic=%s bootstrap=%s", IN_TOPIC, self.s.bootstrap)
                while not stop.is_set():
                    batch = await consumer.getmany(timeout_ms=500, max_records=20)
                    for tp, msgs in batch.items():
                        for msg in msgs:
                            try:
                                out = await self.handle(msg.value)
                            except Poison as e:
                                log.warning("PARSER_POISON offset=%s error=%s", msg.offset, e)
                                out = None
                            except Transient as e:
                                self.stats["retries"] += 1
                                self.stats["lastError"] = str(e)
                                log.warning("PARSER_RETRY offset=%s error=%s", msg.offset, e)
                                await asyncio.sleep(self.s.retry_delay)
                                consumer.seek(tp, msg.offset)
                                break
                            if out is not None:
                                await producer.send_and_wait(OUT_TOPIC, key=out["accountId"].encode(),
                                                             value=encode(out))
                                log.info("PARSER_RESULT upload=%s status=%s code=%s lines=%d",
                                         out["aggregateId"], out["data"]["status"], out["data"]["errorCode"],
                                         len(out["data"]["lines"]))
                            await consumer.commit({tp: msg.offset + 1})
                            self._count(out)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # Kafka down, metadata errors: reconnect after a delay
                self.stats["state"] = "reconnecting"
                self.stats["lastError"] = f"{type(e).__name__}: {e}"[:200]
                log.warning("PARSER_KAFKA error=%s", self.stats["lastError"])
                await asyncio.sleep(self.s.retry_delay)
            finally:
                for c in (consumer, producer):
                    try:
                        await c.stop()
                    except Exception:  # noqa: BLE001 - best effort on shutdown
                        pass
        self.stats["state"] = "stopped"
