"""Authenticated FastAPI entrypoint for raw Playwright and OpenSwarm execution."""
from __future__ import annotations

import asyncio
import hmac
import json
import hashlib
import ipaddress
import logging
import os
import re
import threading
import time
import socket
import uuid
from datetime import datetime, timezone
from collections import defaultdict
from typing import Any
from urllib.parse import urlsplit

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exception_handlers import request_validation_exception_handler as default_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from agent_registry import build_default_registry
from task_state import TaskStateStore
from firestore_task_lease import FirestoreTaskLeaseCoordinator, LeaseConflict, LeaseUnavailable
from drive_checkpoint_store import GoogleDriveCheckpointStore, CheckpointError
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
_SERVER_ID = os.getenv("OPENSWARM_SERVER_ID", "server-" + socket.gethostname())
_INSTANCE_ID = str(uuid.uuid4())
_START_TIME = time.time()
_MAX_RUNTIME_SECONDS = int(os.getenv("MAX_RUNTIME_SECONDS", str(12 * 60 * 60)))
_DRAIN_THRESHOLD_SECONDS = int(os.getenv("DRAIN_THRESHOLD_SECONDS", str(11 * 60 * 60 + 45 * 60)))
_lease_coordinator: FirestoreTaskLeaseCoordinator | None = None
_lease_coordinator_lock = threading.Lock()
_checkpoint_store: GoogleDriveCheckpointStore | None = None
_checkpoint_store_lock = threading.Lock()
_URL_PATTERN = re.compile(r"https?://[^\s<>\"']+", re.IGNORECASE)
_BLOCKED_HOST_SUFFIXES = (".localhost", ".local", ".internal", ".test", ".invalid")
_BLOCKED_HOSTS = {"localhost", "metadata.google.internal", "metadata"}
AI_PACKAGES_WAITING_MESSAGE = "Playwright dependencies are still downloading. Please wait."


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


def _get_lease_coordinator() -> FirestoreTaskLeaseCoordinator:
    """Return the transactional coordinator or fail closed; never fall back to Drive/SQLite."""
    global _lease_coordinator
    with _lease_coordinator_lock:
        if _lease_coordinator is None:
            _lease_coordinator = FirestoreTaskLeaseCoordinator.from_env(_SERVER_ID, _INSTANCE_ID)
        return _lease_coordinator


def _get_checkpoint_store() -> GoogleDriveCheckpointStore:
    global _checkpoint_store
    with _checkpoint_store_lock:
        if _checkpoint_store is None:
            _checkpoint_store = GoogleDriveCheckpointStore.from_env()
        return _checkpoint_store


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


def _redact_checkpoint_value(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _redact_checkpoint_value(item) for key, item in value.items()
                if not any(secret in str(key).lower() for secret in
                           ("password", "access_token", "refresh_token", "api_key", "secret", "credential"))}
    if isinstance(value, list):
        return [_redact_checkpoint_value(item) for item in value]
    if isinstance(value, str):
        safe = re.sub(r"(?i)bearer\s+[^\s,;]+", "Bearer [REDACTED]", value)
        safe = re.sub(r"(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|token)(\s*[:=]\s*)[^\s,;]+", r"\1\2[REDACTED]", safe)
        return safe
    return value


def _lease_error(status_code: int, error: str, message: str, task_id: str,
                 operation: str, retryable: bool, recovery_action: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={
        "error": error, "message": message, "task_id": task_id,
        "server_id": _SERVER_ID, "instance_id": _INSTANCE_ID,
        "operation": operation, "retryable": retryable,
        "recovery_action": recovery_action,
    })


