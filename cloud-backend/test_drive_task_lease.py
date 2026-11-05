"""Contract tests for the best-effort Google Drive task lease adapter."""
import unittest

from drive_task_lease import (
    DriveTaskLeaseCoordinator,
    LeaseConflict,
    LeaseUnavailable,
)


class FakeRequest:
    def __init__(self, callback):
        self.callback = callback

    def execute(self):
        return self.callback()


class FakeFiles:
    def __init__(self):
        self.files_by_id = {}
        self.counter = 0
        self.fail_list = False

    def list(self, **kwargs):
        def run():
            if self.fail_list:
                raise OSError("offline")
            return {"files": list(self.files_by_id.values())}
        return FakeRequest(run)

    def create(self, body, **kwargs):
        def run():
            self.counter += 1
            file_id = f"lease-{self.counter}"
            self.files_by_id[file_id] = {
                "id": file_id,
                "name": body["name"],
                "appProperties": dict(body["appProperties"]),
            }
            return dict(self.files_by_id[file_id])
        return FakeRequest(run)

    def update(self, fileId, body, **kwargs):
        def run():
            self.files_by_id[fileId]["appProperties"] = dict(body["appProperties"])
            return {"id": fileId, "appProperties": body["appProperties"]}
        return FakeRequest(run)

    def delete(self, fileId):
        def run():
            del self.files_by_id[fileId]
            return {}
        return FakeRequest(run)


class FakeDrive:
    def __init__(self):
        self._files = FakeFiles()

    def files(self):
        return self._files


class DriveTaskLeaseTests(unittest.TestCase):
    def setUp(self):
        self.drive = FakeDrive()
        self.coordinator = DriveTaskLeaseCoordinator(
            self.drive, "checkpoints-folder", "server-a", "instance-a", lease_seconds=60
        )

    def test_creates_named_lease_with_owner_app_properties(self):
        lease = self.coordinator.acquire("task-123", now=1000)
        self.assertEqual(lease.drive_file_id, "lease-1")
        self.assertEqual(lease.lease_version, 1)
        self.assertEqual(lease.lease_expiry, 1060)
        self.assertEqual(self.drive.files().files_by_id[lease.drive_file_id]["name"],
                         "task_task-123_lease.json")
        self.assertEqual(self.drive.files().files_by_id[lease.drive_file_id]["appProperties"]["owner_instance_id"],
                         "instance-a")

    def test_visible_existing_lease_immediately_conflicts(self):
        self.coordinator.acquire("task-123", now=1000)
        with self.assertRaises(LeaseConflict):
            DriveTaskLeaseCoordinator(self.drive, "checkpoints-folder", "server-b", "instance-b").acquire(
                "task-123", now=1001
            )

    def test_assert_owner_fails_after_lease_expiry(self):
        lease = self.coordinator.acquire("task-123", now=1000)
        with self.assertRaises(LeaseConflict):
            self.coordinator.assert_owner(lease, now=1060)

    def test_drive_listing_failure_fails_closed(self):
        self.drive.files().fail_list = True
        with self.assertRaises(LeaseUnavailable):
            self.coordinator.acquire("task-123", now=1000)

    def test_report_does_not_claim_atomicity(self):
        limitations = DriveTaskLeaseCoordinator.describe_limitations()
        self.assertFalse(limitations["atomic"])
        self.assertFalse(limitations["cas_supported"])
        self.assertFalse(limitations["mutual_exclusion_guaranteed"])


if __name__ == "__main__":
    unittest.main()
