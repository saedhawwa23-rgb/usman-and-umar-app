import os
import unittest
import tempfile
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from main import app
from task_state import TaskStateStore
import main
import openswarm_agent


class FakeCoordinator:
    def acquire(self, task_id): return SimpleNamespace(task_id=task_id, lease_version=1)
    def validate(self, lease): return lease
    def renew(self, lease): return lease
    def update_task_state(self, lease, updates): return updates
    def release(self, lease): return None
    def get_task_state(self, task_id): return None
    def get_lease(self, task_id):
        if task_id == "does-not-exist": return None
        return SimpleNamespace(status="ACTIVE", lease_expiry=time.time() + 300)
    def request_control(self, task_id, command): return {"task_id": task_id, "command": command, "control_generation": 1}
    def check_ready(self): return True
    def get_control(self, task_id): return {"task_id": task_id, "command": "NONE"}


class FakeCheckpointStore:
    def readiness_check(self): return True
    def upload(self, checkpoint): return {"file_id": "fake", "sha256": "fake"}


class OpenSwarmInspectionApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://testserver",
        )

    async def asyncTearDown(self):
        await self.client.aclose()

    async def test_agent_and_capability_endpoints_require_bearer_auth(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            for path in ("/agents", "/capabilities", "/tools", "/tasks/missing", "/tasks/missing/logs"):
                response = await self.client.get(path)
                self.assertEqual(response.status_code, 401, path)

    async def test_agent_roster_lists_specialists_and_adapter_readiness(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            agents = await self.client.get("/agents", headers={"Authorization": "Bearer test-secret"})
            capabilities = await self.client.get("/capabilities", headers={"Authorization": "Bearer test-secret"})
        self.assertEqual(agents.status_code, 200)
        ids = {agent["id"] for agent in agents.json()["agents"]}
        self.assertTrue({"manager", "planner", "research", "browser", "coder", "terminal",
                         "github", "files", "automation", "android", "data", "documents",
                         "drive", "security", "verification", "delivery", "memory"} <= ids)
        self.assertEqual(capabilities.status_code, 200)
        browser = next(item for item in capabilities.json()["capabilities"] if item["id"] == "browser")
        research = next(item for item in capabilities.json()["capabilities"] if item["id"] == "research")
        github = next(item for item in capabilities.json()["capabilities"] if item["id"] == "github")
        memory = next(item for item in capabilities.json()["capabilities"] if item["id"] == "memory")
        self.assertTrue(browser["adapter_configured"])
        self.assertTrue(research["adapter_configured"])
        self.assertTrue(memory["adapter_configured"])
        self.assertFalse(github["adapter_configured"])
        tool_ids = {item["id"] for item in capabilities.json()["tools"]}
        self.assertIn("research.search", tool_ids)
        self.assertIn("memory.read", tool_ids)



    async def test_pause_and_cancel_endpoints_update_task_state(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "api.sqlite"))
            for task_id in ("pause-api", "cancel-api"):
                store.create(task_id, "test")
                store.transition(task_id, "PLANNING", "planning")
                store.transition(task_id, "READY", "ready")
                store.transition(task_id, "RUNNING", "running")
            with patch.object(main, "_task_store", store), \
                 patch.object(main, "_get_lease_coordinator", return_value=FakeCoordinator()), \
                 patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
                headers = {"Authorization": "Bearer test-secret"}
                paused = await self.client.post("/tasks/pause-api/pause", headers=headers)
                store.transition("pause-api", "PAUSED", "test_checkpoint_reached")
                paused_again = await self.client.post("/tasks/pause-api/pause", headers=headers)
                cancelled = await self.client.post("/tasks/cancel-api/cancel", headers=headers)
            self.assertEqual(paused.status_code, 200)
            self.assertEqual(paused_again.status_code, 200)
            self.assertEqual(paused_again.json()["state"], "PAUSED")
            self.assertEqual(cancelled.status_code, 200)
            self.assertEqual(store.get("pause-api")["state"], "PAUSED")
            self.assertEqual(store.get("cancel-api")["state"], "CANCEL_REQUESTED")

    async def test_resume_endpoint_only_accepts_paused_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "api.sqlite"))
            store.create("resume-api", "Resume my task")
            store.transition("resume-api", "PLANNING", "planning")
            store.save_plan("resume-api", [])
            store.transition("resume-api", "READY", "ready")
            store.transition("resume-api", "RUNNING", "running")
            store.transition("resume-api", "PAUSE_REQUESTED", "pause_requested")
            store.transition("resume-api", "PAUSED", "paused")
            store.save_result("resume-api", {"status": "PAUSED", "results": [], "approved_step_ids": []})
            with patch.object(main, "_task_store", store), \
                 patch.object(openswarm_agent, "run_openswarm_task", new=AsyncMock(return_value='{"status":"COMPLETED"}')), \
                 patch.object(main, "_get_lease_coordinator", return_value=FakeCoordinator()), \
                 patch.object(main, "_get_checkpoint_store", return_value=FakeCheckpointStore()), \
                 patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
                response = await self.client.post(
                    "/tasks/resume-api/resume", headers={"Authorization": "Bearer test-secret"}
                )
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["result"], '{"status":"COMPLETED"}')

    async def test_unconfigured_tool_fails_closed_even_when_approved(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post(
                "/tools/terminal.execute/execute",
                headers={"Authorization": "Bearer test-secret"},
                json={"payload": {"command": "echo unsafe"}, "approved": True},
            )
        self.assertEqual(response.status_code, 503)
        self.assertIn("no configured execution adapter", response.json()["detail"])

    async def test_task_inspection_returns_not_found_for_unknown_task(self):
        with patch.object(main, "_get_lease_coordinator", return_value=FakeCoordinator()), \
             patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.get(
                "/tasks/does-not-exist",
                headers={"Authorization": "Bearer test-secret"},
            )
        self.assertEqual(response.status_code, 404)


if __name__ == "__main__":
    unittest.main()
