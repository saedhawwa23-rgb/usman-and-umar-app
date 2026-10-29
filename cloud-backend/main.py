"""Authenticated, direct Browser Use execution for the Colab-hosted task backend.

The endpoint runs the repository's bounded, read-only Browser Use helper in this
Python process. It does not forward tasks to an external OpenSwarm endpoint and
never fabricates a success result. Full exceptions are logged server-side; client
responses contain the actual agent result or a concise, credential-redacted error.
"""
from __future__ import annotations

import asyncio
import hmac
import ipaddress
import logging
import os
import re
import threading
import time
from collections import defaultdict
from typing import Any
from urllib.parse import urlsplit

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from browser_use_interactive import run_browser_task

app = FastAPI(title="USMAN AND UMAR Direct Browser Use Executor", version="2.0.0")
logger = logging.getLogger(__name__)
_rate_lock = threading.Lock()
_request_times: dict[str, list[float]] = defaultdict(list)
_RATE_WINDOW_SECONDS = 60.0
_RATE_LIMIT = 20
_EXECUTION_TIMEOUT_SECONDS = 55.0
_execution_semaphore = asyncio.Semaphore(1)
_URL_PATTERN = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
_BLOCKED_HOST_SUFFIXES = (".localhost", ".local", ".internal", ".test", ".invalid")
_BLOCKED_HOSTS = {"localhost", "metadata.google.internal", "metadata"}


class ExecuteRequest(BaseModel):
    task_id: str = Field(min_length=1, max_length=128)
    prompt: str = Field(min_length=1, max_length=64_000)


def _error_response(status_code: int, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"status": "error", "error": message},
    )


def _allowed_domains_from_prompt(prompt: str) -> list[str]:
    """Require explicit public URLs and use their hosts as Browser Use's allowlist."""
    domains: list[str] = []
    for match in _URL_PATTERN.findall(prompt):
        candidate = match.rstrip(".,;:!?)]}")
        try:
            parsed = urlsplit(candidate)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail="The browser task contains a malformed URL.") from exc
        host = (parsed.hostname or "").rstrip(".").lower()
        if parsed.scheme.lower() not in {"http", "https"} or not host:
            continue
        if parsed.username or parsed.password:
            raise HTTPException(status_code=400, detail="URLs containing embedded credentials are not allowed.")
        if host in _BLOCKED_HOSTS or host.endswith(_BLOCKED_HOST_SUFFIXES) or "." not in host:
            raise HTTPException(status_code=400, detail="Only public internet hostnames are allowed for browser tasks.")
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            raise HTTPException(status_code=400, detail="Use a public hostname rather than an IP address in browser tasks.")
        try:
            host.encode("idna")
        except UnicodeError as exc:
            raise HTTPException(status_code=400, detail="The browser task contains an invalid hostname.") from exc
        if host not in domains:
            domains.append(host)
    if not domains:
        raise HTTPException(
            status_code=400,
            detail="Include at least one public http(s) URL in the prompt; browser navigation is restricted to hosts in the prompt.",
        )
    return domains


def _safe_exception_detail(exc: Exception) -> str:
    """Return a compact exception summary without common API credential formats."""
    detail = " ".join(str(exc).split())
    detail = re.sub(r"(?i)bearer\s+\S+", "Bearer [REDACTED]", detail)
    detail = re.sub(r"(?i)(api[_-]?key|token)\s*([:=])\s*\S+", r"\1\2[REDACTED]", detail)
    detail = re.sub(r"\bsk-[A-Za-z0-9_-]{12,}\b", "[REDACTED]", detail)
    detail = re.sub(r"(https?://)[^/@\s:]+:[^/@\s]+@", r"\1[REDACTED]@", detail)
    return detail[:300] or "No exception detail was provided."


async def _execute_impl(
    request: ExecuteRequest,
    http_request: Request,
    authorization: str | None,
) -> dict[str, Any] | JSONResponse:
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    if not expected_token:
        return _error_response(503, "APP_CLIENT_TOKEN is not configured on this Colab backend.")
    scheme, separator, supplied_token = (authorization or "").partition(" ")
    if (
        not separator
        or scheme.lower() != "bearer"
        or not supplied_token.strip()
        or not hmac.compare_digest(supplied_token.strip(), expected_token)
    ):
        return _error_response(401, "Invalid application token.")

    client_ip = http_request.client.host if http_request.client else "unknown"
    now = time.monotonic()
    with _rate_lock:
        recent = [stamp for stamp in _request_times[client_ip] if now - stamp < _RATE_WINDOW_SECONDS]
        if len(recent) >= _RATE_LIMIT:
            return _error_response(429, "Request rate limit exceeded.")
        recent.append(now)
        _request_times[client_ip] = recent

    try:
        allowed_domains = _allowed_domains_from_prompt(request.prompt)
    except HTTPException as exc:
        return _error_response(exc.status_code, str(exc.detail))

    try:
        async with _execution_semaphore:
            result = await asyncio.wait_for(
                run_browser_task(
                    request.prompt,
                    allowed_domains=allowed_domains,
                    max_steps=8,
                ),
                timeout=_EXECUTION_TIMEOUT_SECONDS,
            )
    except asyncio.TimeoutError as exc:
        logger.exception("Browser Use timed out for task_id=%s", request.task_id)
        return _error_response(504, "Browser Use timed out; inspect the Colab backend log for the traceback.")
    except Exception as exc:
        logger.exception("Browser Use failed for task_id=%s", request.task_id)
        summary = _safe_exception_detail(exc)
        return _error_response(
            500,
            f"Browser Use failed ({type(exc).__name__}): {summary} Full traceback is in the Colab backend log.",
        )

    if not isinstance(result, str) or not result.strip():
        return _error_response(502, "Browser Use returned no result; no success was recorded.")
    return {"result": result}


@app.get("/")
def root() -> dict[str, str]:
    return {"service": "USMAN AND UMAR Direct Browser Use Executor", "status": "ok", "health": "/health"}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/execute", response_model=None)
async def execute(
    request: ExecuteRequest,
    http_request: Request,
    authorization: str | None = Header(default=None, alias="Authorization"),
) -> dict[str, Any] | JSONResponse:
    return await _execute_impl(request, http_request, authorization)
