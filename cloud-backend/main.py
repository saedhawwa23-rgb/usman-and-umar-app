"""Authenticated FastAPI entrypoint for raw Playwright and OpenSwarm execution."""
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

from agent_registry import build_default_registry
from task_state import TaskStateStore
from tool_registry import build_default_capability_registry
from pydantic import BaseModel, Field

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
AI_PACKAGES_WAITING_MESSAGE = "Playwright dependencies are still downloading. Please wait."

# Test seams and optional caches. The heavy modules are intentionally not imported
# here: /health and the FastAPI/Cloudflare startup path must work without them.
run_openswarm_task: Any = None
run_playwright_task: Any = None
_agent_registry = build_default_registry()
_task_store = TaskStateStore()
_capability_registry = build_default_capability_registry(_task_store)


def _load_ai_execution_functions(openswarm_requested: bool) -> None:
    """Load browser and optional OpenSwarm modules only when /execute needs them."""
    global run_playwright_task, run_openswarm_task
    try:
        import playwright  # noqa: F401
        if openswarm_requested and run_openswarm_task is None:
            from openswarm_agent import run_openswarm_task as loaded_swarm
            run_openswarm_task = loaded_swarm
        if not openswarm_requested and run_playwright_task is None:
            from browser_scraper import run_playwright_task as loaded_browser
            run_playwright_task = loaded_browser
    except ModuleNotFoundError as exc:
        logger.info("Browser dependency is not ready: %s", exc)
        raise RuntimeError(AI_PACKAGES_WAITING_MESSAGE) from exc


class ExecuteRequest(BaseModel):
    task_id: str = Field(min_length=1, max_length=128)
    prompt: str = Field(min_length=1, max_length=64_000)
    image_base64: str | None = Field(default=None, max_length=8_000_000)
    plugin_id: str | None = Field(default=None, max_length=64)
    plugins: list[str] = Field(default_factory=list, max_length=8)
    browser_max_steps: int = Field(default=15, ge=1, le=30)
    think_harder: bool = False
    approved_step_ids: list[str] = Field(default_factory=list, max_length=30)


class ToolExecuteRequest(BaseModel):
    payload: dict[str, Any] = Field(default_factory=dict)
    approved: bool = False



def _require_bearer_token(authorization: str | None) -> None:
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    if not expected_token:
        raise HTTPException(status_code=503, detail="APP_CLIENT_TOKEN is not configured on this backend.")
    scheme, separator, supplied = (authorization or "").partition(" ")
    if not separator or scheme.lower() != "bearer" or not supplied.strip() or not hmac.compare_digest(supplied.strip(), expected_token):
        raise HTTPException(status_code=401, detail="Invalid application token.")


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
    return bool(_requested_plugins(request) & {"openswarm_agent", "github_search", "code_executor"})


def _browser_url_from_prompt(prompt: str, fmhy_requested: bool) -> str:
    for match in _URL_PATTERN.findall(prompt):
        candidate = match.rstrip(".,;:!?)]}")
        if urlsplit(candidate).scheme.lower() in {"http", "https"}:
            return candidate
    if fmhy_requested:
        return "https://fmhy.net"
    raise HTTPException(status_code=400, detail="Include a public http(s) URL in the prompt.")


def _click_target_from_prompt(prompt: str) -> str | None:
    action_requested = re.search(r"\b(?:click|follow|open|visit)\b", prompt, re.IGNORECASE)
    quoted = re.search(r"[\"']([^\"']{2,100})[\"']", prompt)
    if action_requested and quoted:
        return quoted.group(1).strip()
    patterns = (
        r"\b(?:click|follow|open|visit)\s+(?:the\s+)?(.+?)(?:\s+link)?(?=[.!?]|$)",
        r"\b(?:find|locate|search for)\s+(?:a\s+|an\s+|the\s+)?(.+?)\s+on\s+(?:the\s+)?fmhy(?:\.net)?\b",
    )
    for pattern in patterns:
        match = re.search(pattern, prompt, re.IGNORECASE)
        if match:
            target = re.split(r"\s+(?:and|then)\s+", match.group(1), maxsplit=1, flags=re.IGNORECASE)[0]
            target = re.sub(r"^(?:public|free|online|best)\s+", "", target.strip(), flags=re.IGNORECASE)
            return target.strip(" .,:;!?\"") or None
    return None


