"""Upstash Redis REST persistence for task state, checkpoints, receipts, and leases.

All task data is stored as JSON under ``task_{task_id}`` via REST SET/GET.
Lease read/modify/write sequences use a process-local lock only. Plain Upstash
SET/GET does not provide cross-process compare-and-swap, so this adapter cannot
claim exclusive ownership or fencing across simultaneous workers.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable

import requests

from task_state import TASK_STATES, _ALLOWED, TaskStateStore

_MUTATION_LOCK = threading.RLock()
_UNRESOLVED_RECEIPTS = {"PREPARED", "EXECUTING", "UNKNOWN", "PENDING", "CANCEL_REQUESTED", "RECONCILIATION_REQUIRED"}


def _validate_checkpoint(checkpoint: dict[str, Any]) -> None:
    if (not isinstance(checkpoint, dict) or checkpoint.get("schema") != "redis-task-checkpoint"
            or checkpoint.get("schema_version") != 1 or not isinstance(checkpoint.get("payload"), dict)):
        raise RedisStorageError("Redis checkpoint schema is invalid")
    checksum = checkpoint.get("checksum")
    body = {key: value for key, value in checkpoint.items() if key != "checksum"}
    canonical = json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    expected = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    if not isinstance(checksum, str) or not hmac.compare_digest(checksum, expected):
        raise RedisStorageError("Redis checkpoint checksum validation failed")


class RedisStorageError(RuntimeError):
    """Upstash REST configuration, transport, or response failure."""


class RedisLeaseConflict(RedisStorageError):
    """A lease is active, changed, expired, or otherwise unsafe to acquire."""


class RedisTaskStorage:
    """Small Upstash REST client; sends Redis commands as JSON arrays over HTTPS."""
    def __init__(self, url: str | None = None, token: str | None = None, *,
                 http: Any = requests, timeout: float = 10.0) -> None:
        self.url = (url if url is not None else os.getenv("UPSTASH_REDIS_REST_URL", "")).strip().rstrip("/")
        self.token = (token if token is not None else os.getenv("UPSTASH_REDIS_REST_TOKEN", "")).strip()
        self.http = http
        self.timeout = timeout

    @property
    def configured(self) -> bool:
        return self.url.startswith("https://") and bool(self.token)

    @staticmethod
    def redis_key(task_id: str) -> str:
        value = str(task_id or "")
        if not value or len(value) > 128 or any(ch in value for ch in "\r\n\x00"):
            raise ValueError("task_id must contain 1–128 characters and no control characters")
        return f"task_{value}"

    def command(self, *parts: Any) -> Any:
        if not self.configured:
            raise RedisStorageError("UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN are required")
        try:
            response = self.http.post(self.url, json=list(parts), headers={
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
            }, timeout=self.timeout)
            response.raise_for_status()
            payload = response.json()
        except Exception as exc:
            raise RedisStorageError(f"Upstash Redis REST request failed ({type(exc).__name__})") from exc
        if not isinstance(payload, dict) or payload.get("error"):
            error = payload.get("error") if isinstance(payload, dict) else "malformed response"
            raise RedisStorageError(f"Upstash Redis REST returned an error: {str(error)[:200]}")
        return payload.get("result")

    def get(self, task_id: str) -> dict[str, Any] | None:
        result = self.command("GET", self.redis_key(task_id))
        if result is None:
            return None
        try:
            document = json.loads(result) if isinstance(result, str) else result
        except (TypeError, json.JSONDecodeError) as exc:
            raise RedisStorageError("Stored Redis task JSON is invalid") from exc
        if not isinstance(document, dict):
            raise RedisStorageError("Stored Redis task value must be a JSON object")
        return document

    def set(self, task_id: str, document: dict[str, Any]) -> None:
        if not isinstance(document, dict):
            raise ValueError("Redis task document must be an object")
        encoded = json.dumps(document, ensure_ascii=False, separators=(",", ":"))
        result = self.command("SET", self.redis_key(task_id), encoded)
        if result not in ("OK", True):
            raise RedisStorageError("Upstash Redis did not confirm SET")

    def delete(self, task_id: str) -> None:
        self.command("DEL", self.redis_key(task_id))

    def mutate(self, task_id: str, update: Callable[[dict[str, Any]], Any], *,
               create: bool = False) -> tuple[dict[str, Any], Any]:
        """Process-local serialized GET/SET update; not atomic across runtimes."""
        with _MUTATION_LOCK:
            document = self.get(task_id)
            if document is None:
                if not create:
                    raise KeyError(f"Unknown task: {task_id}")
                document = {"task_id": task_id, "events": [], "runtime": {}}
            result = update(document)
            self.set(task_id, document)
            return document, result

    def probe(self) -> bool:
        probe_id = f"__health_{uuid.uuid4().hex}"
        key = self.redis_key(probe_id)
        payload = json.dumps({"purpose": "readiness", "nonce": uuid.uuid4().hex})
        result = self.command("SET", key, payload)
        if result != "OK" or self.command("GET", key) != payload:
            raise RedisStorageError("Upstash SET/GET readiness round-trip failed")
        self.command("DEL", key)
        return True


class RedisTaskStateStore(TaskStateStore):
    """TaskStateStore-compatible adapter; agent memory remains in local SQLite."""
    def __init__(self, storage: RedisTaskStorage | None = None, path: str | None = None) -> None:
        super().__init__(path)
        self.redis = storage or RedisTaskStorage()

    @staticmethod
    def _require_state(document: dict[str, Any], task_id: str) -> dict[str, Any]:
        if not document or "state" not in document:
            raise KeyError(f"Unknown task: {task_id}")
        return document

    def create(self, task_id: str, prompt: str) -> dict[str, Any]:
        now = time.time()
        def update(doc: dict[str, Any]) -> dict[str, Any]:
            if "state" in doc:
                raise ValueError(f"Task already exists: {task_id}")
            doc.update({"task_id": task_id, "prompt": prompt, "state": "PENDING",
                        "plan": [], "result": None, "created_at": now, "updated_at": now})
            doc.setdefault("events", []).append({"event": "task_created", "from_state": None,
                "to_state": "PENDING", "detail": {}, "created_at": now})
            doc.setdefault("runtime", {})
            return doc
        self.redis.mutate(task_id, update, create=True)
        return self.get(task_id)

    def get(self, task_id: str) -> dict[str, Any]:
        doc = self._require_state(self.redis.get(task_id) or {}, task_id)
        return {k: doc.get(k) for k in ("task_id", "prompt", "state", "plan", "result", "created_at", "updated_at")}

    def transition(self, task_id: str, state: str, event: str, detail: dict[str, Any] | None = None) -> dict[str, Any]:
        if state not in TASK_STATES:
            raise ValueError(f"Unknown task state: {state}")
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            self._require_state(doc, task_id)
            old = doc["state"]
            if state not in _ALLOWED[old]:
                raise ValueError(f"Invalid task transition: {old} -> {state}")
            doc.update(state=state, updated_at=now)
            doc.setdefault("events", []).append({"event": event, "from_state": old,
                "to_state": state, "detail": detail or {}, "created_at": now})
        self.redis.mutate(task_id, update)
        return self.get(task_id)

    def append_event(self, task_id: str, event: str, detail: dict[str, Any] | None = None) -> None:
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            self._require_state(doc, task_id)
            doc.setdefault("events", []).append({"event": event, "from_state": None,
                "to_state": None, "detail": detail or {}, "created_at": now})
        self.redis.mutate(task_id, update)

    def save_plan(self, task_id: str, plan: list[dict[str, Any]]) -> None:
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            self._require_state(doc, task_id)
            doc.update(plan=plan, updated_at=now)
            doc.setdefault("events", []).append({"event": "plan_saved", "from_state": None,
                "to_state": None, "detail": {"steps": len(plan)}, "created_at": now})
        self.redis.mutate(task_id, update)

    def save_result(self, task_id: str, result: dict[str, Any]) -> None:
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            self._require_state(doc, task_id)
            doc.update(result=result, updated_at=now)
            doc.setdefault("events", []).append({"event": "result_saved", "from_state": None,
                "to_state": None, "detail": {"verified": result.get("verified", False)}, "created_at": now})
        self.redis.mutate(task_id, update)

    def events(self, task_id: str) -> list[dict[str, Any]]:
        doc = self._require_state(self.redis.get(task_id) or {}, task_id)
        return [{"id": i + 1, "task_id": task_id, **event} for i, event in enumerate(doc.get("events", []))]


@dataclass(frozen=True)
class RedisTaskLease:
    task_id: str
    owner_server_id: str
    owner_instance_id: str
    lease_version: int
    lease_expiry: float
    ownership_status: str = "ACTIVE"
    checkpoint_id: str | None = None
    checkpoint_version: int | None = None


class RedisTaskExecutionSession:
    def __init__(self, runtime: "RedisTaskRuntime", lease: RedisTaskLease,
                 *, recovered_checkpoint: dict[str, Any] | None = None) -> None:
        self.runtime, self.lease = runtime, lease
        self.task_id = lease.task_id
        self.recovered_checkpoint = recovered_checkpoint
        self._last_heartbeat = time.monotonic()
        self._stop = asyncio.Event()
        self._heartbeat_task = None

    def assert_owner(self) -> None:
        self.runtime.assert_owner(self.lease)

    def guarded_store(self, store: Any) -> Any:
        session = self
        mutators = {"create", "transition", "append_event", "save_plan", "save_result", "memory_set"}
        class Guard:
            def __getattr__(self, name: str) -> Any:
                value = getattr(store, name)
                if name not in mutators:
                    return value
                def guarded(*args: Any, **kwargs: Any) -> Any:
                    session.assert_owner()
                    result = value(*args, **kwargs)
                    session.assert_owner()
                    return result
                return guarded
        return Guard()

    async def _heartbeat_loop(self) -> None:
        while not self._stop.is_set():
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=self.runtime.heartbeat_seconds)
            except asyncio.TimeoutError:
                try:
                    self.lease = self.runtime.renew(self.lease)
                    self._last_heartbeat = time.monotonic()
                except RedisStorageError:
                    self._stop.set()

    async def start(self) -> "RedisTaskExecutionSession":
        self.assert_owner()
        self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())
        return self

    async def close(self) -> None:
        self._stop.set()
        if self._heartbeat_task:
            await self._heartbeat_task

    async def before_step(self) -> None:
        if self._stop.is_set():
            self.assert_owner()
            raise RedisStorageError("Redis lease heartbeat stopped; execution blocked")
        self.assert_owner()
        if time.monotonic() - self._last_heartbeat >= self.runtime.heartbeat_seconds:
            self.lease = self.runtime.renew(self.lease)
            self._last_heartbeat = time.monotonic()

    async def after_step(self) -> None:
        self.assert_owner()

    @staticmethod
    def action_key(task_id: str, step_name: str, logical_action_id: str) -> str:
        return hashlib.sha256("\x00".join((task_id, step_name, logical_action_id)).encode()).hexdigest()

    def _receipts(self) -> dict[str, Any]:
        doc = self.runtime.storage.get(self.task_id) or {}
        return (doc.get("runtime") or {}).get("receipts") or {}

    def receipt_state(self, step: dict[str, Any]) -> dict[str, Any] | None:
        self.assert_owner()
        return self._receipts().get(self.action_key(self.task_id, str(step["id"]), str(step["id"])))

    def _write_receipt(self, step: dict[str, Any], state: str, details: dict[str, Any] | None = None) -> None:
        key = self.action_key(self.task_id, str(step["id"]), str(step["id"]))
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            runtime = doc.setdefault("runtime", {})
            receipts = runtime.setdefault("receipts", {})
            prior = receipts.get(key)
            if prior and prior.get("state") == "CONFIRMED" and state != "CONFIRMED":
                return
            receipts[key] = {"task_id": self.task_id, "step_name": str(step["id"]),
                "logical_action_id": str(step["id"]), "idempotency_key": key,
                "state": state, "updated_at": now, "details": details or {}}
        self.runtime.storage.mutate(self.task_id, update, create=True)
        self.assert_owner()

    def prepare_action(self, step: dict[str, Any]) -> dict[str, Any] | None:
        prior = self.receipt_state(step)
        if prior and prior.get("state") == "CONFIRMED":
            return prior
        if prior and prior.get("state") in _UNRESOLVED_RECEIPTS:
            raise RedisStorageError("Unresolved action receipt; reconciliation required before retry")
        self._write_receipt(step, "PREPARED", {"lease_generation": self.lease.lease_version})
        self._write_receipt(step, "EXECUTING", {"lease_generation": self.lease.lease_version})
        return None

    def finish_action(self, step: dict[str, Any], state: str, outcome: dict[str, Any] | None = None) -> None:
        self._write_receipt(step, state, {"outcome": outcome or {}, "lease_generation": self.lease.lease_version})

    @staticmethod
    def uncertain_action_error(step: dict[str, Any], exc: BaseException) -> RedisStorageError:
        return RedisStorageError(f"Critical step {step.get('id', 'unknown')} may have taken effect; automatic replay blocked: {type(exc).__name__}")

    def save_checkpoint(self, *, plan: list[dict[str, Any]], completed: dict[str, Any],
                        pending: dict[str, Any], results: list[dict[str, Any]], task_state: dict[str, Any]) -> None:
        self.assert_owner()
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            runtime = doc.setdefault("runtime", {})
            previous = runtime.get("checkpoint") or {}
            version = int(previous.get("checkpoint_version", 0)) + 1
            checkpoint_id = uuid.uuid4().hex
            checkpoint = {"schema": "redis-task-checkpoint", "schema_version": 1,
                "checkpoint_id": checkpoint_id, "checkpoint_version": version,
                "created_at": now, "lease_version": self.lease.lease_version,
                "payload": {"plan": plan, "completed_steps": completed, "pending_steps": pending,
                    "results": results, "task_state": {"state": task_state.get("state"),
                    "result": task_state.get("result")}}}
            canonical = json.dumps(checkpoint, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            checkpoint["checksum"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
            runtime["checkpoint"] = checkpoint
            lease = runtime.get("lease") or {}
            lease.update(checkpoint_id=checkpoint_id, checkpoint_version=version)
            runtime["lease"] = lease
        self.runtime.storage.mutate(self.task_id, update)
        self.lease = self.runtime.current_lease(self.task_id)


class RedisTaskRuntime:
    """Upstash-backed task lease/checkpoint service; SET/GET ownership is best-effort."""
    def __init__(self, storage: RedisTaskStorage | None = None, *, server_id: str | None = None,
                 instance_id: str | None = None, lease_seconds: float | None = None,
                 heartbeat_seconds: float | None = None) -> None:
        self.storage = storage or RedisTaskStorage()
        self.server_id = server_id or os.getenv("OPENSWARM_SERVER_ID") or socket.gethostname()
        self.instance_id = instance_id or os.getenv("OPENSWARM_INSTANCE_ID") or str(uuid.uuid4())
        self.lease_seconds = float(lease_seconds or os.getenv("REDIS_TASK_LEASE_SECONDS", "120"))
        self.heartbeat_seconds = float(heartbeat_seconds or os.getenv("REDIS_TASK_HEARTBEAT_SECONDS", "20"))
        self.started_monotonic = time.monotonic()
        self.drain_after_seconds = float(os.getenv("OPENSWARM_DRAIN_AFTER_SECONDS", "42300"))

    @property
    def draining(self) -> bool:
        return time.monotonic() - self.started_monotonic >= self.drain_after_seconds

    @staticmethod
    def _lease_from(document: dict[str, Any], task_id: str) -> RedisTaskLease:
        lease = (document.get("runtime") or {}).get("lease")
        if not isinstance(lease, dict):
            raise RedisLeaseConflict("Task has no Redis lease")
        try:
            return RedisTaskLease(task_id, str(lease["owner_server_id"]), str(lease["owner_instance_id"]),
                int(lease["lease_version"]), float(lease["lease_expiry"]), str(lease.get("status", "ACTIVE")),
                lease.get("checkpoint_id"), int(lease["checkpoint_version"]) if lease.get("checkpoint_version") is not None else None)
        except (KeyError, ValueError, TypeError) as exc:
            raise RedisStorageError("Redis task lease is malformed") from exc

    def current_lease(self, task_id: str) -> RedisTaskLease:
        return self._lease_from(self.storage.get(task_id) or {}, task_id)

    def assert_owner(self, lease: RedisTaskLease) -> None:
        if lease.owner_server_id != self.server_id or lease.owner_instance_id != self.instance_id:
            raise RedisLeaseConflict("Redis lease belongs to a different server instance")
        current = self.current_lease(lease.task_id)
        if (current.owner_server_id != self.server_id or current.owner_instance_id != self.instance_id
                or current.lease_version != lease.lease_version or current.lease_expiry <= time.time()):
            raise RedisLeaseConflict("Redis task lease is expired or ownership changed")

    def assert_task_owner(self, task_id: str) -> None:
        lease = self.current_lease(task_id)
        if lease.lease_expiry <= time.time():
            raise RedisLeaseConflict("Redis task lease has expired")

    def renew(self, lease: RedisTaskLease) -> RedisTaskLease:
        self.assert_owner(lease)
        now = time.time()
        def update(doc: dict[str, Any]) -> None:
            stored = self._lease_from(doc, lease.task_id)
            if stored.lease_version != lease.lease_version or stored.owner_instance_id != self.instance_id:
                raise RedisLeaseConflict("Redis task lease changed during renewal")
            raw = doc["runtime"]["lease"]
            raw.update(last_heartbeat=now, lease_expiry=now + self.lease_seconds)
        self.storage.mutate(lease.task_id, update)
        return self.current_lease(lease.task_id)

    def acquire(self, task_id: str) -> RedisTaskExecutionSession:
        now = time.time()
        recovered = None
        def update(doc: dict[str, Any]) -> None:
            nonlocal recovered
            runtime = doc.setdefault("runtime", {})
            old = runtime.get("lease")
            checkpoint = runtime.get("checkpoint")
            if isinstance(old, dict):
                expiry = float(old.get("lease_expiry", 0))
                same_owner = old.get("owner_instance_id") == self.instance_id
                if expiry > now and not same_owner:
                    raise RedisLeaseConflict("Task already has an active lease; back off")
                if expiry <= now and doc.get("state"):
                    receipts = runtime.get("receipts") or {}
                    blocked = [r for r in receipts.values() if r.get("state") in _UNRESOLVED_RECEIPTS]
                    if blocked or not checkpoint:
                        raise RedisLeaseConflict("Expired task has no safe checkpoint or has unresolved receipts")
                    _validate_checkpoint(checkpoint)
                    recovered = checkpoint
            generation = int(old.get("lease_version", 0)) + 1 if isinstance(old, dict) else 1
            runtime["lease"] = {"owner_server_id": self.server_id, "owner_instance_id": self.instance_id,
                "lease_version": generation, "lease_expiry": now + self.lease_seconds,
                "last_heartbeat": now, "status": "ACTIVE",
                "checkpoint_id": (checkpoint or {}).get("checkpoint_id"),
                "checkpoint_version": (checkpoint or {}).get("checkpoint_version")}
            doc.setdefault("task_id", task_id)
            doc.setdefault("events", [])
        self.storage.mutate(task_id, update, create=True)
        lease = self.current_lease(task_id)
        self.assert_owner(lease)
        return RedisTaskExecutionSession(self, lease, recovered_checkpoint=recovered)

    def probe(self) -> dict[str, Any]:
        try:
            self.storage.probe()
            return {"ready": True, "redis_ready": True, "checkpoint_storage_ready": True,
                    "lease_coordinator_ready": True, "detail": "Upstash Redis REST SET/GET round-trip succeeded"}
        except Exception as exc:
            return {"ready": False, "redis_ready": False, "checkpoint_storage_ready": False,
                    "lease_coordinator_ready": False, "detail": " ".join(str(exc).split())[:300]}

    def health(self, active_count: int) -> dict[str, Any]:
        redis = self.probe()
        auth_ready = bool(os.getenv("APP_CLIENT_TOKEN", "").strip())
        ready = redis["ready"] and auth_ready
        draining = self.draining
        return {"status": "ready" if ready else "not_ready", "ready": ready,
            "auth_ready": auth_ready, "checkpoints_ready": redis["ready"],
            "redis_ready": redis["ready"], "checkpoint_storage_ready": redis["ready"],
            "lease_coordinator_ready": redis["ready"], "lease_ready": redis["ready"],
            "redis_detail": redis["detail"], "recovery_state": "LEASE_ACTIVE" if ready else "RECOVERY_BLOCKED",
            "recovery_reason": None if ready else (redis["detail"] if not redis["ready"] else "APP_CLIENT_TOKEN is not configured"),
            "server_id": self.server_id, "instance_id": self.instance_id,
            "uptime_seconds": int(time.monotonic() - self.started_monotonic),
            "accepting_new_tasks": bool(ready and not draining), "draining": draining,
            "active_count": int(active_count), "active_tasks": int(active_count),
            "drain_after_seconds": self.drain_after_seconds,
            "lease_policy": "Upstash REST GET/SET stores shared state but lease updates are not atomic across runtimes; do not treat as exclusive fencing."}

    def recover_checkpoint(self, task_id: str) -> dict[str, Any]:
        doc = self.storage.get(task_id) or {}
        checkpoint = (doc.get("runtime") or {}).get("checkpoint")
        if not checkpoint:
            raise RedisLeaseConflict("No Redis checkpoint is available for recovery")
        _validate_checkpoint(checkpoint)
        return checkpoint


# Explicit opt-in: configured deployments use Redis; developer tests without
# Upstash secrets retain the local SQLite TaskStateStore, while runtime readiness
# remains not_ready until Upstash is configured.
def build_task_state_store() -> TaskStateStore:
    storage = RedisTaskStorage()
    return RedisTaskStateStore(storage) if storage.configured else TaskStateStore()
