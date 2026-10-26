"""FastAPI shell of the parser: health for the runner, the Kafka worker in the background.

GET /health -> {"status": "UP", "version", "worker": {...}}. The worker is the real interface; there is no
public parse endpoint (statements reach the parser only through portfolio's submitted event).
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
import os

from fastapi import FastAPI

from . import PARSER_VERSION
from .worker import Settings, Worker

logging.basicConfig(level=os.environ.get("PARSER_LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)5s %(name)s %(message)s")

worker = Worker(Settings.from_env())


@contextlib.asynccontextmanager
async def lifespan(_: FastAPI):
    stop = asyncio.Event()
    task = None
    if os.environ.get("PARSER_KAFKA_ENABLED", "true").lower() == "true":
        task = asyncio.create_task(worker.run(stop))
    else:
        worker.stats["state"] = "disabled"
    try:
        yield
    finally:
        stop.set()
        if task:
            with contextlib.suppress(asyncio.CancelledError, asyncio.TimeoutError):
                await asyncio.wait_for(task, timeout=10)


app = FastAPI(title="dexter ai-parser", version=PARSER_VERSION, lifespan=lifespan, docs_url=None, redoc_url=None,
              openapi_url=None)


@app.get("/health")
async def health() -> dict:
    return {"status": "UP", "version": PARSER_VERSION, "worker": dict(worker.stats)}
