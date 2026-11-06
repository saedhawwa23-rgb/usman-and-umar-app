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
from fastapi.exception_handlers import request_validation_exception_handler as default_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from agent_registry import build_default_registry
from task_state import TaskStateStore
from tool_registry import build_default_capability_registry
from drive_task_runtime import DriveRuntimeError, DriveTaskRuntime
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
_drive_runtime = DriveTaskRuntime()
_active_count = 0
_active_lock = threading.Lock()


@app.middleware("http")
async def trace_execute_http_request(request: Request, call_next):
    """Log execute arrival and final HTTP status; never log headers or request bodies."""
    is_execute = request.method == "POST" and request.url.path == "/execute"
    if is_execute:
        logger.info("request_received method=POST path=/execute")
    try:
        response = await call_next(request)
    except Exception:
        if is_execute:
            logger.exception("request_failed method=POST path=/execute stage=middleware")
        raise
    if is_execute:
        logger.info("request_completed method=POST path=/execute http_status=%s", response.status_code)
    return response


@app.exception_handler(RequestValidationError)
async def trace_request_validation_failure(request: Request, exc: RequestValidationError):
    if request.method == "POST" and request.url.path == "/execute":
        body = exc.body if isinstance(exc.body, dict) else {}
        task_id = re.sub(r"[\r\n\t]", "_", str(body.get("task_id", "unknown")))[:128]
        fields = ",".join(str(".".join(map(str, item.get("loc", ())))) for item in exc.errors())[:300]
        logger.warning(
            "execute_validation task_id=%s payload_validation=failed fields=%s",
            task_id,
            fields or "unknown",
        )
    return await default_validation_exception_handler(request, exc)

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
        if openswarm_requested:
            if run_openswarm_task is None:
                from openswarm_agent import run_openswarm_task as loaded_swarm
                run_openswarm_task = loaded_swarm
        else:
            # Direct browser requests need Playwright immediately. OpenSwarm tasks
            # may use only local tools, so browser dependencies remain lazy and
            # load only if a plan selects the browser capability.
            import playwright  # noqa: F401
            if run_playwright_task is None:
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


