"""Drive-only OpenSwarm lifecycle integration; all ownership is best-effort, never fenced."""
from __future__ import annotations

import asyncio
import hashlib
import io
import json
import os
import socket
import threading
import time
import uuid
from dataclasses import replace
from typing import Any

from googleapiclient.http import MediaIoBaseUpload

from drive_task_lease import (
    CheckpointIntegrityError, DriveTaskLeaseCoordinator, LeaseConflict,
    LeaseUnavailable, TaskLease,
)

DEFAULT_FOLDER_NAME = "USMAN_UMAR_CHECKPOINTS"
DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"


class DriveRuntimeError(RuntimeError):
    def __init__(self, code: str, message: str, *, recovery_state: str | None = None):
        super().__init__(message)
        self.code = code
        self.recovery_state = recovery_state


def _build_drive_service() -> Any:
    """Build Drive API v3 from ADC (works with Colab auth or configured ADC)."""
    try:
        import google.auth
        from googleapiclient.discovery import build
    except ImportError as exc:
        raise LeaseUnavailable("Google Drive client libraries are not installed") from exc
    credentials, _ = google.auth.default(scopes=[DRIVE_SCOPE])
    return build("drive", "v3", credentials=credentials, cache_discovery=False)


def _resolve_folder(service: Any, folder_id: str | None) -> str:
    if folder_id:
        return folder_id
    # The standard folder name is discovered, not created implicitly. This avoids
    # surprising Drive writes during readiness checks; admins may set its ID.
    response = service.files().list(
        q="name = 'USMAN_UMAR_CHECKPOINTS' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
        spaces="drive", pageSize=100, fields="files(id,name),nextPageToken",
    ).execute()
    files = response.get("files", [])
    if response.get("nextPageToken") or len(files) != 1 or not files[0].get("id"):
        raise LeaseUnavailable("Drive checkpoint folder is missing, duplicated, or ambiguous; set OPENSWARM_DRIVE_FOLDER_ID")
    return str(files[0]["id"])


