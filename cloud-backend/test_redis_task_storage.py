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
    """Minimal REST double. EVAL implements the CAS script's observable contract:
    it commits only when the stored bytes equal the caller's expectation."""

    def __init__(self):
        self.values = {}
        self.calls = []
        self.cas_conflicts = 0
        self._on_eval = None

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
        if command == "EVAL":
            _script, _numkeys, key, allow_create, expected, new_value = args
            if self._on_eval:
                self._on_eval(self)
            current = self.values.get(key)
            if current is None and allow_create != "1":
                self.cas_conflicts += 1
                return Response([-1])
            if (current or "") != expected:
                self.cas_conflicts += 1
                return Response([-1])
            self.values[key] = new_value
            return Response([1])
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

    def test_health_reports_atomic_cross_process_lease_policy(self):
        runtime = RedisTaskRuntime(self.storage, server_id="server", instance_id="instance")
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "api-token"}):
            health = runtime.health(0)
        self.assertEqual(health["status"], "ready")
        self.assertTrue(health["redis_ready"])
        self.assertIn("atomic", health["lease_policy"])
        self.assertIn("fencing", health["lease_policy"])
        self.assertNotIn("not atomic", health["lease_policy"])

    def test_concurrent_writer_is_rejected_and_retried_not_overwritten(self):
        """A second process commits between our read and our CAS; our write must not clobber it."""
        store = RedisTaskStateStore(self.storage, path=":memory:")
        store.create("task-cas", "prompt")

        # Simulate the other process winning the race exactly once, on the first EVAL.
        raced = {"done": False}

        def steal(state):
            if raced["done"]:
                return
            raced["done"] = True
            document = json.loads(state.values["task_task-cas"])
            document["state"] = "RUNNING"
            document["winner"] = "other-process"
            state.values["task_task-cas"] = json.dumps(document)

        self.http._on_eval = steal
        store.append_event("task-cas", "STEP", {"step": 1})
        self.http._on_eval = None

        final = self.storage.get("task-cas")
        self.assertEqual(final["winner"], "other-process", "the other process's commit must survive")
        self.assertIn("STEP", [e["event"] for e in final["events"]], "our retry must still apply")

    def test_cas_gives_up_with_conflict_instead_of_clobbering(self):
        store = RedisTaskStateStore(self.storage, path=":memory:")
        store.create("task-giveup", "prompt")

        # A genuine conflict must change the bytes every time; re-serializing the same
        # document would be byte-identical and the CAS would legitimately succeed.
        counter = {"n": 0}

        def always_steal(state):
            counter["n"] += 1
            document = json.loads(state.values["task_task-giveup"])
            document["winner"] = f"other-process-{counter['n']}"
            state.values["task_task-giveup"] = json.dumps(document)

        self.http._on_eval = always_steal
        with self.assertRaises(RedisLeaseConflict):
            store.append_event("task-giveup", "STEP", {"step": 1})
        self.http._on_eval = None

        self.assertEqual(counter["n"], 8, "every attempt must have lost the race")
        final = self.storage.get("task-giveup")
        self.assertEqual(final["winner"], "other-process-8", "the winner's state must be intact")
        self.assertNotIn("STEP", [e["event"] for e in final["events"]])

    def test_mutate_on_missing_task_without_create_still_raises_key_error(self):
        with self.assertRaises(KeyError):
            self.storage.mutate("task-absent", lambda doc: None)

    def test_eval_carries_expected_bytes_and_new_value(self):
        store = RedisTaskStateStore(self.storage, path=":memory:")
        store.create("task-wire", "prompt")
        eval_calls = [c[1] for c in self.http.calls if c[1][0] == "EVAL"]
        self.assertTrue(eval_calls)
        command, script, numkeys, key, allow_create, expected, new_value = eval_calls[-1]
        self.assertIn("redis.call('GET'", script)
        self.assertEqual(int(numkeys), 1)
        self.assertEqual(key, "task_task-wire")
        # The create path passes allow_create=1 and expects the absent key as "".
        self.assertEqual(allow_create, "1")
        self.assertEqual(expected, "")
        self.assertEqual(json.loads(new_value)["task_id"], "task-wire")
        # A follow-up mutation on the same key must pass the current bytes as its expectation.
        store.append_event("task-wire", "STEP", {})
        command, script, numkeys, key, allow_create, expected, new_value = [
            c[1] for c in self.http.calls if c[1][0] == "EVAL"][-1]
        self.assertEqual(allow_create, "0")
        self.assertEqual(json.loads(expected)["task_id"], "task-wire")
        self.assertIn("STEP", [e["event"] for e in json.loads(new_value)["events"]])


if __name__ == "__main__":
    unittest.main()