async def _execute_impl(request: ExecuteRequest, http_request: Request,
                        authorization: str | None) -> dict[str, Any] | JSONResponse:
    """Claim authoritative ownership before any task execution; fail closed."""
    task_id = re.sub(r"[\r\n\t]", "_", request.task_id)[:128]
    expected_token = os.getenv("APP_CLIENT_TOKEN", "").strip()
    scheme, separator, supplied = (authorization or "").partition(" ")
    if not expected_token:
        logger.warning("execute_auth task_id=%s auth=not_configured status=503", task_id)
        return _error_response(503, "APP_CLIENT_TOKEN is not configured on this backend.")
    if not separator or scheme.lower() != "bearer" or not supplied.strip() or not hmac.compare_digest(supplied.strip(), expected_token):
        logger.warning("execute_auth task_id=%s auth=failed status=401", task_id)
        return _error_response(401, "Invalid application token.")
    fmhy_requested = _fmhy_plugin_requested(request)
    github_requested = "github_search" in _requested_plugins(request)
    openswarm_requested = _openswarm_plugin_requested(request)
    try:
        _allowed_domains_from_prompt(request.prompt, include_fmhy=fmhy_requested,
                                     include_github=github_requested,
                                     require_url=not openswarm_requested and not github_requested)
    except HTTPException as exc:
        return _error_response(exc.status_code, str(exc.detail))
    age = time.time() - _START_TIME
    if age >= _DRAIN_THRESHOLD_SECONDS:
        return _lease_error(503, "SERVER_DRAINING", "This server is draining and will not accept new tasks.", task_id, "execute", True, "route_to_healthy_server")
    try:
        checkpoint_store = _get_checkpoint_store()
        await asyncio.to_thread(checkpoint_store.readiness_check)
    except CheckpointError:
        return _lease_error(503, "DRIVE_UNAVAILABLE", "Durable Google Drive checkpoint storage is unavailable; execution was not started.", task_id, "checkpoint_storage_readiness", True, "retry_after_storage_health_check")
    try:
        coordinator = _get_lease_coordinator()
        lease = await asyncio.to_thread(coordinator.acquire, task_id)
    except LeaseConflict as exc:
        return _lease_error(409, "LEASE_CONFLICT", str(exc), task_id, "execute", False, "reconcile_task_owner")
    except LeaseUnavailable:
        return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Transactional task ownership is unavailable; execution was not started.", task_id, "lease_acquire", True, "retry_after_coordinator_health_check")

    stop_heartbeat = asyncio.Event()
    body_task: asyncio.Task | None = None
    async def ownership_guard() -> None:
        try:
            await asyncio.to_thread(coordinator.validate, lease)
            control = await asyncio.to_thread(coordinator.get_control, task_id)
            command = str(control.get("command", "NONE")).upper()
            if command in {"PAUSE", "CANCEL"}:
                try:
                    task = _task_store.get(task_id)
                    state = task["state"]
                    if command == "PAUSE" and state == "RUNNING":
                        _task_store.transition(task_id, "PAUSE_REQUESTED", "remote_pause_requested",
                                               {"control_generation": control.get("control_generation")})
                    elif command == "CANCEL" and state in {"PENDING", "PLANNING", "READY", "RUNNING", "VERIFYING",
                                                              "WAITING_FOR_USER", "PAUSE_REQUESTED", "PAUSED", "RETRYING"}:
                        _task_store.transition(task_id, "CANCEL_REQUESTED", "remote_cancel_requested",
                                               {"control_generation": control.get("control_generation")})
                except (KeyError, ValueError):
                    pass
        except LeaseConflict:
            raise
        except LeaseUnavailable as exc:
            raise LeaseUnavailable("Task ownership could not be verified") from exc

    checkpoint_lock = asyncio.Lock()
    async def checkpoint_callback(snapshot: dict[str, Any]) -> dict[str, Any]:
        async with checkpoint_lock:
            await ownership_guard()
            current_state = await asyncio.to_thread(coordinator.get_task_state, task_id)
            checkpoint_version = int((current_state or {}).get("checkpoint_version", 0)) + 1
            timestamp = datetime.now(timezone.utc).isoformat()
            cleaned = _redact_checkpoint_value(snapshot)
            checkpoint = {
                "task_id": task_id,
                "checkpoint_version": checkpoint_version,
                "schema_version": 1,
                "graph_name": "openswarm",
                "graph_version": "1",
                "original_prompt": _redact_checkpoint_value(request.prompt),
                "current_step": cleaned.get("current_step", ""),
                "completed_steps": cleaned.get("completed_steps", []),
                "pending_steps": cleaned.get("pending_steps", []),
                "graph_state": cleaned,
                "execution_receipts": cleaned.get("execution_receipts", []),
                "idempotency_keys": cleaned.get("idempotency_keys", []),
                "task_status": cleaned.get("task_status", "RUNNING"),
                "task_lease_version": lease.lease_version,
                "server_id": _SERVER_ID,
                "server_instance_id": _INSTANCE_ID,
                "created_at": (current_state or {}).get("created_at", timestamp),
                "updated_at": timestamp,
                "recovery_metadata": {"checkpoint_store": "google_drive", "verified_readback": True},
            }
            uploaded = await asyncio.to_thread(checkpoint_store.upload, checkpoint)
            await ownership_guard()
            await asyncio.to_thread(coordinator.update_task_state, lease, {
                "checkpoint_version": checkpoint_version,
                "checkpoint_file_id": uploaded["file_id"],
                "checkpoint_sha256": uploaded["sha256"],
                "task_status": checkpoint["task_status"],
                "current_step": checkpoint["current_step"],
            })
            return uploaded

    async def heartbeat() -> None:
        interval = int(os.getenv("TASK_LEASE_HEARTBEAT_SECONDS", "20"))
        while not stop_heartbeat.is_set():
            try:
                await asyncio.wait_for(stop_heartbeat.wait(), timeout=interval)
                return
            except asyncio.TimeoutError:
                pass
            try:
                await asyncio.to_thread(coordinator.renew, lease)
            except Exception:
                if body_task is not None and not body_task.done():
                    body_task.cancel()
                return

    heartbeat_task = asyncio.create_task(heartbeat())
    try:
        await ownership_guard()
        body_task = asyncio.create_task(_execute_body(request, http_request, authorization,
                                                       ownership_guard, checkpoint_callback, lease))
        result = await body_task
        if isinstance(result, JSONResponse) and result.status_code >= 500:
            # An execution error may have an uncertain external outcome. Keep
            # the lease until expiry + recovery grace rather than immediately
            # making the task available to another runtime.
            return result
        task_status = "COMPLETED"
        if isinstance(result, dict) and isinstance(result.get("result"), str):
            try:
                task_status = str(json.loads(result["result"]).get("status", "COMPLETED")).upper()
            except (ValueError, TypeError):
                task_status = "COMPLETED"
        if isinstance(result, JSONResponse):
            task_status = "REJECTED"
        await ownership_guard()
        await asyncio.to_thread(coordinator.update_task_state, lease, {
            "task_status": task_status,
            "server_id": _SERVER_ID, "server_instance_id": _INSTANCE_ID,
        })
        await asyncio.to_thread(coordinator.release, lease)
        return result
    except LeaseConflict as exc:
        return _lease_error(409, "STALE_FENCING_TOKEN", str(exc), task_id, "execute", False, "reconcile_task_owner")
    except LeaseUnavailable:
        return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Task ownership could not be verified; execution stopped.", task_id, "lease_validation", True, "reconcile_task_owner")
    except asyncio.CancelledError:
        return _lease_error(503, "EXECUTION_OUTCOME_UNKNOWN", "Ownership heartbeat was lost; task outcome requires reconciliation.", task_id, "execution", True, "inspect_execution_receipt")
    finally:
        stop_heartbeat.set()
        heartbeat_task.cancel()
        await asyncio.gather(heartbeat_task, return_exceptions=True)