def _error_response(status_code: int, message: str, error_code: str | None = None, **fields: Any) -> JSONResponse:
    content = {"status": "error", "error": message, "message": message}
    if error_code:
        content["error_code"] = error_code
        content.setdefault("recovery_action", {
            "BACKEND_NOT_READY": "Restore Drive, checkpoint storage, and backend authentication readiness before retrying.",
            "SERVER_DRAINING": "Submit new work to another server reporting accepting_new_tasks=true.",
            "DRIVE_UNAVAILABLE": "Restore Drive connectivity and inspect persisted task state before retrying.",
            "LEASE_CONFLICT": "Do not retry side effects; inspect duplicate/conflicting lease records and reconcile ownership.",
            "STALE_OWNER": "Stop execution on this instance and inspect the latest lease generation.",
            "RECOVERY_BLOCKED": "Reconcile the cited receipts/checkpoint manually before resuming.",
            "CHECKPOINT_INVALID": "Preserve Drive records and inspect checkpoint schema/checksum before recovery.",
            "CHECKPOINT_CONFLICT": "Preserve both records and inspect lease/checkpoint generations before retrying.",
        }.get(error_code, "Inspect the structured error details and task state before retrying."))
    content.update(fields)
    return JSONResponse(status_code=status_code, content=content)


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
    global _active_count
    task_id = re.sub(r"[\r\n\t]", "_", request.task_id)[:128]
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    if not expected_token:
        logger.warning("execute_auth task_id=%s auth=not_configured status=503", task_id)
        return _error_response(503, "APP_CLIENT_TOKEN is not configured on this Colab backend.")
    scheme, separator, supplied_token = (authorization or "").partition(" ")
    if not separator or scheme.lower() != "bearer" or not supplied_token.strip() or not hmac.compare_digest(supplied_token.strip(), expected_token):
        logger.warning("execute_auth task_id=%s auth=failed status=401", task_id)
        return _error_response(401, "Invalid application token.")
    logger.info("execute_auth task_id=%s auth=passed payload_validation=passed", task_id)
    client_ip = http_request.client.host if http_request.client else "unknown"
    now = time.monotonic()
    with _rate_lock:
        recent = [stamp for stamp in _request_times[client_ip] if now - stamp < _RATE_WINDOW_SECONDS]
        if len(recent) >= _RATE_LIMIT:
            logger.warning("execute_status task_id=%s execution_status=rejected status=429 stage=rate_limit", task_id)
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
        logger.warning("execute_status task_id=%s execution_status=rejected status=%s stage=payload_policy", task_id, exc.status_code)
        return _error_response(exc.status_code, str(exc.detail))
    session = None
    counted_active = False
    try:
        health_state = _drive_runtime.health(_active_count)
        if not health_state["ready"]:
            return _error_response(503, "Mandatory backend readiness checks failed; task execution is disabled.",
                "BACKEND_NOT_READY", task_id=request.task_id, ready=False, drive_ready=health_state["drive_ready"],
                auth_ready=health_state["auth_ready"], checkpoints_ready=health_state["checkpoints_ready"],
                recovery_state=health_state["recovery_state"])
        if health_state["draining"]:
            return _error_response(503, "This server instance is draining and is not accepting new tasks.",
                "SERVER_DRAINING", draining=True, accepting_new_tasks=False,
                task_id=request.task_id, server_id=health_state["server_id"], instance_id=health_state["instance_id"])
        _load_ai_execution_functions(openswarm_requested)
        async with _execution_semaphore:
            if openswarm_requested:
                session = await _drive_runtime.acquire(request.task_id).start()
            with _active_lock:
                _active_count += 1
                counted_active = True
            logger.info("execute_started task_id=%s route=%s", task_id, "openswarm" if openswarm_requested else "playwright")
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
                    lifecycle=session,
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
    except DriveRuntimeError as exc:
        logger.warning("execute_status task_id=%s execution_status=blocked error_code=%s", task_id, exc.code)
        status = 409 if exc.code in {"LEASE_CONFLICT", "STALE_OWNER", "RECOVERY_BLOCKED", "CHECKPOINT_CONFLICT"} else 503
        return _error_response(status, _safe_exception_detail(exc), exc.code,
            task_id=request.task_id,
            drive_ready=exc.code not in {"DRIVE_UNAVAILABLE", "LEASE_UNAVAILABLE", "CHECKPOINT_UNAVAILABLE"},
            recovery_state=exc.recovery_state)
    except asyncio.TimeoutError:
        logger.exception("Agent execution timed out for task_id=%s", request.task_id)
        logger.error("execute_status task_id=%s execution_status=failed status=504 stage=execution", task_id)
        return _error_response(504, "Agent execution timed out; inspect the Colab backend log for the traceback.")
    except RuntimeError as exc:
        if str(exc) == AI_PACKAGES_WAITING_MESSAGE:
            logger.warning("execute_status task_id=%s execution_status=not_ready status=503 stage=dependency_load", task_id)
            return _error_response(503, AI_PACKAGES_WAITING_MESSAGE)
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        logger.error("execute_status task_id=%s execution_status=failed status=500 stage=execution exception_class=%s", task_id, type(exc).__name__)
        label = "OpenSwarm execution" if openswarm_requested else "Playwright browser execution"
        return _error_response(500, f"{label} failed (RuntimeError): {_safe_exception_detail(exc)} Full traceback is in the Colab backend log.")
    except Exception as exc:
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        logger.error("execute_status task_id=%s execution_status=failed status=500 stage=execution exception_class=%s", task_id, type(exc).__name__)
        label = "OpenSwarm execution" if openswarm_requested else "Playwright browser execution"
        return _error_response(500, f"{label} failed ({type(exc).__name__}): {_safe_exception_detail(exc)} Full traceback is in the Colab backend log.")
    finally:
        if session is not None:
            await session.close()
        if counted_active:
            with _active_lock:
                _active_count = max(0, _active_count - 1)
    if not isinstance(result, str) or not result.strip():
        logger.error("execute_status task_id=%s execution_status=empty_result status=502 stage=result_validation", task_id)
        return _error_response(502, "Agent returned no result; no success was recorded.")
    logger.info("execute_status task_id=%s execution_status=success status=200", task_id)
    return {"result": result}


@app.get("/")
def root() -> dict[str, str]:
    return {"service": "USMAN AND UMAR OpenSwarm Task Backend", "status": "ok", "health": "/health"}


@app.get("/health")
def health() -> dict[str, Any]:
    return _drive_runtime.health(_active_count)

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





@app.post("/tasks/{task_id}/pause", response_model=None)
def pause_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    try:
        _drive_runtime.assert_task_owner(task_id)
        task = _task_store.get(task_id)
        if task["state"] in {"PAUSE_REQUESTED", "PAUSED"}:
            return task
        if task["state"] != "RUNNING":
            raise HTTPException(status_code=409, detail=f"Only a RUNNING task can be paused; current state is {task['state']}.")
        result = _task_store.transition(task_id, "PAUSE_REQUESTED", "user_requested_pause")
        _drive_runtime.assert_task_owner(task_id)
        return result
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except DriveRuntimeError as exc:
        return _error_response(409 if exc.code == "STALE_OWNER" else 503, str(exc), exc.code,
                               drive_ready=exc.code != "DRIVE_UNAVAILABLE")


