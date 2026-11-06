"""Versioned JSON checkpoints in the configured Google Drive folder.

Drive is durable storage here, not a locking/CAS service. The Firestore
coordinator owns the checkpoint version pointer; Drive files are immutable
versioned artifacts, so a partial or stale upload cannot overwrite a newer file.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
from typing import Any


class CheckpointError(RuntimeError):
    pass


class GoogleDriveCheckpointStore:
    REQUIRED_FIELDS = {
        "task_id", "checkpoint_version", "schema_version", "graph_name",
        "graph_version", "original_prompt", "current_step", "completed_steps",
        "pending_steps", "graph_state", "execution_receipts", "idempotency_keys",
        "task_status", "task_lease_version", "server_id", "server_instance_id",
        "created_at", "updated_at", "recovery_metadata",
    }

    def __init__(self, service: Any, folder_id: str) -> None:
        if not folder_id:
            raise ValueError("Google Drive checkpoint folder ID is required")
        self.service = service
        self.folder_id = folder_id

    @classmethod
    def from_env(cls) -> "GoogleDriveCheckpointStore":
        folder_id = os.getenv("DRIVE_CHECKPOINT_FOLDER_ID", "").strip()
        if not folder_id:
            raise CheckpointError("DRIVE_CHECKPOINT_FOLDER_ID is not configured")
        try:
            import google.auth
            from googleapiclient.discovery import build
            credentials, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/drive"])
            return cls(build("drive", "v3", credentials=credentials, cache_discovery=False), folder_id)
        except Exception as exc:
            raise CheckpointError("Google Drive credentials or API client are unavailable") from exc

    @staticmethod
    def _validate(checkpoint: dict[str, Any]) -> None:
        missing = GoogleDriveCheckpointStore.REQUIRED_FIELDS - set(checkpoint)
        if missing:
            raise CheckpointError("Checkpoint is missing required fields: " + ", ".join(sorted(missing)))
        def check_keys(value: Any) -> None:
            if isinstance(value, dict):
                for key, item in value.items():
                    if any(secret in str(key).lower() for secret in
                           ("password", "access_token", "refresh_token", "api_key", "credential", "secret")):
                        raise CheckpointError("Checkpoint contains a prohibited credential-like field")
                    check_keys(item)
            elif isinstance(value, list):
                for item in value:
                    check_keys(item)
        check_keys(checkpoint)
        if not isinstance(checkpoint["task_id"], str) or int(checkpoint["checkpoint_version"]) < 1:
            raise CheckpointError("Checkpoint task identity/version is invalid")
        if int(checkpoint["task_lease_version"]) < 1:
            raise CheckpointError("Checkpoint fencing generation is invalid")
        if not isinstance(checkpoint["graph_state"], dict):
            raise CheckpointError("Checkpoint graph_state must be an object")
        for field in ("completed_steps", "pending_steps", "execution_receipts", "idempotency_keys"):
            if not isinstance(checkpoint[field], list):
                raise CheckpointError(f"Checkpoint {field} must be an array")
        if not isinstance(checkpoint["recovery_metadata"], dict):
            raise CheckpointError("Checkpoint recovery_metadata must be an object")
        try:
            encoded = json.dumps(checkpoint, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        except (TypeError, ValueError) as exc:
            raise CheckpointError("Graph state is not JSON serializable") from exc
        if len(encoded) > 8_000_000:
            raise CheckpointError("Checkpoint exceeds the configured 8 MB safety limit")

    @staticmethod
    def _encoded(checkpoint: dict[str, Any]) -> tuple[bytes, str]:
        GoogleDriveCheckpointStore._validate(checkpoint)
        content = json.dumps(checkpoint, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return content, hashlib.sha256(content).hexdigest()

    def upload(self, checkpoint: dict[str, Any]) -> dict[str, Any]:
        content, digest = self._encoded(checkpoint)
        task_id = checkpoint["task_id"]
        version = int(checkpoint["checkpoint_version"])
        name = f"task_{task_id}_checkpoint_{version}.json"
        try:
            from googleapiclient.http import MediaIoBaseUpload
            result = self.service.files().create(
                body={"name": name, "mimeType": "application/json", "parents": [self.folder_id],
                      "appProperties": {"task_id": task_id, "checkpoint_version": str(version),
                                         "sha256": digest, "schema_version": str(checkpoint["schema_version"])}},
                media_body=MediaIoBaseUpload(io.BytesIO(content), mimetype="application/json", resumable=True),
                fields="id,name,appProperties,createdTime",
            ).execute()
        except Exception as exc:
            raise CheckpointError("Google Drive checkpoint upload failed") from exc
        file_id = result.get("id")
        if not file_id:
            raise CheckpointError("Google Drive did not return a checkpoint file ID")
        loaded = self.download(file_id, expected_sha256=digest)
        if loaded != checkpoint:
            raise CheckpointError("Uploaded checkpoint read-back did not match the source")
        return {"file_id": file_id, "name": name, "checkpoint_version": version,
                "sha256": digest, "created_time": result.get("createdTime")}

    def download(self, file_id: str, *, expected_sha256: str | None = None) -> dict[str, Any]:
        if not file_id or len(file_id) > 256:
            raise CheckpointError("Invalid Drive checkpoint file ID")
        try:
            from googleapiclient.http import MediaIoBaseDownload
            request = self.service.files().get_media(fileId=file_id)
            target = io.BytesIO()
            downloader = MediaIoBaseDownload(target, request)
            done = False
            while not done:
                _, done = downloader.next_chunk()
            content = target.getvalue()
            digest = hashlib.sha256(content).hexdigest()
            if expected_sha256 and digest != expected_sha256:
                raise CheckpointError("Checkpoint SHA-256 verification failed")
            checkpoint = json.loads(content.decode("utf-8"))
            if not isinstance(checkpoint, dict):
                raise CheckpointError("Checkpoint root must be a JSON object")
            self._validate(checkpoint)
            return checkpoint
        except CheckpointError:
            raise
        except Exception as exc:
            raise CheckpointError("Google Drive checkpoint download or validation failed") from exc

    def readiness_check(self) -> bool:
        """Verify folder access plus temporary upload/read/delete permissions."""
        name = f"checkpoint-readiness-probe-{os.getpid()}.json"
        payload = b"{\"probe\":true}"
        file_id = None
        try:
            from googleapiclient.http import MediaIoBaseUpload, MediaIoBaseDownload
            created = self.service.files().create(
                body={"name": name, "mimeType": "application/json", "parents": [self.folder_id]},
                media_body=MediaIoBaseUpload(io.BytesIO(payload), mimetype="application/json"),
                fields="id",
            ).execute()
            file_id = created.get("id")
            if not file_id:
                raise CheckpointError("Drive readiness probe did not return a file ID")
            request = self.service.files().get_media(fileId=file_id)
            output = io.BytesIO()
            downloader = MediaIoBaseDownload(output, request)
            done = False
            while not done:
                _, done = downloader.next_chunk()
            if output.getvalue() != payload:
                raise CheckpointError("Drive readiness probe read-back mismatch")
            return True
        except Exception as exc:
            if isinstance(exc, CheckpointError):
                raise
            raise CheckpointError("Google Drive checkpoint storage readiness check failed") from exc
        finally:
            if file_id:
                try:
                    self.service.files().delete(fileId=file_id).execute()
                except Exception as exc:
                    raise CheckpointError("Drive readiness probe could not be cleaned up") from exc