async def _execute_body(request: ExecuteRequest, http_request: Request,
                        authorization: str | None,
                        ownership_guard: Any = None,
                        checkpoint_callback: Any = None,
                        lease: Any = None) -> dict[str, Any] | JSONResponse:
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
    try:
        _load_ai_execution_functions(openswarm_requested)
        async with _execution_semaphore:
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
                    ownership_guard=ownership_guard,
                    checkpoint_callback=checkpoint_callback,
                    server_id=_SERVER_ID,
                    instance_id=_INSTANCE_ID,
                    fencing_token=lease.lease_version,
                ), timeout=_EXECUTION_TIMEOUT_SECONDS)
            else:
                url = _browser_url_from_prompt(request.prompt, fmhy_requested)
                link_text = _click_target_from_prompt(request.prompt)
                idempotency_key = hashlib.sha256(f"{task_id}:browser:1".encode("utf-8")).hexdigest()
                browser_receipt = {
                    "task_id": task_id, "step_id": "browser-1", "action_id": "browser.navigate",
                    "idempotency_key": idempotency_key, "server_id": _SERVER_ID,
                    "instance_id": _INSTANCE_ID, "started_at": time.time(),
                    "fencing_token": getattr(lease, "lease_version", None), "status": "EXECUTING",
                    "completed_at": None, "external_reference": None,
                    "result_hash": None, "error_details": None,
                }
                if checkpoint_callback is not None:
                    await checkpoint_callback({
                        "current_step": "browser-1", "completed_steps": [], "pending_steps": ["browser-1"],
                        "graph_state": {"summary": "Direct browser task", "plan": [], "results": [],
                                        "approved_step_ids": []},
                        "execution_receipts": [browser_receipt], "idempotency_keys": [idempotency_key],
                        "task_status": "RUNNING",
                    })
                if ownership_guard is not None:
                    await ownership_guard()
                result = await asyncio.wait_for(run_playwright_task(
                    url,
                    allowed_domains=allowed_domains,
                    action="click" if link_text else "scrape",
                    link_text=link_text,
                ), timeout=_EXECUTION_TIMEOUT_SECONDS)
                if ownership_guard is not None:
                    await ownership_guard()
                browser_receipt = {**browser_receipt, "status": "CONFIRMED", "completed_at": time.time(),
                                   "result_hash": hashlib.sha256(str(result).encode("utf-8")).hexdigest()}
                if checkpoint_callback is not None:
                    await checkpoint_callback({
                        "current_step": "", "completed_steps": ["browser-1"], "pending_steps": [],
                        "graph_state": {"summary": "Direct browser task", "plan": [],
                                        "results": [{"step_id": "browser-1", "agent_id": "browser",
                                                     "status": "COMPLETED", "output": str(result)}],
                                        "approved_step_ids": []},
                        "execution_receipts": [browser_receipt], "idempotency_keys": [idempotency_key],
                        "task_status": "COMPLETED",
                    })
    except asyncio.TimeoutError:
        logger.exception("Agent execution timed out for task_id=%s", request.task_id)
        logger.error("execute_status task_id=%s execution_status=failed status=504 stage=execution", task_id)
        return _lease_error(504, "EXECUTION_OUTCOME_UNKNOWN", "Execution timed out; inspect the receipt before retrying.", task_id, "execution", True, "inspect_execution_receipt")
    except LeaseConflict as exc:
        code = "LEASE_EXPIRED" if "expired" in str(exc).lower() else "STALE_FENCING_TOKEN"
        return _lease_error(409, code, str(exc), task_id, "execution", False, "reconcile_task_owner")
    except LeaseUnavailable:
        return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Task ownership could not be revalidated.", task_id, "lease_validation", True, "retry_after_coordinator_health_check")
    except CheckpointError as exc:
        detail = _safe_exception_detail(exc)
        error_code = "CHECKPOINT_INVALID" if any(term in detail.lower() for term in ("checksum", "schema", "read-back did not match", "invalid")) else "DRIVE_UNAVAILABLE"
        return _lease_error(503, error_code, detail, task_id, "checkpoint_write", True, "inspect_checkpoint_storage")
    except RuntimeError as exc:
        if str(exc) == AI_PACKAGES_WAITING_MESSAGE:
            logger.warning("execute_status task_id=%s execution_status=not_ready status=503 stage=dependency_load", task_id)
            return _lease_error(503, "SERVER_NOT_READY", AI_PACKAGES_WAITING_MESSAGE, task_id, "dependency_load", True, "retry_after_backend_readiness")
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        logger.error("execute_status task_id=%s execution_status=failed status=500 stage=execution exception_class=%s", task_id, type(exc).__name__)
        label = "OpenSwarm execution" if openswarm_requested else "Playwright browser execution"
        return _lease_error(500, "EXECUTION_FAILED", f"{label} failed: {_safe_exception_detail(exc)}", task_id, "browser_execution" if not openswarm_requested else "openswarm_execution", False, "inspect_execution_receipt")
    except Exception as exc:
        logger.exception("Agent execution failed for task_id=%s", request.task_id)
        logger.error("execute_status task_id=%s execution_status=failed status=500 stage=execution exception_class=%s", task_id, type(exc).__name__)
        label = "OpenSwarm execution" if openswarm_requested else "Playwright browser execution"
        return _lease_error(500, "EXECUTION_FAILED", f"{label} failed: {_safe_exception_detail(exc)}", task_id, "browser_execution" if not openswarm_requested else "openswarm_execution", False, "inspect_execution_receipt")
    if not isinstance(result, str) or not result.strip():
        logger.error("execute_status task_id=%s execution_status=empty_result status=502 stage=result_validation", task_id)
        return _lease_error(502, "EXECUTION_FAILED", "Agent returned no result; no success was recorded.", task_id, "result_validation", False, "inspect_execution_receipt")
    logger.info("execute_status task_id=%s execution_status=success status=200", task_id)
    return {"result": result}


