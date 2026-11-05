"""Best-effort task lease coordination using Google Drive files.

Drive does not provide unique-name constraints, ACID transactions, or CAS. The
preflight list + create sequence below is therefore NOT an atomic mutex. It
reduces accidental duplicate starts and fails closed when a conflict is seen,
but cannot guarantee distributed mutual exclusion under races or eventual
consistency. Use a transactional coordinator if strict exclusion is required.
"""
from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from typing import Any


class LeaseConflict(RuntimeError):
    """A live or ambiguous lease already exists; execution must back off."""


class LeaseUnavailable(RuntimeError):
    """Drive could not be consulted safely; execution must fail closed."""


@dataclass(frozen=True)
class TaskLease:
    task_id: str
    owner_server_id: str
    owner_instance_id: str
    lease_version: int
    lease_expiry: float
    ownership_status: str
    drive_file_id: str


class DriveTaskLeaseCoordinator:
    """Drive API v3 adapter using task_{id}_lease.json and AppProperties.

    ``service`` is a Google Drive API v3 service object. No local database is
    consulted as an ownership authority. Call ``assert_owner`` before each
    execution boundary and pass the returned lease version as a fencing token.
    """

    def __init__(self, service: Any, folder_id: str, server_id: str,
                 instance_id: str, lease_seconds: int = 120) -> None:
        if not folder_id or not server_id or not instance_id:
            raise ValueError("Drive folder, server ID, and instance ID are required")
        if lease_seconds < 1:
            raise ValueError("lease_seconds must be positive")
        self.service = service
        self.folder_id = folder_id
        self.server_id = server_id
        self.instance_id = instance_id
        self.lease_seconds = lease_seconds

    @staticmethod
    def filename(task_id: str) -> str:
        if not task_id or len(task_id) > 128 or any(ch in task_id for ch in "/\\\r\n"):
            raise ValueError("Invalid task_id")
        return f"task_{task_id}_lease.json"

    def _list(self, name: str) -> list[dict[str, Any]]:
        query = (f"name = '{name.replace(chr(39), chr(92) + chr(39))}' "
                 f"and '{self.folder_id}' in parents and trashed = false")
        try:
            response = self.service.files().list(
                q=query, spaces="drive", pageSize=100,
                fields="files(id,name,appProperties,createdTime,modifiedTime)",
            ).execute()
            files = response.get("files", [])
            # More than one matching name is ambiguous; never pick one and run.
            if response.get("nextPageToken"):
                raise LeaseUnavailable("Lease listing exceeded the bounded page")
            return files
        except LeaseUnavailable:
            raise
        except Exception as exc:
            raise LeaseUnavailable("Unable to inspect Drive task lease") from exc

    @staticmethod
    def _to_lease(file: dict[str, Any]) -> TaskLease:
        properties = file.get("appProperties") or {}
        try:
            return TaskLease(
                task_id=properties["task_id"],
                owner_server_id=properties["owner_server_id"],
                owner_instance_id=properties["owner_instance_id"],
                lease_version=int(properties["lease_version"]),
                lease_expiry=float(properties["lease_expiry"]),
                ownership_status=properties.get("ownership_status", "ACTIVE"),
                drive_file_id=file["id"],
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise LeaseUnavailable("Drive lease metadata is invalid") from exc

    def acquire(self, task_id: str, *, previous_lease_version: int = 0,
                now: float | None = None) -> TaskLease:
        """Attempt to create a lease; any visible existing file causes backoff.

        The preflight and create are separate Drive operations, so concurrent
        creators can both succeed. This method never claims that the operation
        is atomic. A conflict or uncertain API result must stop task execution.
        """
        name = self.filename(task_id)
        now = time.time() if now is None else now
        existing = self._list(name)
        if existing:
            raise LeaseConflict("Lease file already exists; back off and reconcile")
        lease_version = max(1, int(previous_lease_version) + 1)
        expiry = now + self.lease_seconds
        properties = {
            "task_id": task_id,
            "owner_server_id": self.server_id,
            "owner_instance_id": self.instance_id,
            "lease_version": str(lease_version),
            "lease_expiry": str(expiry),
            "ownership_status": "ACTIVE",
        }
        metadata = {
            "name": name,
            "mimeType": "application/json",
            "parents": [self.folder_id],
            "appProperties": properties,
        }
        try:
            created = self.service.files().create(
                body=metadata, fields="id,name,appProperties,createdTime"
            ).execute()
            file_id = created.get("id")
            if not file_id:
                raise LeaseUnavailable("Drive did not return a lease file ID")
            # Verify visible ownership after creation. This narrows, but does
            # not remove, races caused by listing consistency or propagation.
            visible = self._list(name)
            if len(visible) != 1 or visible[0].get("id") != file_id:
                raise LeaseConflict("Lease creation is ambiguous; execution must back off")
            lease = self._to_lease(visible[0])
            if lease.owner_instance_id != self.instance_id or lease.lease_version != lease_version:
                raise LeaseConflict("Lease verification failed; execution must back off")
            return lease
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            # A timed-out create may have succeeded. Do not retry by starting
            # work; caller must query Drive and reconcile the lease first.
            raise LeaseUnavailable("Drive lease creation outcome is uncertain") from exc

    def assert_owner(self, lease: TaskLease, *, now: float | None = None) -> None:
        """Fail closed unless Drive currently shows exactly this unexpired lease."""
        now = time.time() if now is None else now
        matches = self._list(self.filename(lease.task_id))
        if len(matches) != 1:
            raise LeaseConflict("Lease is missing or ambiguous; execution must stop")
        current = self._to_lease(matches[0])
        if (current.drive_file_id != lease.drive_file_id
                or current.owner_server_id != self.server_id
                or current.owner_instance_id != self.instance_id
                or current.lease_version != lease.lease_version
                or current.ownership_status != "ACTIVE"
                or current.lease_expiry <= now):
            raise LeaseConflict("Lease is expired or ownership has changed; execution must stop")

    def renew(self, lease: TaskLease, *, now: float | None = None) -> TaskLease:
        """Best-effort metadata update; Drive update is not a CAS operation."""
        now = time.time() if now is None else now
        self.assert_owner(lease, now=now)
        renewed = TaskLease(**{**asdict(lease), "lease_expiry": now + self.lease_seconds})
        properties = {
            "task_id": renewed.task_id,
            "owner_server_id": renewed.owner_server_id,
            "owner_instance_id": renewed.owner_instance_id,
            "lease_version": str(renewed.lease_version),
            "lease_expiry": str(renewed.lease_expiry),
            "ownership_status": renewed.ownership_status,
        }
        try:
            self.service.files().update(
                fileId=lease.drive_file_id,
                body={"appProperties": properties},
                fields="id,appProperties",
            ).execute()
            self.assert_owner(renewed, now=now)
            return renewed
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            raise LeaseUnavailable("Drive lease renewal outcome is uncertain") from exc

    def release(self, lease: TaskLease, *, now: float | None = None) -> None:
        """Delete only after a fresh ownership check; deletion is not conditional CAS."""
        self.assert_owner(lease, now=now)
        try:
            self.service.files().delete(fileId=lease.drive_file_id).execute()
        except Exception as exc:
            raise LeaseUnavailable("Drive lease release outcome is uncertain") from exc

    @staticmethod
    def describe_limitations() -> dict[str, Any]:
        return {
            "mechanism": "Google Drive file creation plus AppProperties",
            "atomic": False,
            "cas_supported": False,
            "duplicate_names_allowed": True,
            "mutual_exclusion_guaranteed": False,
            "policy": "Fail closed on visible conflicts or uncertain outcomes; "
                      "recheck ownership at execution boundaries; use a transactional "
                      "coordinator for strict distributed exclusion.",
        }
