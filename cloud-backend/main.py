"""Authenticated FastAPI entrypoint for direct Browser Use and OpenSwarm execution."""
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

from browser_use_interactive import NVIDIA_DEFAULT_MODEL, run_browser_task
from openswarm_agent import run_openswarm_task

app = FastAPI(title="USMAN AND UMAR OpenSwarm Task Backend", version="3.0.0")
logger = logging.getLogger(__name__)
_rate_lock = threading.Lock()
_request_times: dict[str, list[float]] = defaultdict(list)
_RATE_WINDOW_SECONDS = 60.0
_RATE_LIMIT = 20
_EXECUTION_TIMEOUT_SECONDS = 180.0
_execution_semaphore = asyncio.Semaphore(1)
_URL_PATTERN = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
_BLOCKED_HOST_SUFFIXES = (".localhost", ".local", ".internal", ".test", ".invalid")
_BLOCKED_HOSTS = {"localhost", "metadata.google.internal", "metadata"}


class ExecuteRequest(BaseModel):
    task_id: str = Field(min_length=1, max_length=128)
    prompt: str = Field(min_length=1, max_length=64_000)
    plugin_id: str | None = Field(default=None, max_length=64)
    plugins: list[str] = Field(default_factory=list, max_length=8)
    browser_max_steps: int = Field(default=15, ge=1, le=30)
    think_harder: bool = False


def _error_response(status_code: int, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"status": "error", "error": message})


def _requested_plugins(request: ExecuteRequest) -> set[str]:
    requested = {item.strip().lower() for item in request.plugins if item.strip()}
    if request.plugin_id:
        requested.add(request.plugin_id.strip().lower())
    return requested


def _fmhy_plugin_requested(request: ExecuteRequest) -> bool:
    requested = _requested_plugins(request)
    return bool(requested & {"fmhy_browser", "fmhy_directory"}) or "fmhy.net" in request.prompt.lower()


def _openswarm_plugin_requested(request: ExecuteRequest) -> bool:
    return "openswarm_agent" in _requested_plugins(request)


def _allowed_domains_from_prompt(prompt: str, include_fmhy: bool = False, require_url: bool = True) -> list[str]:
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
    if not domains and not include_fmhy and require_url:
        raise HTTPException(status_code=400, detail="Include at least one public http(s) URL in the prompt; browser navigation is restricted to hosts in the prompt.")
    if include_fmhy and "fmhy.net" not in domains:
        domains.insert(0, "fmhy.net")
    return domains


def _safe_exception_detail(exc: Exception) -> str:
    detail = " ".join(str(exc).split())
    detail = re.sub(r"(?i)bearer\s+\S+", "Bearer [REDACTED]", detail)
    detail = re.sub(r"(?i)(api[_-]?key|token)\s*([:=])\s*\S+", r"\1\2[REDACTED]", detail)
    detail = re.sub(r"\bsk-[A-Za-z0-9_-]{12,}\b", "[REDACTED]", detail)
    detail = re.sub(r"(https?://)[^/@\s:]+:[^/@\s]+@", r"\1[REDACTED]@", detail)
    return detail[:300] or "No exception detail was provided."


async def _execute_impl(request: ExecuteRequest, http_request: Request, authorization: str | None) -> dict[str, Any] | JSONResponse:
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    if not expected_token:
        return _error_response(503, "APP_CLIENT_TOKEN is not configured on this Colab backend.")
    scheme, separator, supplied_token = (authorization or "").partition(" ")
    if not separator or scheme.lower() != "bearer" or not supplied_token.strip() or not hmac.compare_digest(supplied_token.strip(), expected_token):
        return _error_response(401, "Invalid application token.")
    client_ip = http_request.client.host if http_request.client else "unknown"
    now = time.monotonic()
    with _rate_lock:
        recent = [stamp for stamp in _request_times[client_ip] if now - stamp < _RATE_WINDOW_SECONDS]
        if len(recent) >= _RATE_LIMIT:
            return _error_response(429, "Request rate limit exceeded.")
        recent.append(now)
        _request_times[client_ip] = recent
    fmhy_requested = _fmhy_plugin_requested(request)
    openswarm_requested = _openswarm_plugin_requested(request)
    try:
        allowed_domains = _allowed_domains_from_prompt(
            request.prompt,
            include_fmhy=fmhy_requested,
            require_url=not openswarm_requested,
        )
    except HTTPException as exc:
        return _error_response(exc.status_code, str(exc.detail))
    try:
        async with _execution_semaphore:
            max_steps = 30 if request.think_harder else request.browser_max_steps
            if openswarm_requested:
                result = await asyncio.wait_for(run_openswarm_task(
                    task_id=request.task_id,
                    prompt=request.prompt,
                    plugin_id="openswarm_agent",
                    allowed_domains=allowed_domains,
                    max_steps=max_steps,
                ), timeout=_EXECUTION_TIMEOUT_SECONDS)
            else:
                model = NVIDIA_DEFAULT_MODEL if os.getenv("NVIDIA_API_KEY", "").strip() else None
                result = await asyncio.wait_for(run_browser_task(
                    request.prompt,
                    allowed_domains=allowed_domains,
                    model=model,
                    max_steps=max_steps,
                    allow_fmhy_external=fmhy_requested,
                ), timeout=_EXECUTION_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        logger.exception("Agent execution timed out for task_id=%s", request.task_id)
        return _error_response(504, "Agent execution timed out; inspect the Colab backend log for the traceback.")
    except Exception as exc:
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        label = "OpenSwarm execution" if openswarm_requested else "Browser Use"
        return _error_response(500, f"{label} failed ({type(exc).__name__}): {_safe_exception_detail(exc)} Full traceback is in the Colab backend log.")
    if not isinstance(result, str) or not result.strip():
        return _error_response(502, "Agent returned no result; no success was recorded.")
    return {"result": result}


@app.get("/")
def root() -> dict[str, str]:
    return {"service": "USMAN AND UMAR OpenSwarm Task Backend", "status": "ok", "health": "/health"}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/execute", response_model=None)
async def execute(request: ExecuteRequest, http_request: Request, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    return await _execute_impl(request, http_request, authorization)