class DriveTaskExecutionSession:
    """Per-task callbacks guarding local mutations and action boundaries."""
    def __init__(self, runtime: "DriveTaskRuntime", coordinator: DriveTaskLeaseCoordinator,
                 lease: TaskLease, *, task_id: str):
        self.runtime, self.coordinator, self.lease, self.task_id = runtime, coordinator, lease, task_id
        self._last_heartbeat = time.monotonic()
        self._heartbeat_task: asyncio.Task | None = None
        self._stop = asyncio.Event()

    def assert_owner(self) -> None:
        try:
            self.coordinator.assert_owner(self.lease)
        except LeaseConflict as exc:
            raise DriveRuntimeError("STALE_OWNER", str(exc)) from exc
        except LeaseUnavailable as exc:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", str(exc)) from exc

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
        interval = max(1.0, float(self.coordinator.heartbeat_seconds))
        while not self._stop.is_set():
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=interval)
            except asyncio.TimeoutError:
                try:
                    self.lease = await asyncio.to_thread(self.coordinator.renew, self.lease)
                    self._last_heartbeat = time.monotonic()
                    self.runtime.remember(self.lease)
                except LeaseConflict as exc:
                    self._stop.set()
                    self.runtime.forget(self.task_id)
                    return
                except LeaseUnavailable:
                    # A missed heartbeat is not permission to continue external
                    # actions: next boundary's assert_owner fails closed.
                    self._stop.set()
                    self.runtime.forget(self.task_id)
                    return

    async def start(self) -> "DriveTaskExecutionSession":
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
            raise DriveRuntimeError("LEASE_UNAVAILABLE", "Drive heartbeat stopped; execution is blocked")
        self.assert_owner()
        if time.monotonic() - self._last_heartbeat >= self.coordinator.heartbeat_seconds:
            try:
                self.lease = await asyncio.to_thread(self.coordinator.renew, self.lease)
                self._last_heartbeat = time.monotonic()
                self.runtime.remember(self.lease)
            except LeaseConflict as exc:
                raise DriveRuntimeError("STALE_OWNER", str(exc)) from exc
            except LeaseUnavailable as exc:
                raise DriveRuntimeError("DRIVE_UNAVAILABLE", str(exc)) from exc

    async def after_step(self) -> None:
        self.assert_owner()

    def action_key(self, step: dict[str, Any]) -> str:
        return self.coordinator.idempotency_key(self.task_id, str(step["id"]), str(step["id"]))

    def receipt_state(self, step: dict[str, Any]) -> dict[str, Any] | None:
        self.assert_owner()
        key = self.action_key(step)
        matches = [item for item in self.coordinator.list_receipts(self.task_id)
                   if item.get("idempotency_key") == key]
        if len(matches) > 1:
            raise DriveRuntimeError("RECOVERY_BLOCKED", "Duplicate receipt; refusing to repeat action", recovery_state="RECOVERY_BLOCKED")
        return matches[0] if matches else None

    def prepare_action(self, step: dict[str, Any]) -> dict[str, Any] | None:
        receipt = self.receipt_state(step)
        if receipt and receipt.get("state") == "CONFIRMED":
            return receipt
        if receipt and receipt.get("state") in {"UNKNOWN", "EXECUTING", "PREPARED", "PENDING", "RECONCILIATION_REQUIRED", "CANCEL_REQUESTED"}:
            raise DriveRuntimeError("RECOVERY_BLOCKED", "Unresolved action receipt; reconciliation required before retry", recovery_state="RECOVERY_BLOCKED")
        self.assert_owner()
        common = {"details": {"step_id": step["id"], "lease_generation": self.lease.lease_version}}
        try:
            self.coordinator.write_receipt(self.task_id, str(step["id"]), str(step["id"]), "PREPARED", **common)
            self.assert_owner()
            self.coordinator.write_receipt(self.task_id, str(step["id"]), str(step["id"]), "EXECUTING", **common)
            self.assert_owner()
        except LeaseConflict as exc:
            raise DriveRuntimeError("RECOVERY_BLOCKED", str(exc), recovery_state="RECOVERY_BLOCKED") from exc
        except LeaseUnavailable as exc:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", str(exc)) from exc
        return None

    def finish_action(self, step: dict[str, Any], state: str, outcome: dict[str, Any] | None = None) -> None:
        self.assert_owner()
        try:
            self.coordinator.write_receipt(self.task_id, str(step["id"]), str(step["id"]), state,
                details={"step_id": step["id"], "outcome": outcome or {}, "lease_generation": self.lease.lease_version})
            self.assert_owner()
        except LeaseConflict as exc:
            raise DriveRuntimeError("RECOVERY_BLOCKED", str(exc), recovery_state="RECOVERY_BLOCKED") from exc
        except LeaseUnavailable as exc:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", str(exc)) from exc

    @staticmethod
    def uncertain_action_error(step: dict[str, Any], exc: BaseException) -> DriveRuntimeError:
        return DriveRuntimeError("EXECUTION_OUTCOME_UNKNOWN",
            f"Critical step {step.get('id', 'unknown')} may have taken effect; automatic replay is blocked: {type(exc).__name__}",
            recovery_state="RECOVERY_BLOCKED")

    def save_checkpoint(self, *, plan: list[dict[str, Any]], completed: dict[str, Any],
                        pending: dict[str, Any], results: list[dict[str, Any]], task_state: dict[str, Any]) -> None:
        self.assert_owner()
        current = self.coordinator.load_checkpoint(
            self.task_id, self.lease.checkpoint_id, self.lease.checkpoint_version,
            expected_owner=None,
        ) if self.lease.checkpoint_id and self.lease.checkpoint_version else None
        lineage = list((current or {}).get("payload", {}).get("checkpoint_lineage", []))
        payload = {"plan": plan, "completed_steps": completed, "pending_steps": pending,
            "results": results, "task_state": {"state": task_state.get("state"), "result": task_state.get("result")},
            "checkpoint_lineage": lineage + ([{"checkpoint_id": self.lease.checkpoint_id,
                "checkpoint_version": self.lease.checkpoint_version}] if self.lease.checkpoint_id else []),
            "lease_generation": self.lease.lease_version}
        try:
            doc = self.coordinator.save_checkpoint(self.task_id, payload, owner=self.lease,
                checkpoint_version=(self.lease.checkpoint_version or 0) + 1)
            self.lease = replace(self.lease, checkpoint_id=doc["checkpoint_id"],
                                 checkpoint_version=doc["checkpoint_version"])
            self.runtime.remember(self.lease)
        except LeaseConflict as exc:
            raise DriveRuntimeError("CHECKPOINT_CONFLICT", str(exc)) from exc
        except (LeaseUnavailable, CheckpointIntegrityError) as exc:
            raise DriveRuntimeError("CHECKPOINT_UNAVAILABLE", str(exc)) from exc