@app.get("/")
def root() -> dict[str, str]:
    return {"service": "USMAN AND UMAR OpenSwarm Task Backend", "status": "ok", "health": "/health"}


@app.get("/health", response_model=None)
async def health() -> dict[str, Any] | JSONResponse:
    now = time.time()
    uptime = max(0.0, now - _START_TIME)
    draining = uptime >= _DRAIN_THRESHOLD_SECONDS
    try:
        coordinator = _get_lease_coordinator()
        await asyncio.to_thread(coordinator.check_ready)
        coordinator_ready = True
    except LeaseUnavailable:
        coordinator_ready = False
    try:
        checkpoint_store = _get_checkpoint_store()
        await asyncio.to_thread(checkpoint_store.readiness_check)
        drive_ready = True
    except CheckpointError:
        drive_ready = False
    active_ids = _task_store.active_tasks()
    ready = coordinator_ready and drive_ready and not draining
    payload = {
        "status": "draining" if draining else ("ready" if ready else "not_ready"),
        "server_id": _SERVER_ID,
        "instance_id": _INSTANCE_ID,
        "start_time": _START_TIME,
        "current_time": now,
        "uptime_seconds": uptime,
        "maximum_runtime_seconds": _MAX_RUNTIME_SECONDS,
        "remaining_runtime_seconds": max(0, _MAX_RUNTIME_SECONDS - uptime),
        "accepting_new_tasks": ready,
        "draining": draining,
        "drive_ready": drive_ready,
        "checkpoint_storage_ready": drive_ready,
        "lease_coordinator_ready": coordinator_ready,
        "active_task_count": len(active_ids),
        "active_task_ids": active_ids,
    }
    return payload if ready else JSONResponse(status_code=503, content=payload)

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


