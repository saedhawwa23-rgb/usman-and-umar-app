"""Conservative, best-effort task recovery using Google Drive files only.

Google Drive does not provide unique-name constraints, create-if-absent, or
conditional compare-and-swap for these operations. Preflight, update, and
read-back checks reduce accidental duplicate work but do NOT guarantee
exclusive ownership or fencing against concurrent/stale workers. Any ambiguity
or Drive error fails closed. Drive-only ownership is advisory; strict
exclusivity is unavailable, so callers requiring strict ownership must fail
closed and use a separately chosen ownership mechanism.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import time
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from googleapiclient.http import MediaIoBaseUpload


class LeaseConflict(RuntimeError):
    """A live, changed, duplicate, or otherwise ambiguous lease was observed."""


class LeaseUnavailable(RuntimeError):
    """Drive or its metadata could not be consulted safely."""


class CheckpointIntegrityError(LeaseUnavailable):
    """Checkpoint content, schema, checksum, or generation did not validate."""


LEASE_ACTIVE = "LEASE_ACTIVE"
LEASE_SUSPECT = "LEASE_SUSPECT"
LEASE_EXPIRED = "LEASE_EXPIRED"
RECOVERY_WAITING = "RECOVERY_WAITING"
RECOVERY_VALIDATING = "RECOVERY_VALIDATING"
RECOVERY_BLOCKED = "RECOVERY_BLOCKED"
RECOVERY_READY = "RECOVERY_READY"
RECOVERY_COMPLETED = "RECOVERY_COMPLETED"
RECEIPT_STATES = frozenset({
    "PREPARED", "EXECUTING", "CONFIRMED", "FAILED", "UNKNOWN",
    "CANCEL_REQUESTED", "CANCELLED", "PENDING", "RECONCILIATION_REQUIRED",
})


def _env_number(name: str, default: float, *, minimum: float = 0) -> float:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be numeric") from exc
    if parsed < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return parsed


def _utc_iso(epoch: float) -> str:
    return datetime.fromtimestamp(float(epoch), tz=timezone.utc).isoformat().replace("+00:00", "Z")


def _parse_utc(value: Any, field: str) -> float:
    """Parse UTC ISO-8601 (or legacy numeric epoch) and fail closed otherwise."""
    if value is None or value == "":
        raise LeaseUnavailable(f"Lease timestamp {field} is missing")
    try:
        if isinstance(value, (int, float)):
            result = float(value)
        else:
            text = str(value)
            if text.endswith("Z"):
                text = text[:-1] + "+00:00"
            parsed = datetime.fromisoformat(text)
            if parsed.tzinfo is None or parsed.utcoffset().total_seconds() != 0:
                raise ValueError("timestamp is not explicitly UTC")
            result = parsed.timestamp()
        if not (result > 0 and result < float("inf")):
            raise ValueError("timestamp is not finite and positive")
        return result
    except (TypeError, ValueError, OverflowError, AttributeError) as exc:
        raise LeaseUnavailable(f"Lease timestamp {field} is invalid or not UTC") from exc


def _canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


@dataclass(frozen=True)
class TaskLease:
    # First eight fields preserve the original coordinator API/constructor.
    task_id: str
    owner_server_id: str
    owner_instance_id: str
    lease_version: int
    lease_expiry: float
    ownership_status: str
    drive_file_id: str
    lease_created_at: float = 0.0
    last_heartbeat: float = 0.0
    updated_at: float = 0.0
    checkpoint_id: str | None = None
    checkpoint_version: int | None = None

    @property
    def status(self) -> str:
        return self.ownership_status


@dataclass(frozen=True)
class RecoveryResult:
    state: str
    task_id: str
    lease: TaskLease | None = None
    checkpoint: dict[str, Any] | None = None
    confirmed_receipt_keys: tuple[str, ...] = ()
    blocked_receipt_keys: tuple[str, ...] = ()
    detail: str = ""
    history: tuple[str, ...] = ()


class DriveTaskLeaseCoordinator:
    """Best-effort Drive API v3 lease/checkpoint/receipt adapter.

    ``lease_version`` is a best-effort generation label, NOT a fencing token.
    Clock and sleep are injectable to make recovery grace tests deterministic.
    """
    def __init__(self, service: Any, folder_id: str, server_id: str,
                 instance_id: str, lease_seconds: int | None = None, *,
                 heartbeat_seconds: float | None = None,
                 missed_heartbeat_threshold: int | None = None,
                 recovery_grace_seconds: float | None = None,
                 clock_skew_seconds: float | None = None,
                 clock: Callable[[], float] = time.time,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        if not folder_id or not server_id or not instance_id:
            raise ValueError("Drive folder, server ID, and instance ID are required")
        self.lease_seconds = int(lease_seconds if lease_seconds is not None else _env_number("DRIVE_TASK_LEASE_SECONDS", 120, minimum=1))
        self.heartbeat_seconds = float(heartbeat_seconds if heartbeat_seconds is not None else _env_number("DRIVE_TASK_HEARTBEAT_SECONDS", 20, minimum=1))
        self.missed_heartbeat_threshold = int(missed_heartbeat_threshold if missed_heartbeat_threshold is not None else _env_number("DRIVE_TASK_MISSED_HEARTBEAT_THRESHOLD", 3, minimum=1))
        self.recovery_grace_seconds = float(recovery_grace_seconds if recovery_grace_seconds is not None else _env_number("DRIVE_TASK_RECOVERY_GRACE_SECONDS", 120, minimum=0))
        self.clock_skew_seconds = float(clock_skew_seconds if clock_skew_seconds is not None else _env_number("DRIVE_TASK_CLOCK_SKEW_SECONDS", 5, minimum=0))
        if self.lease_seconds < 1 or self.heartbeat_seconds <= 0 or self.missed_heartbeat_threshold < 1 or self.recovery_grace_seconds < 0 or self.clock_skew_seconds < 0:
            raise ValueError("lease and recovery timing configuration is invalid")
        self.service, self.folder_id = service, folder_id
        self.server_id, self.instance_id = server_id, instance_id
        self.clock, self.sleep = clock, sleep

    @staticmethod
    def filename(task_id: str) -> str:
        if not task_id or len(task_id) > 128 or any(ch in task_id for ch in "/\\\r\n"):
            raise ValueError("Invalid task_id")
        return f"task_{task_id}_lease.json"

    def _list(self, name: str) -> list[dict[str, Any]]:
        query = (f"name = '{name.replace(chr(39), chr(92) + chr(39))}' "
                 f"and '{self.folder_id}' in parents and trashed = false")
        try:
            response = self.service.files().list(q=query, spaces="drive", pageSize=100,
                fields="files(id,name,appProperties,createdTime,modifiedTime),nextPageToken").execute()
            if response.get("nextPageToken"):
                raise LeaseUnavailable("Drive listing exceeded the bounded page")
            return response.get("files", [])
        except LeaseUnavailable:
            raise
        except Exception as exc:
            raise LeaseUnavailable("Unable to inspect Drive task records") from exc

    @staticmethod
    def _to_lease(file: dict[str, Any]) -> TaskLease:
        props = file.get("appProperties") or {}
        try:
            created = _parse_utc(props.get("lease_created_at"), "lease_created_at")
            expiry = _parse_utc(props.get("lease_expiry"), "lease_expiry")
            heartbeat = _parse_utc(props.get("last_heartbeat"), "last_heartbeat")
            updated = _parse_utc(props.get("updated_at"), "updated_at")
            if created > heartbeat or heartbeat > updated or expiry <= created or expiry < heartbeat:
                raise LeaseUnavailable("Lease timestamps are inconsistent")
            cpv = props.get("checkpoint_version")
            status = props.get("status", props.get("ownership_status"))
            if not props.get("task_id") or not props.get("owner_server_id") or not props.get("owner_instance_id") or not status:
                raise LeaseUnavailable("Lease identity or status is missing")
            generation = int(props["lease_version"])
            if generation < 1:
                raise LeaseUnavailable("Lease generation is invalid")
            return TaskLease(task_id=str(props["task_id"]),
                owner_server_id=str(props["owner_server_id"]),
                owner_instance_id=str(props["owner_instance_id"]),
                lease_version=generation, lease_expiry=expiry,
                ownership_status=str(status),
                drive_file_id=str(file["id"]), lease_created_at=created,
                last_heartbeat=heartbeat, updated_at=updated,
                checkpoint_id=props.get("checkpoint_id") or None,
                checkpoint_version=int(cpv) if cpv not in (None, "") else None)
        except LeaseUnavailable:
            raise
        except (KeyError, TypeError, ValueError) as exc:
            raise LeaseUnavailable("Drive lease metadata is invalid or incomplete") from exc

    @staticmethod
    def _properties(lease: TaskLease) -> dict[str, str]:
        return {"task_id": lease.task_id, "owner_server_id": lease.owner_server_id,
            "owner_instance_id": lease.owner_instance_id, "lease_version": str(lease.lease_version),
            "lease_created_at": _utc_iso(lease.lease_created_at), "lease_expiry": _utc_iso(lease.lease_expiry),
            "last_heartbeat": _utc_iso(lease.last_heartbeat), "status": lease.ownership_status,
            "ownership_status": lease.ownership_status, "checkpoint_id": lease.checkpoint_id or "",
            "checkpoint_version": "" if lease.checkpoint_version is None else str(lease.checkpoint_version),
            "updated_at": _utc_iso(lease.updated_at)}

    def acquire(self, task_id: str, *, previous_lease_version: int = 0,
                now: float | None = None) -> TaskLease:
        """Preflight and create a best-effort lease; simultaneous creators can race."""
        name, now = self.filename(task_id), self.clock() if now is None else now
        if self._list(name):
            raise LeaseConflict("Lease file already exists; back off and reconcile")
        generation = max(1, int(previous_lease_version) + 1)
        lease = TaskLease(task_id, self.server_id, self.instance_id, generation,
            now + self.lease_seconds, "ACTIVE", "pending", now, now, now)
        props = self._properties(lease)
        metadata = {"name": name, "mimeType": "application/json", "parents": [self.folder_id],
                    "appProperties": props}
        try:
            created = self.service.files().create(body=metadata, media_body=self._upload_json(props),
                fields="id,name,appProperties").execute()
            file_id = created.get("id")
            if not file_id:
                raise LeaseUnavailable("Drive did not return a lease file ID")
            visible = self._list(name)
            if len(visible) != 1 or visible[0].get("id") != file_id:
                raise LeaseConflict("Lease creation is ambiguous; execution must back off")
            result, _ = self._current_lease(task_id)
            if (result.owner_server_id != self.server_id or result.owner_instance_id != self.instance_id
                    or result.lease_version != generation):
                raise LeaseConflict("Lease verification failed; execution must back off")
            return result
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            raise LeaseUnavailable("Drive lease creation outcome is uncertain") from exc

    def _current_lease(self, task_id: str) -> tuple[TaskLease, dict[str, Any]]:
        records = self._list(self.filename(task_id))
        if len(records) != 1:
            raise LeaseConflict("Lease is missing or duplicated; execution must stop")
        lease = self._to_lease(records[0])
        if lease.task_id != task_id:
            raise LeaseUnavailable("Lease task identity does not match Drive filename")
        content = self._read_document(records[0])
        if any(content.get(key) != value for key, value in self._properties(lease).items()):
            raise LeaseConflict("Lease file content conflicts with its Drive metadata; ownership is ambiguous")
        return lease, records[0]

    def assert_owner(self, lease: TaskLease, *, now: float | None = None) -> None:
        now = self.clock() if now is None else now
        current, _ = self._current_lease(lease.task_id)
        if (current.drive_file_id != lease.drive_file_id or current.owner_server_id != self.server_id
                or current.owner_instance_id != self.instance_id or current.lease_version != lease.lease_version
                or current.ownership_status != "ACTIVE" or current.lease_expiry <= now + self.clock_skew_seconds):
            raise LeaseConflict("Lease expired, changed, or is inside clock-skew safety margin")

    def renew(self, lease: TaskLease, *, now: float | None = None) -> TaskLease:
        now = self.clock() if now is None else now
        self.assert_owner(lease, now=now)
        if now < lease.last_heartbeat - self.clock_skew_seconds:
            raise LeaseConflict("Heartbeat clock moved backwards; renewal refused")
        renewed = TaskLease(**{**asdict(lease), "lease_expiry": now + self.lease_seconds,
                               "last_heartbeat": now, "updated_at": now})
        props = self._properties(renewed)
        try:
            self.service.files().update(fileId=lease.drive_file_id,
                body={"appProperties": props}, media_body=self._upload_json(props),
                fields="id,appProperties").execute()
            self.assert_owner(renewed, now=now)
            return renewed
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            raise LeaseUnavailable("Drive lease renewal outcome is uncertain") from exc

    heartbeat = renew

    def release(self, lease: TaskLease, *, now: float | None = None) -> None:
        self.assert_owner(lease, now=now)
        try:
            self.service.files().delete(fileId=lease.drive_file_id).execute()
        except Exception as exc:
            raise LeaseUnavailable("Drive lease release outcome is uncertain") from exc

    @staticmethod
    def idempotency_key(task_id: str, step_name: str, logical_action_id: str) -> str:
        if not task_id or not step_name or not logical_action_id:
            raise ValueError("task_id, step_name, and logical_action_id are required")
        return hashlib.sha256("\x00".join((task_id, step_name, logical_action_id)).encode()).hexdigest()

    @staticmethod
    def _upload_json(document: dict[str, Any]) -> MediaIoBaseUpload:
        content = _canonical_json(document).encode("utf-8")
        return MediaIoBaseUpload(io.BytesIO(content), mimetype="application/json", resumable=False)

    def _create_record(self, name: str, props: dict[str, str], document: dict[str, Any]) -> dict[str, Any]:
        body = {"name": name, "mimeType": "application/json", "parents": [self.folder_id],
                "appProperties": props}
        try:
            response = self.service.files().create(body=body, media_body=self._upload_json(document),
                fields="id,name,appProperties").execute()
            if not response.get("id"):
                raise LeaseUnavailable("Drive did not return a record ID")
            return response
        except LeaseUnavailable:
            raise
        except Exception as exc:
            raise LeaseUnavailable("Drive JSON record creation failed or is uncertain") from exc

    def _read_document(self, file: dict[str, Any]) -> dict[str, Any]:
        try:
            content = self.service.files().get_media(fileId=file["id"]).execute()
            if not isinstance(content, (bytes, bytearray)) or not content:
                raise ValueError("record content is missing")
            doc = json.loads(bytes(content).decode("utf-8"))
            if not isinstance(doc, dict):
                raise ValueError("record is not a JSON object")
            return doc
        except Exception as exc:
            raise LeaseUnavailable("Drive record content is unavailable or invalid") from exc

    def _latest_checkpoint_version(self, task_id: str, lease_version: int | None = None) -> int:
        try:
            response = self.service.files().list(q=f"'{self.folder_id}' in parents and trashed = false",
                spaces="drive", pageSize=1000, fields="files(name,appProperties),nextPageToken").execute()
            if response.get("nextPageToken"):
                raise LeaseUnavailable("Checkpoint listing exceeded bounded page")
            latest = 0
            prefix = f"task_{task_id}_checkpoint_"
            for record in response.get("files", []):
                props = record.get("appProperties") or {}
                if not str(record.get("name", "")).startswith(prefix) or props.get("task_id") != task_id:
                    continue
                version = int(props["checkpoint_version"])
                if version < 1:
                    raise ValueError("invalid checkpoint version")
                if lease_version is None or int(props.get("lease_version", 0)) <= lease_version:
                    latest = max(latest, version)
            return latest
        except LeaseUnavailable:
            raise
        except (KeyError, TypeError, ValueError) as exc:
            raise LeaseUnavailable("Checkpoint version metadata is invalid") from exc
        except Exception as exc:
            raise LeaseUnavailable("Unable to inspect checkpoint versions") from exc

    def save_checkpoint(self, task_id: str, payload: dict[str, Any], *, owner: TaskLease,
                        checkpoint_id: str | None = None, checkpoint_version: int | None = None,
                        now: float | None = None) -> dict[str, Any]:
        """Create immutable versioned checkpoint; reject detectable stale owners."""
        now = self.clock() if now is None else now
        self.assert_owner(owner, now=now)
        if owner.task_id != task_id or not isinstance(payload, dict):
            raise ValueError("checkpoint task and payload must be valid")
        current, _ = self._current_lease(task_id)
        if (current.drive_file_id != owner.drive_file_id
                or current.owner_server_id != owner.owner_server_id
                or current.owner_instance_id != owner.owner_instance_id
                or current.lease_version != owner.lease_version):
            raise LeaseConflict("Checkpoint owner is stale; immutable write refused")
        latest_version = max(current.checkpoint_version or 0,
                             self._latest_checkpoint_version(task_id, current.lease_version))
        cp_id = checkpoint_id or hashlib.sha256(f"{task_id}:{owner.lease_version}".encode()).hexdigest()[:24]
        version = int(checkpoint_version if checkpoint_version is not None else latest_version + 1)
        if version <= latest_version:
            raise LeaseConflict("Checkpoint version is stale or non-monotonic; immutable write refused")
        data = {"schema": "drive-task-checkpoint", "schema_version": 1, "task_id": task_id,
            "checkpoint_id": cp_id, "checkpoint_version": version,
            "owner_server_id": owner.owner_server_id, "owner_instance_id": owner.owner_instance_id,
            "lease_version": owner.lease_version, "created_at": _utc_iso(now), "payload": payload}
        data["checksum"] = hashlib.sha256(_canonical_json(data).encode("utf-8")).hexdigest()
        name = f"task_{task_id}_checkpoint_{cp_id}_v{version}.json"
        if self._list(name):
            raise LeaseConflict("Checkpoint version already exists; immutable write refused")
        response = self._create_record(name, {"task_id": task_id, "checkpoint_id": cp_id,
            "checkpoint_version": str(version), "lease_version": str(owner.lease_version),
            "owner_instance_id": owner.owner_instance_id}, data)
        reread = self._list(name)
        if len(reread) != 1 or reread[0].get("id") != response["id"]:
            raise LeaseConflict("Checkpoint write is ambiguous")
        if self._read_document(reread[0]) != data:
            raise CheckpointIntegrityError("Checkpoint content read-back differs from the validated payload")
        self.assert_owner(owner, now=now)
        linked = TaskLease(**{**asdict(owner), "checkpoint_id": cp_id,
                              "checkpoint_version": version, "updated_at": now})
        props = self._properties(linked)
        try:
            self.service.files().update(fileId=owner.drive_file_id,
                body={"appProperties": props}, media_body=self._upload_json(props),
                fields="id,appProperties").execute()
            self.assert_owner(linked, now=now)
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            raise LeaseUnavailable("Checkpoint link update outcome is uncertain") from exc
        return data

    def load_checkpoint(self, task_id: str, checkpoint_id: str, checkpoint_version: int,
                        *, expected_owner: TaskLease | None = None) -> dict[str, Any]:
        name = f"task_{task_id}_checkpoint_{checkpoint_id}_v{int(checkpoint_version)}.json"
        records = self._list(name)
        if len(records) != 1:
            raise CheckpointIntegrityError("Checkpoint is missing or duplicated")
        doc = self._read_document(records[0])
        checksum = doc.pop("checksum", None)
        expected = hashlib.sha256(_canonical_json(doc).encode("utf-8")).hexdigest()
        doc["checksum"] = checksum
        if checksum != expected or doc.get("schema") != "drive-task-checkpoint" or doc.get("schema_version") != 1:
            raise CheckpointIntegrityError("Checkpoint checksum or schema validation failed")
        if doc.get("task_id") != task_id or doc.get("checkpoint_id") != checkpoint_id or int(doc.get("checkpoint_version", -1)) != int(checkpoint_version):
            raise CheckpointIntegrityError("Checkpoint identity does not match requested generation")
        _parse_utc(doc.get("created_at"), "checkpoint.created_at")
        if expected_owner is not None and (doc.get("owner_server_id") != expected_owner.owner_server_id
                or doc.get("owner_instance_id") != expected_owner.owner_instance_id
                or int(doc.get("lease_version", -1)) != expected_owner.lease_version):
            raise CheckpointIntegrityError("Checkpoint owner/generation does not match expected lease")
        return doc

    def _receipt_name(self, task_id: str, key: str) -> str:
        self.filename(task_id)
        if not key or len(key) > 128 or any(ch in key for ch in "/\\\r\n"):
            raise ValueError("Invalid idempotency key")
        return f"task_{task_id}_receipt_{key}.json"

    def write_receipt(self, task_id: str, step_name: str, logical_action_id: str,
                      state: str, *, details: dict[str, Any] | None = None,
                      now: float | None = None) -> dict[str, Any]:
        if state not in RECEIPT_STATES:
            raise ValueError(f"Unsupported receipt state: {state}")
        key, now = self.idempotency_key(task_id, step_name, logical_action_id), self.clock() if now is None else now
        doc = {"schema": "drive-task-receipt", "schema_version": 1, "task_id": task_id,
            "step_name": step_name, "logical_action_id": logical_action_id, "idempotency_key": key,
            "state": state, "created_at": _utc_iso(now), "updated_at": _utc_iso(now), "details": details or {}}
        name = self._receipt_name(task_id, key)
        matches = self._list(name)
        if len(matches) > 1:
            raise LeaseConflict("Duplicate receipt records; action blocked")
        if matches:
            old = self._read_document(matches[0])
            if old.get("idempotency_key") != key or old.get("task_id") != task_id or old.get("logical_action_id") != logical_action_id:
                raise LeaseConflict("Receipt identity is inconsistent; action blocked")
            if old.get("state") == "CONFIRMED" and state != "CONFIRMED":
                return old
            doc["created_at"] = old.get("created_at")
            try:
                self.service.files().update(fileId=matches[0]["id"], body={
                    "appProperties": {"task_id": task_id, "idempotency_key": key, "state": state}},
                    media_body=self._upload_json(doc), fields="id,appProperties").execute()
                updated = self._list(name)
                if len(updated) != 1 or self._read_document(updated[0]) != doc:
                    raise LeaseUnavailable("Receipt update read-back is uncertain")
                return doc
            except Exception as exc:
                raise LeaseUnavailable("Receipt update outcome is uncertain") from exc
        created = self._create_record(name, {"task_id": task_id, "idempotency_key": key, "state": state}, doc)
        reread = self._list(name)
        if len(reread) != 1 or reread[0].get("id") != created["id"] or self._read_document(reread[0]) != doc:
            raise LeaseConflict("Receipt creation is ambiguous; action blocked")
        return doc

    def list_receipts(self, task_id: str) -> list[dict[str, Any]]:
        try:
            response = self.service.files().list(q=f"'{self.folder_id}' in parents and trashed = false",
                spaces="drive", pageSize=1000,
                fields="files(id,name,appProperties),nextPageToken").execute()
            if response.get("nextPageToken"):
                raise LeaseUnavailable("Receipt listing exceeded bounded page")
            prefix = f"task_{task_id}_receipt_"
            files = [f for f in response.get("files", []) if str(f.get("name", "")).startswith(prefix)]
            docs = [self._read_document(f) for f in files]
            keys = [d.get("idempotency_key") for d in docs]
            if len(keys) != len(set(keys)):
                raise LeaseConflict("Duplicate persistent receipt keys; recovery blocked")
            for doc in docs:
                if doc.get("task_id") != task_id or doc.get("state") not in RECEIPT_STATES:
                    raise LeaseUnavailable("Receipt schema/state is invalid")
            return docs
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            raise LeaseUnavailable("Unable to inspect execution receipts") from exc

    def recovery_state(self, lease: TaskLease, *, now: float | None = None) -> str:
        now = self.clock() if now is None else now
        if lease.lease_expiry <= now - self.clock_skew_seconds:
            return LEASE_EXPIRED
        if now - lease.last_heartbeat >= self.heartbeat_seconds * self.missed_heartbeat_threshold:
            return LEASE_SUSPECT
        return LEASE_ACTIVE

    def _recover_without_history(self, task_id: str, *, now: float | None = None,
                                 wait: bool = True) -> RecoveryResult:
        """Grace, re-read unchanged lease, validate checkpoint/receipts, propose generation.

        The final Drive update and read-back are not CAS and do not fence a
        concurrent or stale process; success remains best-effort only.
        """
        history: list[str] = []
        start = self.clock() if now is None else now
        try:
            observed, _ = self._current_lease(task_id)
        except (LeaseConflict, LeaseUnavailable) as exc:
            return RecoveryResult(RECOVERY_BLOCKED, task_id, detail=str(exc), history=(RECOVERY_BLOCKED,))
        state = self.recovery_state(observed, now=start)
        history.append(state)
        if state != LEASE_EXPIRED:
            history.append(RECOVERY_BLOCKED)
            return RecoveryResult(RECOVERY_BLOCKED, task_id, observed,
                detail="Lease is not safely expired; no takeover proposed", history=tuple(history))
        due = observed.lease_expiry + self.recovery_grace_seconds + self.clock_skew_seconds
        delay = max(0.0, due - start)
        if delay:
            history.append(RECOVERY_WAITING)
            if not wait:
                return RecoveryResult(RECOVERY_WAITING, task_id, observed,
                    detail=f"Recovery grace remains: {delay:.3f}s", history=tuple(history))
            try:
                self.sleep(delay)
            except Exception as exc:
                history.append(RECOVERY_BLOCKED)
                return RecoveryResult(RECOVERY_BLOCKED, task_id, observed,
                    detail=f"Unable to wait through recovery grace: {exc}", history=tuple(history))
        history.append(RECOVERY_VALIDATING)
        try:
            current, _ = self._current_lease(task_id)
            identity = lambda x: (x.owner_server_id, x.owner_instance_id, x.lease_version, x.lease_created_at, x.last_heartbeat)
            if identity(current) != identity(observed):
                raise LeaseConflict("Lease owner, generation, or heartbeat changed during grace")
            validation_now = max(self.clock(), due)
            if self.recovery_state(current, now=validation_now) != LEASE_EXPIRED:
                raise LeaseConflict("Lease is no longer expired after grace")
            checkpoint = None
            if current.checkpoint_id:
                if current.checkpoint_version is None:
                    raise CheckpointIntegrityError("Lease references checkpoint without a version")
                checkpoint = self.load_checkpoint(task_id, current.checkpoint_id,
                    current.checkpoint_version)
                # Recovery may have advanced the lease generation since this
                # immutable checkpoint was written. Its original owner and
                # generation remain provenance, not a current-owner claim.
                if (not checkpoint.get("owner_server_id") or not checkpoint.get("owner_instance_id")
                        or int(checkpoint.get("lease_version", 0)) < 1
                        or int(checkpoint["lease_version"]) > current.lease_version):
                    raise CheckpointIntegrityError("Checkpoint owner/generation metadata is invalid")
            receipts = self.list_receipts(task_id)
            confirmed = tuple(sorted(d["idempotency_key"] for d in receipts if d["state"] == "CONFIRMED"))
            blocking = {"UNKNOWN", "EXECUTING", "PREPARED", "PENDING", "RECONCILIATION_REQUIRED", "CANCEL_REQUESTED"}
            blocked = tuple(sorted(d["idempotency_key"] for d in receipts if d["state"] in blocking))
            if blocked:
                history.append(RECOVERY_BLOCKED)
                return RecoveryResult(RECOVERY_BLOCKED, task_id, current, checkpoint, confirmed, blocked,
                    "Unresolved action receipts require reconciliation; do not repeat actions", tuple(history))
            if checkpoint is None:
                history.append(RECOVERY_BLOCKED)
                return RecoveryResult(RECOVERY_BLOCKED, task_id, current, None, confirmed, (),
                    "No validated checkpoint or prior task state; Drive-only recovery cannot prove the task was genuinely unstarted, so takeover is refused",
                    tuple(history))
            history.append(RECOVERY_READY)
            proposed_at = max(self.clock(), due)
            proposed = TaskLease(task_id, self.server_id, self.instance_id, current.lease_version + 1,
                proposed_at + self.lease_seconds, "ACTIVE", current.drive_file_id,
                proposed_at, proposed_at, proposed_at, current.checkpoint_id, current.checkpoint_version)
            props = self._properties(proposed)
            self.service.files().update(fileId=current.drive_file_id,
                body={"appProperties": props}, media_body=self._upload_json(props),
                fields="id,appProperties").execute()
            check, _ = self._current_lease(task_id)
            if (check.owner_server_id, check.owner_instance_id, check.lease_version) != (self.server_id, self.instance_id, proposed.lease_version):
                raise LeaseConflict("Recovery generation read-back mismatch; do not execute")
            history.append(RECOVERY_COMPLETED)
            return RecoveryResult(RECOVERY_COMPLETED, task_id, check, checkpoint, confirmed, (),
                "Best-effort generation proposal read back; races remain possible", tuple(history))
        except (LeaseConflict, LeaseUnavailable) as exc:
            history.append(RECOVERY_BLOCKED)
            return RecoveryResult(RECOVERY_BLOCKED, task_id, detail=str(exc), history=tuple(history))
        except Exception as exc:
            history.append(RECOVERY_BLOCKED)
            return RecoveryResult(RECOVERY_BLOCKED, task_id, detail=f"Drive recovery outcome uncertain: {exc}", history=tuple(history))

    def _persist_recovery_history(self, result: RecoveryResult) -> dict[str, Any]:
        generation = result.lease.lease_version if result.lease is not None else 0
        checkpoint = None
        if result.lease is not None and result.lease.checkpoint_id:
            checkpoint = {"checkpoint_id": result.lease.checkpoint_id,
                          "checkpoint_version": result.lease.checkpoint_version}
        elif result.checkpoint is not None:
            checkpoint = {"checkpoint_id": result.checkpoint.get("checkpoint_id"),
                          "checkpoint_version": result.checkpoint.get("checkpoint_version")}
        receipt_refs = sorted(set(result.confirmed_receipt_keys + result.blocked_receipt_keys))
        timestamp = _utc_iso(self.clock())
        transitions = []
        previous = "NONE"
        for index, state in enumerate(result.history):
            transitions.append({"prior_state": previous, "new_state": state,
                "timestamp": timestamp, "server_id": self.server_id,
                "instance_id": self.instance_id, "generation": generation,
                "reason": result.detail if index == len(result.history) - 1 and result.detail
                          else f"Recovery entered {state}",
                "checkpoint_ref": checkpoint, "receipt_refs": receipt_refs})
            previous = state
        document = {"schema": "drive-task-recovery-history", "schema_version": 1,
            "task_id": result.task_id, "recorded_at": timestamp,
            "server_id": self.server_id, "instance_id": self.instance_id,
            "generation": generation, "transitions": transitions}
        name = f"task_{result.task_id}_recovery_history_{uuid.uuid4().hex}.json"
        created = self._create_record(name, {"task_id": result.task_id,
            "record_type": "recovery_history"}, document)
        matches = self._list(name)
        if len(matches) != 1 or matches[0].get("id") != created["id"]:
            raise LeaseUnavailable("Recovery history write is ambiguous")
        if self._read_document(matches[0]) != document:
            raise LeaseUnavailable("Recovery history read-back does not match written content")
        return document

    def list_recovery_history(self, task_id: str) -> list[dict[str, Any]]:
        """Read persisted recovery transition logs for a task from Drive content."""
        self.filename(task_id)
        try:
            response = self.service.files().list(q=f"'{self.folder_id}' in parents and trashed = false",
                spaces="drive", pageSize=1000,
                fields="files(id,name,appProperties),nextPageToken").execute()
            if response.get("nextPageToken"):
                raise LeaseUnavailable("Recovery history listing exceeded bounded page")
            prefix = f"task_{task_id}_recovery_history_"
            records = [f for f in response.get("files", [])
                       if str(f.get("name", "")).startswith(prefix)]
            histories = [self._read_document(record) for record in records]
            for history in histories:
                if (history.get("schema") != "drive-task-recovery-history"
                        or history.get("schema_version") != 1
                        or history.get("task_id") != task_id
                        or not isinstance(history.get("transitions"), list)):
                    raise LeaseUnavailable("Recovery history schema or identity is invalid")
            return sorted(histories, key=lambda item: item.get("recorded_at", ""))
        except LeaseUnavailable:
            raise
        except Exception as exc:
            raise LeaseUnavailable("Unable to inspect recovery history") from exc

    def recover(self, task_id: str, *, now: float | None = None, wait: bool = True) -> RecoveryResult:
        """Run best-effort recovery and persist its state transitions in Drive."""
        result = self._recover_without_history(task_id, now=now, wait=wait)
        try:
            self._persist_recovery_history(result)
            return result
        except Exception as exc:
            return RecoveryResult(RECOVERY_BLOCKED, task_id, result.lease,
                result.checkpoint, result.confirmed_receipt_keys, result.blocked_receipt_keys,
                f"Recovery history persistence failed; do not execute: {exc}",
                result.history + (RECOVERY_BLOCKED,))

    def reconcile_receipt(self, task_id: str, step_name: str, logical_action_id: str,
                          outcome: str, *, details: dict[str, Any] | None = None) -> dict[str, Any]:
        if outcome not in {"CONFIRMED", "FAILED", "CANCELLED", "UNKNOWN", "RECONCILIATION_REQUIRED"}:
            raise ValueError("Reconciliation outcome is unsupported")
        return self.write_receipt(task_id, step_name, logical_action_id, outcome, details=details)

    @staticmethod
    def describe_limitations() -> dict[str, Any]:
        return {"mechanism": "Google Drive JSON file content and AppProperties", "atomic": False,
            "cas_supported": False, "create_if_absent_supported": False,
            "duplicate_names_allowed": True, "mutual_exclusion_guaranteed": False,
            "fencing_guaranteed": False,
            "ownership_advisory": True,
            "policy": "Drive-only ownership is advisory, not exclusive. Fail closed on duplicates, Drive failures, invalid metadata/content, and ambiguous outcomes; re-read after recovery grace. Concurrent acquisition or takeover can race. Strict ownership cannot be provided by this adapter; callers that require it must fail closed and use an independently selected strict-ownership mechanism."}