class DriveTaskRuntime:
    """Lazy ADC-backed coordinator plus process identity and injectable clients."""
    def __init__(self, *, service_factory: Any = _build_drive_service,
                 coordinator_factory: Any = DriveTaskLeaseCoordinator,
                 folder_id: str | None = None, server_id: str | None = None,
                 instance_id: str | None = None, clock: Any = time.time):
        self.server_id = server_id or os.getenv("OPENSWARM_SERVER_ID") or socket.gethostname()
        self.instance_id = instance_id or os.getenv("OPENSWARM_INSTANCE_ID") or str(uuid.uuid4())
        os.environ.setdefault("OPENSWARM_INSTANCE_ID", self.instance_id)
        self.folder_id = folder_id or os.getenv("OPENSWARM_DRIVE_FOLDER_ID")
        self.service_factory, self.coordinator_factory, self.clock = service_factory, coordinator_factory, clock
        self._coordinator = None
        self._error: str | None = None
        self._lock = threading.RLock()
        self._leases: dict[str, TaskLease] = {}
        self.started_monotonic = time.monotonic()
        self.drain_after_seconds = float(os.getenv("OPENSWARM_DRAIN_AFTER_SECONDS", "42300"))

    @property
    def draining(self) -> bool:
        return time.monotonic() - self.started_monotonic >= self.drain_after_seconds

    def _get_coordinator(self) -> DriveTaskLeaseCoordinator:
        with self._lock:
            if self._coordinator is not None:
                return self._coordinator
            try:
                service = self.service_factory()
                folder_id = _resolve_folder(service, self.folder_id)
                self._coordinator = self.coordinator_factory(service, folder_id,
                    self.server_id, self.instance_id)
                self._error = None
                return self._coordinator
            except Exception as exc:
                self._error = " ".join(str(exc).split())[:300]
                raise LeaseUnavailable(self._error) from exc

    def probe(self) -> dict[str, Any]:
        probe_file_id = None
        try:
            coordinator = self._get_coordinator()
            files = coordinator.service.files()
            folder = files.get(fileId=coordinator.folder_id,
                fields="id,name,mimeType,trashed").execute()
            if folder.get("mimeType") != "application/vnd.google-apps.folder" or folder.get("trashed") is True:
                raise LeaseUnavailable("Configured Drive checkpoint folder is invalid or trashed")
            files.list(q=f"'{coordinator.folder_id}' in parents and trashed = false",
                spaces="drive", pageSize=1, fields="files(id)").execute()

            # A read-only list does not prove that the runtime can persist a
            # checkpoint or lease. Round-trip a tiny application/json file
            # using the same Drive content API and remove it before readiness.
            nonce = uuid.uuid4().hex
            name = f".openswarm_readiness_{nonce}.json"
            document = {"purpose": "openswarm_readiness", "nonce": nonce}
            payload = json.dumps(document, sort_keys=True, separators=(",", ":")).encode("utf-8")
            created = files.create(
                body={"name": name, "mimeType": "application/json", "parents": [coordinator.folder_id]},
                media_body=MediaIoBaseUpload(io.BytesIO(payload), mimetype="application/json", resumable=False),
                fields="id,name",
            ).execute()
            probe_file_id = created.get("id")
            if not probe_file_id:
                raise LeaseUnavailable("Drive did not return a readiness probe file ID")
            readback = files.get_media(fileId=probe_file_id).execute()
            if not isinstance(readback, (bytes, bytearray)) or bytes(readback) != payload:
                raise LeaseUnavailable("Drive checkpoint JSON write/read-back verification failed")
            if hashlib.sha256(bytes(readback)).digest() != hashlib.sha256(payload).digest():
                raise LeaseUnavailable("Drive checkpoint JSON checksum verification failed")
            files.delete(fileId=probe_file_id).execute()
            probe_file_id = None
            self._error = None
            return {"ready": True, "drive_ready": True, "checkpoint_storage_ready": True,
                    "lease_coordinator_ready": True, "detail": "Drive JSON content write/read-back succeeded"}
        except Exception as exc:
            self._error = " ".join(str(exc).split())[:300]
            return {"ready": False, "drive_ready": False, "checkpoint_storage_ready": False,
                    "lease_coordinator_ready": False, "detail": self._error}
        finally:
            if probe_file_id:
                try:
                    coordinator.service.files().delete(fileId=probe_file_id).execute()
                except Exception as exc:
                    self._error = "Drive readiness probe cleanup failed; persistence readiness is uncertain"
                    raise LeaseUnavailable(self._error) from exc

    def health(self, active_count: int) -> dict[str, Any]:
        drive = self.probe()
        auth_ready = bool(os.getenv("APP_CLIENT_TOKEN", "").strip())
        ready = bool(drive["ready"] and auth_ready)
        recovery_state = "LEASE_ACTIVE" if ready else "RECOVERY_BLOCKED"
        recovery_reason = None if ready else (drive["detail"] if not drive["ready"] else "APP_CLIENT_TOKEN is not configured")
        accepting = ready and not self.draining
        return {"status": "ready" if ready else "not_ready", "ready": ready,
            "auth_ready": auth_ready, "checkpoints_ready": drive["ready"],
            "drive_ready": drive["ready"], "checkpoint_storage_ready": drive["ready"],
            "lease_coordinator_ready": drive["ready"], "lease_ready": drive["ready"],
            "drive_detail": drive["detail"], "recovery_state": recovery_state,
            "recovery_reason": recovery_reason, "server_id": self.server_id,
            "instance_id": self.instance_id, "uptime_seconds": int(time.monotonic() - self.started_monotonic),
            "accepting_new_tasks": accepting, "draining": self.draining,
            "active_count": int(active_count), "active_tasks": int(active_count),
            "drain_after_seconds": self.drain_after_seconds,
            "lease_policy": DriveTaskLeaseCoordinator.describe_limitations()}

    def remember(self, lease: TaskLease) -> None:
        with self._lock:
            self._leases[lease.task_id] = lease

    def forget(self, task_id: str) -> None:
        with self._lock:
            self._leases.pop(task_id, None)

    def acquire(self, task_id: str) -> DriveTaskExecutionSession:
        status = self.probe()
        if not status["ready"]:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", "Drive is unavailable; OpenSwarm work is disabled")
        coordinator = self._get_coordinator()
        lease = self._leases.get(task_id)
        if lease:
            try:
                coordinator.assert_owner(lease)
                return DriveTaskExecutionSession(self, coordinator, lease, task_id=task_id)
            except (LeaseConflict, LeaseUnavailable):
                self.forget(task_id)
        try:
            lease = coordinator.acquire(task_id)
        except LeaseConflict:
            recovery = coordinator.recover(task_id, wait=False)
            if recovery.state == "RECOVERY_COMPLETED" and recovery.lease:
                lease = recovery.lease
            else:
                raise DriveRuntimeError("LEASE_CONFLICT", recovery.detail or "Existing Drive lease cannot safely be acquired",
                                        recovery_state=recovery.state)
        except LeaseUnavailable as exc:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", str(exc)) from exc
        self.remember(lease)
        session = DriveTaskExecutionSession(self, coordinator, lease, task_id=task_id)
        if 'recovery' in locals() and recovery.checkpoint:
            session.recovered_checkpoint = recovery.checkpoint
        return session

    def assert_task_owner(self, task_id: str) -> None:
        lease = self._leases.get(task_id)
        if lease is None:
            raise DriveRuntimeError("STALE_OWNER", "This instance has no validated task lease")
        try:
            self._get_coordinator().assert_owner(lease)
        except LeaseConflict as exc:
            raise DriveRuntimeError("STALE_OWNER", str(exc)) from exc
        except LeaseUnavailable as exc:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", str(exc)) from exc

    def recover_checkpoint(self, task_id: str) -> dict[str, Any]:
        status = self.probe()
        if not status["ready"]:
            raise DriveRuntimeError("DRIVE_UNAVAILABLE", "Drive unavailable during recovery")
        result = self._get_coordinator().recover(task_id, wait=False)
        if result.state != "RECOVERY_COMPLETED" or not result.lease or not result.checkpoint:
            raise DriveRuntimeError("RECOVERY_BLOCKED", result.detail or "No validated resumable checkpoint",
                                    recovery_state=result.state)
        self.remember(result.lease)
        return result.checkpoint
