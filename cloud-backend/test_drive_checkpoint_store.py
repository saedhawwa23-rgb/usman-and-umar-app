import unittest

from drive_checkpoint_store import CheckpointError, GoogleDriveCheckpointStore


class DriveCheckpointValidationTests(unittest.TestCase):
    def checkpoint(self):
        return {
            "task_id": "task-1", "checkpoint_version": 1, "schema_version": 1,
            "graph_name": "openswarm", "graph_version": "1", "original_prompt": "summarize",
            "current_step": "step-1", "completed_steps": [], "pending_steps": ["step-1"],
            "graph_state": {"plan": []}, "execution_receipts": [], "idempotency_keys": [],
            "task_status": "RUNNING", "task_lease_version": 4, "server_id": "server-a",
            "server_instance_id": "instance-a", "created_at": "2026-11-05T00:00:00Z",
            "updated_at": "2026-11-05T00:00:01Z", "recovery_metadata": {},
        }

    def test_valid_checkpoint_has_stable_checksum(self):
        checkpoint = self.checkpoint()
        first = GoogleDriveCheckpointStore._encoded(checkpoint)
        second = GoogleDriveCheckpointStore._encoded(checkpoint)
        self.assertEqual(first, second)
        self.assertEqual(len(first[1]), 64)

    def test_missing_required_schema_field_is_rejected(self):
        checkpoint = self.checkpoint()
        checkpoint.pop("execution_receipts")
        with self.assertRaises(CheckpointError):
            GoogleDriveCheckpointStore._validate(checkpoint)

    def test_nested_credential_fields_are_rejected(self):
        checkpoint = self.checkpoint()
        checkpoint["graph_state"]["auth"] = {"access_token": "must-not-be-saved"}
        with self.assertRaises(CheckpointError):
            GoogleDriveCheckpointStore._validate(checkpoint)

    def test_invalid_fencing_generation_and_non_json_graph_state_are_rejected(self):
        checkpoint = self.checkpoint()
        checkpoint["task_lease_version"] = 0
        with self.assertRaises(CheckpointError):
            GoogleDriveCheckpointStore._validate(checkpoint)
        checkpoint = self.checkpoint()
        checkpoint["graph_state"]["not_json"] = object()
        with self.assertRaises(CheckpointError):
            GoogleDriveCheckpointStore._encoded(checkpoint)


if __name__ == "__main__":
    unittest.main()