def _allowed_domains_from_prompt(prompt: str, include_fmhy: bool = False, include_github: bool = False, require_url: bool = True) -> list[str]:
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
    if include_github and "github.com" not in domains:
        domains.insert(0, "github.com")
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
    github_requested = "github_search" in _requested_plugins(request)
    openswarm_requested = _openswarm_plugin_requested(request)
    try:
        allowed_domains = _allowed_domains_from_prompt(
            request.prompt,
            include_fmhy=fmhy_requested,
            include_github=github_requested,
            require_url=not openswarm_requested and not github_requested,
        )
    except HTTPException as exc:
        return _error_response(exc.status_code, str(exc.detail))
    try:
        _load_ai_execution_functions(openswarm_requested)
        async with _execution_semaphore:
            max_steps = 30 if request.think_harder else request.browser_max_steps
            if openswarm_requested:
                result = await asyncio.wait_for(run_openswarm_task(
                    task_id=request.task_id,
                    prompt=request.prompt,
                    plugin_id="openswarm_agent",
                    plugin_ids=sorted(_requested_plugins(request)),
                    allowed_domains=allowed_domains,
                    max_steps=max_steps,
                    approved_step_ids=request.approved_step_ids,
                ), timeout=_EXECUTION_TIMEOUT_SECONDS)
            else:
                url = _browser_url_from_prompt(request.prompt, fmhy_requested)
                link_text = _click_target_from_prompt(request.prompt)
                result = await asyncio.wait_for(run_playwright_task(
                    url,
                    allowed_domains=allowed_domains,
                    action="click" if link_text else "scrape",
                    link_text=link_text,
                ), timeout=_EXECUTION_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        logger.exception("Agent execution timed out for task_id=%s", request.task_id)
        return _error_response(504, "Agent execution timed out; inspect the Colab backend log for the traceback.")
    except RuntimeError as exc:
        if str(exc) == AI_PACKAGES_WAITING_MESSAGE:
            return _error_response(503, AI_PACKAGES_WAITING_MESSAGE)
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        label = "OpenSwarm execution" if openswarm_requested else "Playwright browser execution"
        return _error_response(500, f"{label} failed (RuntimeError): {_safe_exception_detail(exc)} Full traceback is in the Colab backend log.")
    except Exception as exc:
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        label = "OpenSwarm execution" if openswarm_requested else "Playwright browser execution"
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

@app.get("/agents")
def agents(authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    return {"agents": _agent_registry.all()}


@app.get("/capabilities")
def capabilities(authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    model_configured = bool(os.getenv("NVIDIA_API_KEY", "").strip() or os.getenv("OPENROUTER_API_KEY", "").strip())
    tool_readiness = {item["id"]: item["adapter_configured"] for item in _capability_registry.describe()}
    agent_tools = {"browser": "browser.extract", "research": "research.search", "github": "github.read", "memory": "memory.read", "terminal": "terminal.execute"}
    configured = {"manager", "planner", "coder"} if model_configured else set()
    configured.update(agent_id for agent_id, tool_id in agent_tools.items() if tool_readiness.get(tool_id, False))
    return {"capabilities": [
        {"id": agent["id"], "name": agent["name"], "capabilities": agent["capabilities"],
         "registered": True, "requires_model": agent["requires_model"],
         "model_configured": model_configured, "adapter_configured": agent["id"] in configured}
        for agent in _agent_registry.all()
    ], "tools": _capability_registry.describe()}


@app.get("/tasks/{task_id}")
def get_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    try:
        return _task_store.get(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.get("/tasks/{task_id}/logs")
def get_task_logs(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    try:
        _task_store.get(task_id)
        return {"events": _task_store.events(task_id)}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc





@app.post("/tasks/{task_id}/pause")
def pause_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    try:
        task = _task_store.get(task_id)
        if task["state"] == "PAUSE_REQUESTED":
            return task
        if task["state"] != "RUNNING":
            raise HTTPException(status_code=409, detail=f"Only a RUNNING task can be paused; current state is {task['state']}.")
        return _task_store.transition(task_id, "PAUSE_REQUESTED", "user_requested_pause")
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/tasks/{task_id}/cancel")
def cancel_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    cancellable = {"PENDING", "PLANNING", "READY", "RUNNING", "VERIFYING",
                   "WAITING_FOR_USER", "PAUSE_REQUESTED", "PAUSED", "RETRYING"}
    try:
        task = _task_store.get(task_id)
        if task["state"] not in cancellable:
            raise HTTPException(status_code=409, detail=f"Task cannot be cancelled from {task['state']}.")
        return _task_store.transition(task_id, "CANCELLED", "user_requested_cancel")
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/tasks/{task_id}/resume")
async def resume_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    try:
        task = _task_store.get(task_id)
        if task["state"] != "PAUSED":
            raise HTTPException(status_code=409, detail=f"Only a PAUSED task can be resumed; current state is {task['state']}.")
        from openswarm_agent import run_openswarm_task
        saved_result = task.get("result") or {}
        result = await asyncio.wait_for(run_openswarm_task(
            task_id=task_id, prompt=task["prompt"], plugin_id=None, plugin_ids=[],
            allowed_domains=_allowed_domains_from_prompt(task["prompt"], require_url=False),
            max_steps=30, approved_step_ids=saved_result.get("approved_step_ids", []),
        ), timeout=_EXECUTION_TIMEOUT_SECONDS)
        return {"result": result}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except asyncio.TimeoutError as exc:
        raise HTTPException(status_code=504, detail="Task resume exceeded the server execution timeout.") from exc
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Task resume failed for task_id=%s", task_id)
        raise HTTPException(status_code=500, detail=_safe_exception_detail(exc)) from exc


@app.get("/tools")
def tools(authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    return {"tools": _capability_registry.describe()}


@app.post("/tools/{capability_id}/execute")
async def execute_tool(
    capability_id: str,
    request: ToolExecuteRequest,
    authorization: str | None = Header(default=None, alias="Authorization"),
) -> dict[str, Any]:
    _require_bearer_token(authorization)
    try:
        result = await _capability_registry.execute(capability_id, request.payload, approved=request.approved)
        return {"status": "executed", "capability_id": capability_id, "result": result}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Capability execution failed: %s", capability_id)
        raise HTTPException(status_code=500, detail=_safe_exception_detail(exc)) from exc

@app.post("/execute", response_model=None)
async def execute(request: ExecuteRequest, http_request: Request, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    return await _execute_impl(request, http_request, authorization)
