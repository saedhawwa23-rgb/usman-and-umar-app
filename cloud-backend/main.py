"""Render-hosted bridge to a user-configured OpenSwarm execution endpoint.

This service contains no LLM API integration and never generates placeholder output.
The explicitly opt-in mock mode is only for connection testing and marks every response
as mock data; disable it before using the real OpenSwarm upstream.
"""
from __future__ import annotations

import hmac
import logging
import os
import threading
import time
from collections import defaultdict
from typing import Any
from urllib.parse import urlparse

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

app = FastAPI(title="USMAN AND UMAR OpenSwarm Bridge", version="1.0.0")
logger = logging.getLogger(__name__)
_rate_lock = threading.Lock()
_request_times: dict[str, list[float]] = defaultdict(list)
_RATE_WINDOW_SECONDS = 60.0
_RATE_LIMIT = 20
_MOCK_OUTPUT = "[MOCK RESPONSE — NOT A REAL OPENSWARM RESULT] Hello from the Cloud Server!"


class ExecuteRequest(BaseModel):
    task_id: str = Field(min_length=1, max_length=128)
    prompt: str = Field(min_length=1, max_length=64_000)


def _upstream_config() -> tuple[str, str | None]:
    endpoint = os.getenv("OPEN_SWARM_EXECUTE_URL", "").strip()
    if not endpoint:
        raise HTTPException(
            status_code=503,
            detail="OpenSwarm is not configured. Set OPEN_SWARM_EXECUTE_URL to the real OpenSwarm execution endpoint.",
        )
    parsed = urlparse(endpoint)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise HTTPException(status_code=500, detail="OPEN_SWARM_EXECUTE_URL must be an HTTPS URL without embedded credentials.")
    token = os.getenv("OPEN_SWARM_BEARER_TOKEN", "").strip() or None
    return endpoint, token


def _mock_execution_enabled() -> bool:
    return os.getenv("ENABLE_MOCK_EXECUTION", "false").strip().lower() in {"1", "true", "yes", "on"}


def _error_response(status_code: int, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"status": "error", "error": message},
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


async def _execute_impl(
    request: ExecuteRequest,
    http_request: Request,
    authorization: str | None,
) -> dict[str, Any]:
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    if not expected_token:
        raise HTTPException(status_code=503, detail="Render APP_CLIENT_TOKEN is not configured.")
    scheme, separator, supplied_token = (authorization or "").partition(" ")
    if (
        not separator
        or scheme.lower() != "bearer"
        or not supplied_token.strip()
        or not hmac.compare_digest(supplied_token.strip(), expected_token)
    ):
        raise HTTPException(status_code=401, detail="Invalid application token.")

    client_ip = http_request.client.host if http_request.client else "unknown"
    now = time.monotonic()
    with _rate_lock:
        recent = [stamp for stamp in _request_times[client_ip] if now - stamp < _RATE_WINDOW_SECONDS]
        if len(recent) >= _RATE_LIMIT:
            raise HTTPException(status_code=429, detail="Request rate limit exceeded.")
        recent.append(now)
        _request_times[client_ip] = recent

    # Opt in explicitly in Render settings for connection testing. The marker is returned
    # in both fields consumed by app versions and cannot be confused with a real agent result.
    if _mock_execution_enabled():
        return {"status": "mock", "mock": True, "result": _MOCK_OUTPUT, "output": _MOCK_OUTPUT}

    endpoint, token = _upstream_config()
    headers = {"Accept": "application/json, text/plain"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    # Keep headroom beneath Android's 65-second end-to-end call timeout.
    timeout = httpx.Timeout(55.0, connect=10.0)
    try:
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
            upstream = await client.post(
                endpoint,
                json={"task_id": request.task_id, "prompt": request.prompt},
                headers=headers,
            )
    except httpx.TimeoutException as exc:
        raise HTTPException(status_code=504, detail="OpenSwarm execution timed out; no result was returned.") from exc
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Could not reach OpenSwarm: {type(exc).__name__}.") from exc

    if not upstream.is_success:
        raise HTTPException(status_code=502, detail=f"OpenSwarm returned HTTP {upstream.status_code}.")

    raw = upstream.text
    if not raw.strip():
        raise HTTPException(status_code=502, detail="OpenSwarm returned an empty body; no result was returned.")
    try:
        payload = upstream.json()
    except ValueError:
        return {"result": raw}

    if isinstance(payload, dict):
        for key in ("result", "response", "output", "text"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return {"result": value}
        # Preserve structured upstream output exactly as JSON, without inventing a summary.
        return {"result": payload}
    if isinstance(payload, (str, int, float, bool, list)):
        return {"result": payload}
    raise HTTPException(status_code=502, detail="OpenSwarm returned an unsupported JSON value.")


@app.post("/execute", response_model=None)
async def execute(
    request: ExecuteRequest,
    http_request: Request,
    authorization: str | None = Header(default=None, alias="Authorization"),
) -> dict[str, Any] | JSONResponse:
    try:
        return await _execute_impl(request, http_request, authorization)
    except HTTPException as exc:
        return _error_response(exc.status_code, str(exc.detail))
    except Exception:
        logger.exception("Unhandled error in POST /execute")
        return _error_response(500, "Unexpected server error. Check Render logs for details.")