@app.get("/tasks/{task_id}", response_model=None)
async def get_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    try:
        local = _task_store.get(task_id)
    except KeyError as exc:
        local = None
    try:
        coordinator = _get_lease_coordinator()
        distributed = await asyncio.to_thread(coordinator.get_task_state, task_id)
        lease = await asyncio.to_thread(coordinator.get_lease, task_id)
    except LeaseUnavailable:
        if local is not None:
            local["distributed_state_available"] = False
            return local
        return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Cross-instance task state cannot be verified.", task_id, "task_status", True, "retry_after_coordinator_health_check")
    if local is None and distributed is None and lease is None:
        raise HTTPException(status_code=404, detail=f"Unknown task: {task_id}")
    response = dict(local or {})
    if distributed or lease:
        distributed = distributed or {}
        response.update({
            "task_id": task_id,
            "state": distributed.get("task_status", response.get("state", "RUNNING" if lease and lease.status == "ACTIVE" else "UNKNOWN")),
            "server_id": distributed.get("server_id", lease.owner_server_id if lease else None),
            "server_instance_id": distributed.get("server_instance_id", lease.owner_instance_id if lease else None),
            "lease_version": lease.lease_version if lease else distributed.get("lease_version"),
            "lease_expiry": lease.lease_expiry if lease else None,
            "last_heartbeat": lease.last_heartbeat if lease else None,
            "lease_status": lease.status if lease else "NONE",
            "checkpoint_version": distributed.get("checkpoint_version"),
            "checkpoint_file_id": distributed.get("checkpoint_file_id"),
            "distributed_state_available": True,
        })
        if local is None:
            response["checkpoint_receipts"] = []
            if distributed.get("checkpoint_file_id"):
                try:
                    store = _get_checkpoint_store()
                    checkpoint = await asyncio.to_thread(store.download,
                        distributed["checkpoint_file_id"], expected_sha256=distributed.get("checkpoint_sha256"))
                    response["checkpoint_receipts"] = checkpoint.get("execution_receipts", [])
                except CheckpointError:
                    response["checkpoint_available"] = False
    return response