@app.post("/tasks/{task_id}/cancel", response_model=None)
def cancel_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    cancellable = {"PENDING", "PLANNING", "READY", "RUNNING", "VERIFYING",
                   "WAITING_FOR_USER", "PAUSE_REQUESTED", "PAUSED", "RETRYING", "CANCEL_REQUESTED"}
    try:
        _drive_runtime.assert_task_owner(task_id)
        task = _task_store.get(task_id)
        if task["state"] == "CANCEL_REQUESTED":
            return {"status": "CANCEL_REQUESTED", "state": "CANCEL_REQUESTED",
                    "cancellation_confirmed": False, "task": task}
        if task["state"] not in cancellable:
            raise HTTPException(status_code=409, detail=f"Task cannot be cancelled from {task['state']}.")
        if task["state"] == "RUNNING":
            requested = _task_store.transition(task_id, "CANCEL_REQUESTED", "user_requested_cancel")
            _drive_runtime.assert_task_owner(task_id)
            return {"status": "CANCEL_REQUESTED", "state": "CANCEL_REQUESTED",
                    "cancellation_confirmed": False, "task": requested}
        result = _task_store.transition(task_id, "CANCELLED", "user_requested_cancel")
        _drive_runtime.assert_task_owner(task_id)
        return result
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except DriveRuntimeError as exc:
        return _error_response(409 if exc.code == "STALE_OWNER" else 503, str(exc), exc.code,
                               drive_ready=exc.code != "DRIVE_UNAVAILABLE")


@app.post("/tasks/{task_id}/resume", response_model=None)
async def resume_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    session = None
    try:
        session = await _drive_runtime.acquire(task_id).start()
        try:
            task = _task_store.get(task_id)
        except KeyError:
            checkpoint_doc = getattr(session, "recovered_checkpoint", None)
            if not checkpoint_doc and session.lease.checkpoint_id and session.lease.checkpoint_version:
                checkpoint_doc = session.coordinator.load_checkpoint(task_id, session.lease.checkpoint_id,
                    session.lease.checkpoint_version)
            if not checkpoint_doc:
                raise HTTPException(status_code=404, detail="No validated Drive checkpoint exists for this task.")
            payload = checkpoint_doc.get("payload") or {}
            guarded_store = session.guarded_store(_task_store)
            saved = ((payload.get("task_state") or {}).get("result") or {})
            guarded_store.create(task_id, str(saved.get("summary") or "Recovered OpenSwarm task"))
            guarded_store.transition(task_id, "PLANNING", "recovered_checkpoint_validated")
            guarded_store.save_plan(task_id, payload.get("plan") or [])
            guarded_store.transition(task_id, "READY", "recovered_plan_ready")
            guarded_store.transition(task_id, "RUNNING", "recovered_execution_state")
            guarded_store.transition(task_id, "PAUSE_REQUESTED", "recovery_resume_checkpoint")
            guarded_store.transition(task_id, "PAUSED", "recovery_checkpoint_restored")
            saved["status"] = "PAUSED"
            saved["results"] = payload.get("results") or []
            saved["approved_step_ids"] = saved.get("approved_step_ids") or []
            guarded_store.save_result(task_id, saved)
            task = _task_store.get(task_id)
        if task["state"] != "PAUSED":
            raise HTTPException(status_code=409, detail=f"Only a PAUSED task can be resumed; current state is {task['state']}.")
        from openswarm_agent import run_openswarm_task
        saved_result = task.get("result") or {}
        result = await asyncio.wait_for(run_openswarm_task(
            task_id=task_id, prompt=task["prompt"], plugin_id=None, plugin_ids=[],
            allowed_domains=_allowed_domains_from_prompt(task["prompt"], require_url=False),
            max_steps=30, approved_step_ids=saved_result.get("approved_step_ids", []),
            lifecycle=session,
        ), timeout=_EXECUTION_TIMEOUT_SECONDS)
        return {"result": result}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except asyncio.TimeoutError as exc:
        raise HTTPException(status_code=504, detail="Task resume exceeded the server execution timeout.") from exc
    except HTTPException:
        raise
    except DriveRuntimeError as exc:
        status_code = 409 if exc.code in {"LEASE_CONFLICT", "STALE_OWNER", "RECOVERY_BLOCKED"} else 503
        return _error_response(status_code, str(exc), exc.code, recovery_state=exc.recovery_state,
                               drive_ready=exc.code != "DRIVE_UNAVAILABLE")
    except Exception as exc:
        logger.exception("Task resume failed for task_id=%s", task_id)
        raise HTTPException(status_code=500, detail=_safe_exception_detail(exc)) from exc
    finally:
        if session is not None:
            await session.close()


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
