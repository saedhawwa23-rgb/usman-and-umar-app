import json
import os
import unittest
from unittest.mock import patch

from redis_task_storage import (
    RedisLeaseConflict,
    RedisTaskLease,
    RedisTaskRuntime,
    RedisTaskStateStore,
    RedisTaskStorage,
    RedisStorageError,
)


class Response:
    def __init__(self, result=None, error=None):
        self.payload = {"result": result}
        if error:
            self.payload["error"] = error
    def raise_for_status(self):
        return None
    def json(self):
        return self.payload


class FakeUpstash:
    def __init__(self):
        self.values = {}
        self.calls = []
    def post(self, url, *, json, headers, timeout):
        self.calls.append((url, json, headers, timeout))
        command, *args = json
        if command == "SET":
            self.values[args[0]] = args[1]
            return Response("OK")
        if command == "GET":
            return Response(self.values.get(args[0]))
        if command == "DEL":
            existed = args[0] in self.values
            self.values.pop(args[0], None)
            return Response(1 if existed else 0)
        return Response(error="unsupported command")


class RedisTaskStorageTests(unittest.TestCase):
    def setUp(self):
        self.http = FakeUpstash()
        self.storage = RedisTaskStorage("https://example.upstash.io", "test-token", http=self.http)

    def test_rest_set_get_use_task_key_json_and_bearer_header(self):
        document = {"task_id": "task-1", "state": "RUNNING", "plan": []}
        self.storage.set("task-1", document)
        self.assertEqual(self.storage.get("task-1"), document)
        self.assertEqual(self.http.calls[0][1][0:2], ["SET", "task_task-1"])
        self.assertEqual(json.loads(self.http.calls[0][1][2]), document)
        self.assertEqual(self.http.calls[0][2]["Authorization"], "Bearer test-token")
        self.assertEqual(self.http.calls[1][1], ["GET", "task_task-1"])

    def test_probe_performs_set_get_and_cleans_its_temporary_key(self):
        self.assertTrue(self.storage.probe())
        self.assertEqual([call[1][0] for call in self.http.calls], ["SET", "GET", "DEL"])
        self.assertEqual(self.http.values, {})

    def test_missing_secrets_fail_with_not_ready_error(self):
        with self.assertRaisesRegex(RedisStorageError, "UPSTASH_REDIS_REST_URL"):
            RedisTaskStorage(url="", token="").probe()

    def test_invalid_task_ids_are_rejected(self):
        with self.assertRaises(ValueError):
            self.storage.get("bad\nkey")

    def test_task_state_contract_and_event_order_are_preserved(self):
        store = RedisTaskStateStore(self.storage, path=":memory:")
        created = store.create("task-2", "prompt")
        self.assertEqual(created["state"], "PENDING")
        store.transition("task-2", "PLANNING", "planning_started")
        store.save_plan("task-2", [{"id": "s1"}])
        store.transition("task-2", "READY", "plan_ready")
        store.append_event("task-2", "custom_event", {"ok": True})
        events = store.events("task-2")
        self.assertEqual([event["event"] for event in events],
                         ["task_created", "planning_started", "plan_saved", "plan_ready", "custom_event"])
        self.assertEqual(store.get("task-2")["plan"], [{"id": "s1"}])
        with self.assertRaisesRegex(ValueError, "Invalid task transition"):
            store.transition("task-2", "COMPLETED", "invalid_shortcut")

    def test_missing_task_and_duplicate_task_are_not_silently_accepted(self):
        store = RedisTaskStateStore(self.storage, path=":memory:")
        with self.assertRaises(KeyError):
            store.get("missing")
        store.create("duplicate", "first")
        with self.assertRaisesRegex(ValueError, "already exists"):
            store.create("duplicate", "second")

    def test_active_lease_conflicts_and_checkpoint_is_persisted_in_task_json(self):
        first = RedisTaskRuntime(self.storage, server_id="server-a", instance_id="instance-a",
                                 lease_seconds=120, heartbeat_seconds=3600)
        store = RedisTaskStateStore(self.storage, path=":memory:")
        session = first.acquire("leased-task")
        store.create("leased-task", "prompt")
        with self.assertRaises(RedisLeaseConflict):
            RedisTaskRuntime(self.storage, server_id="server-b", instance_id="instance-b").acquire("leased-task")
        session.save_checkpoint(plan=[], completed={}, pending={}, results=[], task_state=store.get("leased-task"))
        document = self.storage.get("leased-task")
        self.assertEqual(document["runtime"]["lease"]["owner_instance_id"], "instance-a")
        self.assertIn("payload", document["runtime"]["checkpoint"])
        self.assertIn("task_leased-task", self.http.values)

    def test_runtime_rejects_lease_owned_by_another_instance(self):
        runtime = RedisTaskRuntime(self.storage, server_id="server-a", instance_id="instance-a")
        foreign = RedisTaskLease("foreign-task", "server-b", "instance-b", 1, 9999999999)
        with self.assertRaisesRegex(RedisLeaseConflict, "different server instance"):
            runtime.assert_owner(foreign)

    def test_corrupt_checkpoint_checksum_is_rejected(self):
        runtime = RedisTaskRuntime(self.storage, server_id="server-a", instance_id="instance-a",
                                   lease_seconds=120, heartbeat_seconds=3600)
        session = runtime.acquire("checkpoint-task")
        session.save_checkpoint(plan=[], completed={}, pending={}, results=[], task_state={"state": "RUNNING"})
        document = self.storage.get("checkpoint-task")
        document["runtime"]["checkpoint"]["payload"]["plan"] = [{"tampered": True}]
        self.storage.set("checkpoint-task", document)
        with self.assertRaisesRegex(RedisStorageError, "checksum"):
            runtime.recover_checkpoint("checkpoint-task")

    def test_health_does_not_claim_atomic_exclusive_ownership(self):
        runtime = RedisTaskRuntime(self.storage, server_id="server", instance_id="instance")
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "api-token"}):
            health = runtime.health(0)
        self.assertEqual(health["status"], "ready")
        self.assertTrue(health["redis_ready"])
        self.assertIn("not atomic", health["lease_policy"])


if __name__ == "__main__":
    unittest.main()