@app.get("/tasks/{task_id}/logs")
def get_task_logs(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any]:
    _require_bearer_token(authorization)
    try:
        _task_store.get(task_id)
        return {"events": _task_store.events(task_id)}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc





@app.post("/tasks/{task_id}/pause", response_model=None)
async def pause_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    try:
        coordinator = _get_lease_coordinator()
        lease = await asyncio.to_thread(coordinator.get_lease, task_id)
        if lease is None or lease.status != "ACTIVE" or lease.lease_expiry <= time.time():
            raise HTTPException(status_code=409, detail="No active owner can acknowledge this pause request.")
        try:
            task = _task_store.get(task_id)
            state = task["state"]
        except KeyError:
            task = None
            distributed = await asyncio.to_thread(coordinator.get_task_state, task_id)
            state = (distributed or {}).get("task_status", "RUNNING")
        if state in {"PAUSE_REQUESTED", "PAUSED"}:
            return task or {"task_id": task_id, "state": state, "server_id": lease.owner_server_id,
                            "server_instance_id": lease.owner_instance_id}
        if state != "RUNNING":
            raise HTTPException(status_code=409, detail=f"Only a RUNNING task can be paused; current state is {state}.")
        control = await asyncio.to_thread(coordinator.request_control, task_id, "PAUSE")
        if task is not None:
            return _task_store.transition(task_id, "PAUSE_REQUESTED", "user_requested_pause", control)
        return {"task_id": task_id, "state": "PAUSE_REQUESTED", "server_id": lease.owner_server_id,
                "server_instance_id": lease.owner_instance_id, **control}
    except LeaseUnavailable:
        return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Pause could not be safely recorded.", task_id, "pause", True, "retry_after_coordinator_health_check")


