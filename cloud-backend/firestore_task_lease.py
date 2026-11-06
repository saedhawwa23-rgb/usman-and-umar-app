"""Transactional Firestore task leases; Drive remains non-transactional storage.

Firestore transactions are used for authoritative ownership when configured.
The task document is the serialization point for acquire/renew/release. A
Firestore token fences backend state changes only when each mutation validates
it; it cannot atomically fence an external side effect. External actions still
need stable idempotency keys and reconciliation.
"""
from __future__ import annotations

import os
import time
from dataclasses import dataclass
from typing import Any, Callable


class LeaseConflict(RuntimeError):
    """The task is owned by a different runtime or the token is stale."""


class LeaseUnavailable(RuntimeError):
    """The transactional ownership service could not be used safely."""


@dataclass(frozen=True)
class Lease:
    task_id: str
    owner_server_id: str
    owner_instance_id: str
    lease_version: int
    lease_created_at: float
    lease_expiry: float
    last_heartbeat: float
    status: str


@dataclass(frozen=True)
class LeaseConfig:
    duration_seconds: int = 120
    heartbeat_interval_seconds: int = 20
    missed_heartbeat_threshold: int = 3
    recovery_grace_seconds: int = 120

    @classmethod
    def from_env(cls) -> "LeaseConfig":
        config = cls(
            duration_seconds=int(os.getenv("TASK_LEASE_DURATION_SECONDS", "120")),
            heartbeat_interval_seconds=int(os.getenv("TASK_LEASE_HEARTBEAT_SECONDS", "20")),
            missed_heartbeat_threshold=int(os.getenv("TASK_LEASE_MISSED_HEARTBEATS", "3")),
            recovery_grace_seconds=int(os.getenv("TASK_LEASE_RECOVERY_GRACE_SECONDS", "120")),
        )
        if min(config.duration_seconds, config.heartbeat_interval_seconds,
               config.missed_heartbeat_threshold, config.recovery_grace_seconds) < 1:
            raise ValueError("Task lease configuration values must all be positive")
        if config.heartbeat_interval_seconds * config.missed_heartbeat_threshold >= config.duration_seconds:
            raise ValueError("Lease duration must exceed heartbeat interval × missed-heartbeat threshold")
        return config


