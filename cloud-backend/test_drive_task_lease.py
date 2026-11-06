"""Fake-Drive tests for conservative recovery; no live credentials are used."""
import json
import re
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from drive_task_lease import (CheckpointIntegrityError, DriveTaskLeaseCoordinator,
    LeaseConflict, LeaseUnavailable, LEASE_ACTIVE, LEASE_EXPIRED, LEASE_SUSPECT,
    RECOVERY_BLOCKED, RECOVERY_COMPLETED, RECOVERY_WAITING)

class FakeRequest:
    def __init__(self, callback): self.callback = callback
    def execute(self): return self.callback()

class FakeFiles:
    def __init__(self):
        self.files_by_id = {}; self.counter = 0; self.fail_list = False; self.fail_create = False
        self.preflight_barrier = None; self.preflight_calls = 0; self.thread_local_visibility = False
        self.thread_local = threading.local(); self.lock = threading.Lock()
    @staticmethod
    def _name(query):
        match = re.search(r"name = '((?:\\'|[^'])*)'", query or "")
        return match.group(1).replace("\\'", "'") if match else None
    def list(self, **kwargs):
        def run():
            if self.fail_list: raise OSError("offline")
            name = self._name(kwargs.get("q"))
            if self.preflight_barrier and name and name.endswith("_lease.json"):
                with self.lock:
                    self.preflight_calls += 1; ordinal = self.preflight_calls
                if ordinal <= 2: self.preflight_barrier.wait(timeout=3)
            if self.thread_local_visibility and name and name.endswith("_lease.json"):
                vid = getattr(self.thread_local, "visible_id", None)
                return {"files": [self.files_by_id[vid]] if vid in self.files_by_id else []}
            files = list(self.files_by_id.values())
            if name is not None: files = [f for f in files if f.get("name") == name]
            return {"files": files}
        return FakeRequest(run)
    @staticmethod
    def _uploaded_bytes(media_body):
        return media_body._fd.getvalue() if media_body is not None else b""
    def create(self, body, media_body=None, **kwargs):
        def run():
            if self.fail_create: raise OSError("offline create")
            with self.lock:
                self.counter += 1; fid = f"record-{self.counter}"
                rec = {"id": fid, "name": body["name"], "appProperties": dict(body.get("appProperties", {})),
                       "mimeType": body.get("mimeType"), "content": self._uploaded_bytes(media_body)}
                self.files_by_id[fid] = rec
            if rec["name"].endswith("_lease.json"): self.thread_local.visible_id = fid
            return dict(rec)
        return FakeRequest(run)
    def update(self, fileId, body, media_body=None, **kwargs):
        def run():
            if fileId not in self.files_by_id: raise OSError("missing record")
            rec = self.files_by_id[fileId]
            rec.update({k: v for k, v in body.items() if k != "appProperties"})
            if "appProperties" in body: rec["appProperties"] = dict(body["appProperties"])
            if media_body is not None: rec["content"] = self._uploaded_bytes(media_body)
            return dict(rec)
        return FakeRequest(run)
    def get_media(self, fileId):
        def run():
            if fileId not in self.files_by_id: raise OSError("missing record")
            return self.files_by_id[fileId].get("content", b"")
        return FakeRequest(run)
    def delete(self, fileId):
        def run(self): del self.files_by_id[fileId]; return {}
        return FakeRequest(run)

class FakeDrive:
    def __init__(self): self._files = FakeFiles()
    def files(self): return self._files

class FakeClock:
    def __init__(self, now=1000.0): self.now = now; self.sleeps = []
    def __call__(self): return self.now
    def sleep(self, seconds): self.sleeps.append(seconds); self.now += seconds

