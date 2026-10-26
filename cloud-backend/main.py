"""Render-hosted bridge to a user-configured OpenSwarm execution endpoint.

This service contains no LLM API integration and never generates placeholder output.
Configure OPEN_SWARM_EXECUTE_URL to the actual OpenSwarm HTTP endpoint before deployment.
"""
from __future__ import annotations

import os
import hmac
import threading
import time
from collections import defaultdict
from typing import Any
from urllib.parse import urlparse

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

app = FastAPI(title="USMAN AND UMAR OpenSwarm Bridge", version="1.0.0")
_rate_lock = threading.Lock()
_request_times: dict[str, list[float]] = defaultdict(list)
_RATE_WINDOW_SECONDS = 60.0
_RATE_LIMIT = 20


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


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/execute")
async def execute(
    request: ExecuteRequest,
    http_request: Request,
    app_token: str | None = Header(default=None, alias="X-App-Token"),
) -> dict[str, Any]:
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    if not expected_token:
        raise HTTPException(status_code=503, detail="Render APP_CLIENT_TOKEN is not configured.")
    if app_token is None or not hmac.compare_digest(app_token, expected_token):
        raise HTTPException(status_code=401, detail="Invalid application token.")

    client_ip = http_request.client.host if http_request.client else "unknown"
    now = time.monotonic()
    with _rate_lock:
        recent = [stamp for stamp in _request_times[client_ip] if now - stamp < _RATE_WINDOW_SECONDS]
        if len(recent) >= _RATE_LIMIT:
            raise HTTPException(status_code=429, detail="Request rate limit exceeded.")
        recent.append(now)
        _request_times[client_ip] = recent

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