class FirestoreTaskLeaseCoordinator:
    """Google Cloud Firestore implementation using a transaction per mutation.

    Construct with a Firestore client and runtime identities. ``from_env`` uses
    Application Default Credentials. Missing configuration raises
    LeaseUnavailable rather than silently falling back to local or Drive state.
    """

    def __init__(self, client: Any, server_id: str, instance_id: str,
                 config: LeaseConfig | None = None, collection: str = "openswarm_task_leases",
                 transaction_runner: Callable[[Callable[[Any], Any]], Any] | None = None) -> None:
        if not server_id or not instance_id:
            raise ValueError("server_id and instance_id are required")
        self.client = client
        self.server_id = server_id
        self.instance_id = instance_id
        self.config = config or LeaseConfig.from_env()
        self.collection = collection
        self.transaction_runner = transaction_runner

    @classmethod
    def from_env(cls, server_id: str, instance_id: str,
                 config: LeaseConfig | None = None) -> "FirestoreTaskLeaseCoordinator":
        project = os.getenv("FIRESTORE_PROJECT_ID", "").strip()
        if not project:
            raise LeaseUnavailable("FIRESTORE_PROJECT_ID is not configured")
        try:
            from google.cloud import firestore
            client = firestore.Client(project=project)
        except Exception as exc:
            raise LeaseUnavailable("Firestore client or Application Default Credentials are unavailable") from exc
        return cls(client, server_id, instance_id, config)

    def _ref(self, task_id: str) -> Any:
        if not task_id or len(task_id) > 128:
            raise ValueError("Invalid task_id")
        return self.client.collection(self.collection).document(task_id)

    def check_ready(self, timeout_seconds: float = 3.0) -> bool:
        """Perform a real bounded Firestore read to validate coordinator access."""
        try:
            self.client.collection("openswarm_metadata").document("readiness").get(
                timeout=timeout_seconds
            )
            return True
        except Exception as exc:
            raise LeaseUnavailable("Firestore coordinator readiness check failed") from exc

    @staticmethod
    def _run_transaction(client: Any, callback: Callable[[Any], Any]) -> Any:
        try:
            from google.cloud import firestore
            transaction = client.transaction(max_attempts=5)
            return firestore.transactional(callback)(transaction)
        except (LeaseConflict, LeaseUnavailable):
            raise
        except Exception as exc:
            raise LeaseUnavailable("Firestore transaction failed; ownership was not changed") from exc

    def _run(self, callback: Callable[[Any], Any]) -> Any:
        if self.transaction_runner is not None:
            try:
                return self.transaction_runner(callback)
            except (LeaseConflict, LeaseUnavailable):
                raise
            except Exception as exc:
                raise LeaseUnavailable("Transactional lease operation failed") from exc
        return self._run_transaction(self.client, callback)

    @staticmethod
    def _lease(task_id: str, value: dict[str, Any]) -> Lease:
        try:
            return Lease(
                task_id=task_id,
                owner_server_id=str(value["owner_server_id"]),
                owner_instance_id=str(value["owner_instance_id"]),
                lease_version=int(value["lease_version"]),
                lease_created_at=float(value["lease_created_at"]),
                lease_expiry=float(value["lease_expiry"]),
                last_heartbeat=float(value["last_heartbeat"]),
                status=str(value["status"]),
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise LeaseUnavailable("Firestore lease record is invalid") from exc

    def acquire(self, task_id: str, *, now: float | None = None) -> Lease:
        """Acquire new ownership or take over after expiry plus recovery grace."""
        now = time.time() if now is None else now
        ref = self._ref(task_id)
        config = self.config

        def operation(transaction: Any) -> Lease:
            snapshot = ref.get(transaction=transaction)
            control_ref = self.client.collection("openswarm_task_control").document(task_id)
            prior = snapshot.to_dict() if snapshot.exists else None
            if prior:
                current = self._lease(task_id, prior)
                same_runtime = (current.owner_server_id == self.server_id and
                                current.owner_instance_id == self.instance_id)
                if current.status == "ACTIVE" and current.lease_expiry > now:
                    if same_runtime:
                        return current
                    raise LeaseConflict("Task is currently owned by another server instance")
                if current.status != "RELEASED" and current.lease_expiry + config.recovery_grace_seconds > now:
                    raise LeaseConflict("Lease expiry recovery grace period has not elapsed")
                version = current.lease_version + 1
                created_at = now
            else:
                version = 1
                created_at = now
            control_snapshot = control_ref.get(transaction=transaction)
            prior_control = control_snapshot.to_dict() if control_snapshot.exists else {}
            data = {
                "task_id": task_id,
                "owner_server_id": self.server_id,
                "owner_instance_id": self.instance_id,
                "lease_version": version,
                "lease_created_at": created_at,
                "lease_expiry": now + config.duration_seconds,
                "last_heartbeat": now,
                "status": "ACTIVE",
                "updated_at": now,
            }
            transaction.set(ref, data)
            transaction.set(control_ref, {
                "task_id": task_id, "command": "NONE",
                "control_generation": int(prior_control.get("control_generation", 0)) + 1,
                "updated_at": now,
            })
            return self._lease(task_id, data)

        return self._run(operation)

    def renew(self, lease: Lease, *, now: float | None = None) -> Lease:
        now = time.time() if now is None else now
        ref = self._ref(lease.task_id)

        def operation(transaction: Any) -> Lease:
            snapshot = ref.get(transaction=transaction)
            if not snapshot.exists:
                raise LeaseConflict("Lease no longer exists")
            current = self._lease(lease.task_id, snapshot.to_dict() or {})
            self._assert_matches(current, lease, now)
            data = {
                **snapshot.to_dict(),
                "lease_expiry": now + self.config.duration_seconds,
                "last_heartbeat": now,
                "updated_at": now,
            }
            transaction.set(ref, data)
            return self._lease(lease.task_id, data)

        return self._run(operation)

    def release(self, lease: Lease, *, now: float | None = None) -> None:
        now = time.time() if now is None else now
        ref = self._ref(lease.task_id)

        def operation(transaction: Any) -> None:
            snapshot = ref.get(transaction=transaction)
            if not snapshot.exists:
                raise LeaseConflict("Lease no longer exists")
            current = self._lease(lease.task_id, snapshot.to_dict() or {})
            self._assert_matches(current, lease, now, permit_expired=True)
            data = {**snapshot.to_dict(), "status": "RELEASED", "lease_expiry": now,
                    "last_heartbeat": now, "updated_at": now}
            transaction.set(ref, data)

        self._run(operation)

    def validate(self, lease: Lease, *, now: float | None = None) -> Lease:
        now = time.time() if now is None else now
        ref = self._ref(lease.task_id)

        def operation(transaction: Any) -> Lease:
            snapshot = ref.get(transaction=transaction)
            if not snapshot.exists:
                raise LeaseConflict("Lease no longer exists")
            current = self._lease(lease.task_id, snapshot.to_dict() or {})
            self._assert_matches(current, lease, now)
            return current

        return self._run(operation)

    def update_task_state(self, lease: Lease, updates: dict[str, Any], *, now: float | None = None) -> dict[str, Any]:
        """Atomically validate ownership and mutate task metadata in one transaction.

        This protects Firestore task records. It cannot atomically guard Drive
        writes or non-transactional external actions; those must revalidate and
        reconcile with idempotency receipts.
        """
        now = time.time() if now is None else now
        lease_ref = self._ref(lease.task_id)
        state_ref = self.client.collection("openswarm_task_state").document(lease.task_id)

        def operation(transaction: Any) -> dict[str, Any]:
            lease_snapshot = lease_ref.get(transaction=transaction)
            if not lease_snapshot.exists:
                raise LeaseConflict("Lease no longer exists")
            current = self._lease(lease.task_id, lease_snapshot.to_dict() or {})
            self._assert_matches(current, lease, now)
            state_snapshot = state_ref.get(transaction=transaction)
            state = state_snapshot.to_dict() if state_snapshot.exists else {"task_id": lease.task_id}
            if "checkpoint_version" in updates:
                current_version = int(state.get("checkpoint_version", 0))
                proposed_version = int(updates["checkpoint_version"])
                if proposed_version <= current_version:
                    raise LeaseConflict("Checkpoint pointer update is stale")
            updated = {**state, **updates, "task_id": lease.task_id,
                       "lease_version": lease.lease_version, "updated_at": now}
            transaction.set(state_ref, updated)
            return updated

        return self._run(operation)

    def get_task_state(self, task_id: str, timeout_seconds: float = 3.0) -> dict[str, Any] | None:
        try:
            snapshot = self.client.collection("openswarm_task_state").document(task_id).get(
                timeout=timeout_seconds
            )
            return snapshot.to_dict() if snapshot.exists else None
        except Exception as exc:
            raise LeaseUnavailable("Firestore task-state retrieval failed") from exc

    def get_lease(self, task_id: str, timeout_seconds: float = 3.0) -> Lease | None:
        try:
            snapshot = self._ref(task_id).get(timeout=timeout_seconds)
            return self._lease(task_id, snapshot.to_dict() or {}) if snapshot.exists else None
        except LeaseUnavailable:
            raise
        except Exception as exc:
            raise LeaseUnavailable("Firestore lease retrieval failed") from exc

    def get_control(self, task_id: str, timeout_seconds: float = 3.0) -> dict[str, Any]:
        try:
            snapshot = self.client.collection("openswarm_task_control").document(task_id).get(timeout=timeout_seconds)
            return snapshot.to_dict() or {"task_id": task_id, "command": "NONE"}
        except Exception as exc:
            raise LeaseUnavailable("Firestore task-control retrieval failed") from exc

    def request_control(self, task_id: str, command: str, *, now: float | None = None) -> dict[str, Any]:
        command = command.upper()
        if command not in {"PAUSE", "CANCEL"}:
            raise ValueError("Control command must be PAUSE or CANCEL")
        now = time.time() if now is None else now
        ref = self.client.collection("openswarm_task_control").document(task_id)

        def operation(transaction: Any) -> dict[str, Any]:
            snapshot = ref.get(transaction=transaction)
            prior = snapshot.to_dict() if snapshot.exists else {}
            existing = str(prior.get("command", "NONE")).upper()
            # Cancellation wins over pause and a pending cancellation is never overwritten.
            selected = "CANCEL" if "CANCEL" in {existing, command} else command
            result = {"task_id": task_id, "command": selected,
                      "control_generation": int(prior.get("control_generation", 0)) + 1,
                      "requested_at": now}
            transaction.set(ref, result)
            return result

        return self._run(operation)

    def _assert_matches(self, current: Lease, expected: Lease, now: float,
                        permit_expired: bool = False) -> None:
        if (current.owner_server_id != self.server_id
                or current.owner_instance_id != self.instance_id
                or current.owner_server_id != expected.owner_server_id
                or current.owner_instance_id != expected.owner_instance_id
                or current.lease_version != expected.lease_version
                or current.status != "ACTIVE"
                or (not permit_expired and current.lease_expiry <= now)):
            raise LeaseConflict("Lease expired or stale fencing generation; operation rejected")