@app.post("/tasks/{task_id}/cancel", response_model=None)
async def cancel_task(task_id: str, authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    cancellable = {"PENDING", "PLANNING", "READY", "RUNNING", "VERIFYING",
                   "WAITING_FOR_USER", "PAUSE_REQUESTED", "PAUSED", "RETRYING"}
    try:
        coordinator = _get_lease_coordinator()
        lease = await asyncio.to_thread(coordinator.get_lease, task_id)
        if lease is None or lease.status != "ACTIVE" or lease.lease_expiry <= time.time():
            raise HTTPException(status_code=409, detail="No active owner can acknowledge this cancel request.")
        try:
            task = _task_store.get(task_id)
            state = task["state"]
        except KeyError:
            task = None
            distributed = await asyncio.to_thread(coordinator.get_task_state, task_id)
            state = (distributed or {}).get("task_status", "RUNNING")
        if state not in cancellable:
            raise HTTPException(status_code=409, detail=f"Task cannot be cancelled from {state}.")
        control = await asyncio.to_thread(coordinator.request_control, task_id, "CANCEL")
        if task is not None:
            return _task_store.transition(task_id, "CANCEL_REQUESTED", "user_requested_cancel", control)
        return {"task_id": task_id, "state": "CANCEL_REQUESTED", "server_id": lease.owner_server_id,
                "server_instance_id": lease.owner_instance_id, **control}
    except LeaseUnavailable:
        return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Cancel could not be safely recorded.", task_id, "cancel", True, "retry_after_coordinator_health_check")


@app.post("/tasks/{task_id}/resume", response_model=None)
async def resume_task(task_id: str, http_request: Request,
                     authorization: str | None = Header(default=None, alias="Authorization")) -> dict[str, Any] | JSONResponse:
    _require_bearer_token(authorization)
    try:
        task = _task_store.get(task_id)
    except KeyError:
        try:
            coordinator = _get_lease_coordinator()
            task_state = await asyncio.to_thread(coordinator.get_task_state, task_id)
            if not task_state or not task_state.get("checkpoint_file_id"):
                raise HTTPException(status_code=404, detail=f"No durable checkpoint is available for task {task_id}.")
            saved_status = str(task_state.get("task_status", "")).upper()
            if saved_status in {"COMPLETED", "FAILED", "CANCELLED", "REJECTED"}:
                raise HTTPException(status_code=409, detail=f"A {saved_status} task cannot be resumed.")
            if saved_status == "WAITING_FOR_USER":
                raise HTTPException(status_code=409, detail="This task requires explicit approval before it can resume.")
            if not task_state.get("checkpoint_sha256") or not task_state.get("checkpoint_version"):
                raise HTTPException(status_code=409, detail="The authoritative checkpoint pointer lacks version/checksum metadata.")
            checkpoint_store = _get_checkpoint_store()
            checkpoint = await asyncio.to_thread(
                checkpoint_store.download, task_state["checkpoint_file_id"],
                expected_sha256=task_state.get("checkpoint_sha256"),
            )
            if checkpoint.get("task_id") != task_id or int(checkpoint.get("checkpoint_version", 0)) != int(task_state.get("checkpoint_version", -1)):
                raise HTTPException(status_code=409, detail="Checkpoint pointer/version mismatch; recovery was refused.")
            completed = set(checkpoint.get("completed_steps", []))
            for receipt in checkpoint.get("execution_receipts", []):
                state = str(receipt.get("status", "")).upper()
                if state in {"EXECUTING", "UNKNOWN"} or (state == "CONFIRMED" and receipt.get("step_id") not in completed):
                    return _lease_error(409, "EXECUTION_OUTCOME_UNKNOWN",
                                        "A side-effect outcome is ambiguous; reconcile it before resuming to avoid duplicate execution.",
                                        task_id, "checkpoint_recovery", False, "reconcile_external_effect")
            await asyncio.to_thread(_task_store.restore_from_checkpoint, checkpoint)
            task = _task_store.get(task_id)
        except LeaseUnavailable:
            return _lease_error(503, "COORDINATOR_UNAVAILABLE", "Durable task state is unavailable; recovery was refused.", task_id, "checkpoint_recovery", True, "retry_after_storage_health_check")
        except CheckpointError:
            return _lease_error(503, "CHECKPOINT_UNAVAILABLE", "The Drive checkpoint could not be verified; recovery was refused.", task_id, "checkpoint_recovery", True, "inspect_checkpoint_storage")
    if task["state"] != "PAUSED":
        raise HTTPException(status_code=409, detail=f"Only a PAUSED task can be resumed; current state is {task['state']}.")
    saved_result = task.get("result") or {}
    request = ExecuteRequest(task_id=task_id, prompt=task["prompt"], plugins=["openswarm_agent"],
                            approved_step_ids=saved_result.get("approved_step_ids", []))
    return await _execute_impl(request, http_request, authorization)


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