class DriveTaskLeaseTests(unittest.TestCase):
    def setUp(self):
        self.drive = FakeDrive(); self.clock = FakeClock()
        self.coordinator = DriveTaskLeaseCoordinator(self.drive, "folder", "server-a", "instance-a",
            lease_seconds=60, heartbeat_seconds=20, missed_heartbeat_threshold=3,
            recovery_grace_seconds=10, clock_skew_seconds=0,
            clock=self.clock, sleep=self.clock.sleep)

    def test_environment_configurable_conservative_defaults(self):
        with patch.dict("os.environ", {}, clear=True):
            c = DriveTaskLeaseCoordinator(self.drive, "folder", "s", "i")
        self.assertEqual((c.lease_seconds, c.heartbeat_seconds,
                          c.missed_heartbeat_threshold, c.recovery_grace_seconds),
                         (120, 20, 3, 120))

    def test_lease_json_fields_and_heartbeat_renewal(self):
        lease = self.coordinator.acquire("task-123", now=1000)
        self.assertEqual(lease.lease_expiry, 1060)
        rec = self.drive.files().files_by_id[lease.drive_file_id]
        self.assertEqual(rec["name"], "task_task-123_lease.json")
        self.assertEqual(rec["mimeType"], "application/json")
        doc = json.loads(rec["content"].decode("utf-8"))
        required = {"task_id", "owner_server_id", "owner_instance_id", "lease_version", "lease_created_at",
                    "lease_expiry", "last_heartbeat", "status", "checkpoint_id", "checkpoint_version", "updated_at"}
        self.assertTrue(required.issubset(doc)); self.assertTrue(doc["lease_expiry"].endswith("Z"))
        renewed = self.coordinator.renew(lease, now=1020)
        self.assertEqual(renewed.lease_expiry, 1080); self.assertEqual(renewed.last_heartbeat, 1020)
        self.assertEqual(json.loads(rec["content"])["lease_expiry"], "1970-01-01T00:18:00Z")
        self.coordinator.assert_owner(renewed, now=1020)

    def test_expiry_grace_and_recovery_state_progression(self):
        lease = self.coordinator.acquire("task", now=1000)
        self.coordinator.save_checkpoint("task", {"cursor": 0}, owner=lease, checkpoint_id="start", now=1001)
        self.assertEqual(self.coordinator.recovery_state(lease, now=1001), LEASE_ACTIVE)
        longer_lease = DriveTaskLeaseCoordinator(self.drive, "folder", "s", "i", lease_seconds=120,
            heartbeat_seconds=20, missed_heartbeat_threshold=3, clock_skew_seconds=0,
            clock=self.clock, sleep=self.clock.sleep).acquire("other", now=1000)
        self.assertEqual(self.coordinator.recovery_state(longer_lease, now=1060), LEASE_SUSPECT)
        self.assertEqual(self.coordinator.recovery_state(lease, now=1060), LEASE_EXPIRED)
        self.clock.now = 1060
        pending = self.coordinator.recover("task", wait=False)
        self.assertEqual(pending.state, RECOVERY_WAITING)
        result = self.coordinator.recover("task")
        self.assertEqual(result.state, RECOVERY_COMPLETED)
        self.assertEqual(result.history[-1], RECOVERY_COMPLETED)
        self.assertEqual(self.clock.sleeps, [10])

    def test_duplicate_lease_and_stale_owner_fail_closed(self):
        lease = self.coordinator.acquire("task", now=1000)
        rec = self.drive.files().files_by_id[lease.drive_file_id]
        self.drive.files().files_by_id["dupe"] = {"id": "dupe", "name": "task_task_lease.json", "appProperties": rec["appProperties"]}
        with self.assertRaises(LeaseConflict): self.coordinator.assert_owner(lease, now=1001)
        with self.assertRaises(LeaseConflict): DriveTaskLeaseCoordinator(self.drive, "folder", "b", "b").acquire("task")
        del self.drive.files().files_by_id["dupe"]
        rec["appProperties"]["owner_instance_id"] = "other"
        with self.assertRaises(LeaseConflict): self.coordinator.assert_owner(lease, now=1001)

    def test_simulated_concurrent_acquisition_race_not_a_guarantee(self):
        """RACE DEMO: fake eventual consistency hides sibling writes from read-back."""
        files = self.drive.files(); files.preflight_barrier = threading.Barrier(2); files.thread_local_visibility = True
        cs = [DriveTaskLeaseCoordinator(self.drive, "folder", f"s{i}", f"i{i}", lease_seconds=60,
            clock=self.clock, sleep=self.clock.sleep, clock_skew_seconds=0) for i in range(2)]
        with ThreadPoolExecutor(max_workers=2) as pool: leases = list(pool.map(lambda c: c.acquire("race", now=1000), cs))
        self.assertEqual(len({l.drive_file_id for l in leases}), 2)
        self.assertFalse(DriveTaskLeaseCoordinator.describe_limitations()["mutual_exclusion_guaranteed"])

    def test_drive_outage_fails_closed(self):
        self.drive.files().fail_list = True
        with self.assertRaises(LeaseUnavailable): self.coordinator.acquire("task", now=1000)
        self.drive.files().fail_list = False; self.drive.files().fail_create = True
        with self.assertRaises(LeaseUnavailable): self.coordinator.acquire("task", now=1000)

    def test_checkpoint_checksum_schema_owner_and_stale_write(self):
        lease = self.coordinator.acquire("task", now=1000)
        self.coordinator.save_checkpoint("task", {"cursor": 7}, owner=lease, checkpoint_id="cp", now=1001)
        second = self.coordinator.save_checkpoint("task", {"cursor": 8}, owner=lease,
                                                  checkpoint_id="cp", now=1002)
        self.assertEqual(second["checkpoint_version"], 2)
        self.assertEqual(self.coordinator._current_lease("task")[0].checkpoint_version, 2)
        with self.assertRaises(LeaseConflict):
            self.coordinator.save_checkpoint("task", {"cursor": 9}, owner=lease,
                                             checkpoint_id="cp", checkpoint_version=2, now=1003)
        self.assertEqual(self.coordinator.load_checkpoint("task", "cp", 1, expected_owner=lease)["payload"], {"cursor": 7})
        self.assertEqual(self.coordinator._current_lease("task")[0].checkpoint_id, "cp")
        rec = next(r for r in self.drive.files().files_by_id.values() if r["name"] == "task_task_checkpoint_cp_v1.json")
        doc = json.loads(rec["content"]); doc["payload"]["cursor"] = 8; rec["content"] = json.dumps(doc).encode()
        with self.assertRaises(CheckpointIntegrityError): self.coordinator.load_checkpoint("task", "cp", 1)
        rec = self.drive.files().files_by_id[lease.drive_file_id]; rec["appProperties"]["owner_instance_id"] = "new"
        with self.assertRaises(LeaseConflict): self.coordinator.save_checkpoint("task", {}, owner=lease, now=1002)

    def test_large_checkpoint_is_persisted_as_file_bytes_and_bad_content_fails_closed(self):
        self.coordinator.acquire("task", now=1000)
        payload = {"blob": "x" * 200_000}
        self.coordinator.save_checkpoint("task", payload, owner=self.coordinator._current_lease("task")[0],
                                         checkpoint_id="large", now=1001)
        rec = next(r for r in self.drive.files().files_by_id.values()
                   if r["name"] == "task_task_checkpoint_large_v1.json")
        self.assertEqual(rec["mimeType"], "application/json")
        self.assertGreater(len(rec["content"]), 200_000)
        self.assertEqual(self.coordinator.load_checkpoint("task", "large", 1)["payload"], payload)
        original = rec["content"]
        for invalid in (b"not JSON", b""):
            rec["content"] = invalid
            with self.subTest(content=invalid[:10]), self.assertRaises(LeaseUnavailable):
                self.coordinator.load_checkpoint("task", "large", 1)
        rec["content"] = original

    def test_confirmed_receipt_skipping_stable_keys_and_unknown_action_block(self):
        key = self.coordinator.idempotency_key("task", "step", "action")
        self.assertEqual(key, self.coordinator.idempotency_key("task", "step", "action"))
        lease = self.coordinator.acquire("task", now=1000)
        self.coordinator.save_checkpoint("task", {"cursor": 1}, owner=lease, checkpoint_id="task-state", now=1001)
        self.coordinator.write_receipt("task", "step", "action", "PREPARED", now=1001)
        self.coordinator.write_receipt("task", "step", "action", "EXECUTING", now=1002)
        self.coordinator.write_receipt("task", "step", "action", "CONFIRMED", now=1001)
        receipt = next(r for r in self.drive.files().files_by_id.values()
                       if r["name"].startswith("task_task_receipt_"))
        self.assertEqual(receipt["mimeType"], "application/json")
        self.assertEqual(json.loads(receipt["content"])["state"], "CONFIRMED")
        result = self.coordinator.recover("task", now=1070)
        self.assertEqual(result.state, RECOVERY_COMPLETED); self.assertEqual(result.confirmed_receipt_keys, (key,))

        drive2 = FakeDrive(); clock2 = FakeClock(1000)
        c2 = DriveTaskLeaseCoordinator(drive2, "folder", "s", "i", lease_seconds=60,
            recovery_grace_seconds=10, clock_skew_seconds=0, clock=clock2, sleep=clock2.sleep)
        c2.acquire("unknown-task", now=1000)
        unknown = c2.idempotency_key("unknown-task", "charge", "payment-1")
        c2.write_receipt("unknown-task", "charge", "payment-1", "UNKNOWN", now=1001)
        blocked = c2.recover("unknown-task", now=1070)
        self.assertEqual(blocked.state, RECOVERY_BLOCKED); self.assertIn(unknown, blocked.blocked_receipt_keys)
        self.assertEqual(c2._current_lease("unknown-task")[0].lease_version, 1)

    def test_server_crash_recovery_waits_rereads_and_proposes_generation(self):
        crashed = self.coordinator.acquire("task", now=1000)
        self.coordinator.save_checkpoint("task", {"cursor": 4}, owner=crashed,
                                         checkpoint_id="before-crash", now=1001)
        receipt_key = self.coordinator.idempotency_key("task", "step", "action-1")
        self.coordinator.write_receipt("task", "step", "action-1", "CONFIRMED", now=1002)
        self.clock.now = 1060
        result = self.coordinator.recover("task")
        self.assertEqual(result.state, RECOVERY_COMPLETED)
        self.assertEqual(result.lease.lease_version, crashed.lease_version + 1)
        self.assertEqual(result.lease.owner_instance_id, "instance-a")
        self.assertEqual(self.clock.sleeps, [10])
        with self.assertRaises(LeaseConflict): self.coordinator.assert_owner(crashed, now=self.clock.now)
        histories = self.coordinator.list_recovery_history("task")
        self.assertEqual(len(histories), 1)
        record = histories[0]
        self.assertEqual(record["task_id"], "task")
        self.assertEqual(record["generation"], result.lease.lease_version)
        self.assertTrue(record["transitions"])
        for transition in record["transitions"]:
            self.assertTrue(transition["prior_state"])
            self.assertTrue(transition["new_state"])
            self.assertTrue(transition["timestamp"].endswith("Z"))
            self.assertEqual(transition["server_id"], "server-a")
            self.assertEqual(transition["instance_id"], "instance-a")
            self.assertEqual(transition["checkpoint_ref"]["checkpoint_id"], "before-crash")
            self.assertIn(receipt_key, transition["receipt_refs"])

    def test_recovery_rechecks_owner_after_grace_and_blocks_changed_lease(self):
        lease = self.coordinator.acquire("task", now=1000)
        self.clock.now = 1060
        original_sleep = self.clock.sleep
        def change_owner_during_wait(seconds):
            props = self.drive.files().files_by_id[lease.drive_file_id]["appProperties"]
            props["owner_instance_id"] = "new-owner"
            original_sleep(seconds)
        self.coordinator.sleep = change_owner_during_wait
        result = self.coordinator.recover("task")
        self.assertEqual(result.state, RECOVERY_BLOCKED)
        self.assertIn("conflicts with its Drive metadata", result.detail)

    def test_checkpoint_lineage_remains_valid_after_prior_recovery(self):
        lease = self.coordinator.acquire("task", now=1000)
        self.coordinator.save_checkpoint("task", {"cursor": 3}, owner=lease,
                                         checkpoint_id="lineage", now=1001)
        self.clock.now = 1060
        first = self.coordinator.recover("task")
        self.assertEqual(first.state, RECOVERY_COMPLETED)
        self.clock.now = first.lease.lease_expiry
        second = self.coordinator.recover("task")
        self.assertEqual(second.state, RECOVERY_COMPLETED)
        self.assertEqual(second.checkpoint["payload"], {"cursor": 3})

    def test_recovery_without_checkpoint_fails_closed(self):
        self.coordinator.acquire("empty-task", now=1000)
        result = self.coordinator.recover("empty-task", now=1060, wait=False)
        self.assertEqual(result.state, RECOVERY_WAITING)
        result = self.coordinator.recover("empty-task", now=1070)
        self.assertEqual(result.state, RECOVERY_BLOCKED)
        self.assertIn("cannot prove", result.detail)

    def test_invalid_missing_or_non_utc_timestamp_fails_closed(self):
        lease = self.coordinator.acquire("task", now=1000)
        props = self.drive.files().files_by_id[lease.drive_file_id]["appProperties"]
        props["updated_at"] = "2026-01-01T12:00:00"
        with self.assertRaises(LeaseUnavailable): self.coordinator.assert_owner(lease, now=1001)

    def test_limitation_report_explicitly_denies_cas_and_fencing(self):
        result = self.coordinator.describe_limitations()
        for key in ("atomic", "cas_supported", "create_if_absent_supported", "mutual_exclusion_guaranteed", "fencing_guaranteed"):
            self.assertFalse(result[key])

if __name__ == "__main__": unittest.main()
