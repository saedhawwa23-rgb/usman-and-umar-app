"""Tests of coordinator policy using a serialized in-memory transaction runner.

These tests validate adapter logic only. They are not Firestore Emulator or
cloud integration tests and do not establish production service availability.
"""
import threading
import unittest
from unittest.mock import patch

from firestore_task_lease import (
    FirestoreTaskLeaseCoordinator,
    LeaseConfig,
    LeaseConflict,
)


class Snapshot:
    def __init__(self, value):
        self.exists = value is not None
        self._value = value

    def to_dict(self):
        return dict(self._value) if self._value is not None else None


class Transaction:
    def __init__(self, store):
        self.store = store
        self.pending = {}

    def set(self, ref, value):
        self.pending[ref.key] = dict(value)


class DocumentRef:
    def __init__(self, store, key):
        self.store, self.key = store, key

    def get(self, transaction=None):
        source = transaction.pending if transaction else self.store.data
        value = source.get(self.key, self.store.data.get(self.key))
        return Snapshot(value)


class CollectionRef:
    def __init__(self, store, name):
        self.store, self.name = store, name

    def document(self, key):
        return DocumentRef(self.store, f"{self.name}/{key}")


class Store:
    def __init__(self):
        self.data = {}
        self.lock = threading.Lock()

    def collection(self, name):
        return CollectionRef(self, name)

    def run_transaction(self, callback):
        # Serializes callback + commit to model the API contract under test.
        # This is intentionally not a substitute for the Firestore Emulator.
        with self.lock:
            tx = Transaction(self)
            result = callback(tx)
            self.data.update(tx.pending)
            return result


def coordinator(store, server, instance):
    return FirestoreTaskLeaseCoordinator(
        store, server, instance,
        config=LeaseConfig(120, 20, 3, 120),
        transaction_runner=store.run_transaction,
    )


class FirestoreLeasePolicyTests(unittest.TestCase):
    def setUp(self):
        self.store = Store()

    def test_two_simultaneous_acquires_one_wins_under_serialized_transaction_model(self):
        coordinators = [coordinator(self.store, "s1", "i1"), coordinator(self.store, "s2", "i2")]
        barrier = threading.Barrier(2)
        results = []
        result_lock = threading.Lock()

        def attempt(client):
            barrier.wait()
            try:
                result = client.acquire("task-x", now=1000)
                item = ("ok", result)
            except LeaseConflict:
                item = ("conflict", None)
            with result_lock:
                results.append(item)

        threads = [threading.Thread(target=attempt, args=(client,)) for client in coordinators]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)
        self.assertTrue(all(not thread.is_alive() for thread in threads))
        self.assertEqual(sum(item[0] == "ok" for item in results), 1)
        self.assertEqual(sum(item[0] == "conflict" for item in results), 1)

    def test_expired_owner_waits_grace_then_takeover_increments_generation(self):
        first = coordinator(self.store, "s1", "i1")
        second = coordinator(self.store, "s2", "i2")
        lease1 = first.acquire("task-x", now=1000)
        with self.assertRaises(LeaseConflict):
            second.acquire("task-x", now=1121)
        lease2 = second.acquire("task-x", now=1241)
        self.assertEqual(lease2.lease_version, lease1.lease_version + 1)
        self.assertEqual(lease2.owner_instance_id, "i2")

    def test_two_competing_takeovers_after_grace_only_one_wins_in_transaction_model(self):
        first = coordinator(self.store, "s1", "i1")
        original = first.acquire("task-race", now=1000)
        candidates = [coordinator(self.store, "s2", "i2"), coordinator(self.store, "s3", "i3")]
        barrier = threading.Barrier(2)
        results = []
        result_lock = threading.Lock()

        def attempt(client):
            barrier.wait()
            try:
                value = client.acquire("task-race", now=1241)
                outcome = ("ok", value)
            except LeaseConflict:
                outcome = ("conflict", None)
            with result_lock:
                results.append(outcome)

        threads = [threading.Thread(target=attempt, args=(client,)) for client in candidates]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=5)
        self.assertTrue(all(not thread.is_alive() for thread in threads))
        self.assertEqual(sum(item[0] == "ok" for item in results), 1)
        self.assertEqual(sum(item[0] == "conflict" for item in results), 1)
        self.assertEqual(next(item[1].lease_version for item in results if item[0] == "ok"),
                         original.lease_version + 1)

    def test_heartbeat_renews_and_stale_owner_cannot_release_or_renew(self):
        first = coordinator(self.store, "s1", "i1")
        second = coordinator(self.store, "s2", "i2")
        lease1 = first.acquire("task-x", now=1000)
        renewed = first.renew(lease1, now=1010)
        self.assertEqual(renewed.last_heartbeat, 1010)
        with self.assertRaises(LeaseConflict):
            second.renew(renewed, now=1020)
        first.release(renewed, now=1021)
        lease2 = second.acquire("task-x", now=1142)
        self.assertEqual(lease2.lease_version, 2)
        with self.assertRaises(LeaseConflict):
            first.release(renewed, now=1143)

    def test_stale_generation_cannot_mutate_task_state(self):
        first = coordinator(self.store, "s1", "i1")
        second = coordinator(self.store, "s2", "i2")
        old = first.acquire("task-x", now=1000)
        first.release(old, now=1001)
        current = second.acquire("task-x", now=1122)
        with self.assertRaises(LeaseConflict):
            first.update_task_state(old, {"state": "COMPLETED"}, now=1123)
        result = second.update_task_state(current, {"state": "RUNNING"}, now=1123)
        self.assertEqual(result["lease_version"], current.lease_version)

    def test_configurable_defaults_and_invalid_timing(self):
        self.assertEqual(LeaseConfig(), LeaseConfig(120, 20, 3, 120))
        with patch.dict("os.environ", {
            "TASK_LEASE_DURATION_SECONDS": "60",
            "TASK_LEASE_HEARTBEAT_SECONDS": "20",
            "TASK_LEASE_MISSED_HEARTBEATS": "3",
            "TASK_LEASE_RECOVERY_GRACE_SECONDS": "120",
        }, clear=True):
            with self.assertRaises(ValueError):
                LeaseConfig.from_env()


if __name__ == "__main__":
    unittest.main()
