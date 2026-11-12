import os
import unittest
import tempfile
from unittest.mock import AsyncMock, patch

import httpx
from main import app
from task_state import TaskStateStore
import main
import openswarm_agent


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
        files = next(item for item in capabilities.json()["capabilities"] if item["id"] == "files")
        self.assertTrue(browser["adapter_configured"])
        self.assertTrue(research["adapter_configured"])
        self.assertTrue(memory["adapter_configured"])
        self.assertTrue(files["adapter_configured"])
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
            class RuntimeStub:
                def assert_task_owner(self, _task_id):
                    return None
            with patch.object(main, "_task_store", store), patch.object(main, "_task_runtime", RuntimeStub()), patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
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
            self.assertEqual(cancelled.json()["status"], "CANCEL_REQUESTED")
            self.assertEqual(cancelled.json()["state"], "CANCEL_REQUESTED")
            self.assertFalse(cancelled.json()["cancellation_confirmed"])
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
            class Session:
                async def start(self):
                    return self
                async def close(self):
                    return None
            class RuntimeStub:
                def acquire(self, _task_id):
                    return Session()
            with patch.object(main, "_task_store", store), \
                 patch.object(main, "_task_runtime", RuntimeStub()), \
                 patch.object(openswarm_agent, "run_openswarm_task", new=AsyncMock(return_value='{"status":"COMPLETED"}')), \
                 patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
                response = await self.client.post(
                    "/tasks/resume-api/resume", headers={"Authorization": "Bearer test-secret"}
                )
            self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["result"], '{"status":"COMPLETED"}')

    async def test_execute_fails_closed_when_redis_readiness_is_false(self):
        class RuntimeStub:
            def health(self, _active):
                return {"ready": False, "redis_ready": False, "auth_ready": True,
                        "checkpoints_ready": False, "recovery_state": "RECOVERY_BLOCKED",
                        "draining": False, "server_id": "test", "instance_id": "test"}
        with patch.object(main, "_task_runtime", RuntimeStub()), \
             patch.object(main, "run_openswarm_task", new=AsyncMock()) as runner, \
             patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post("/execute", headers={"Authorization": "Bearer test-secret"}, json={
                "task_id": "redis-gated", "prompt": "Research this", "plugin_id": "openswarm_agent"})
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["error_code"], "BACKEND_NOT_READY")
        runner.assert_not_awaited()

    async def test_execute_rejects_new_work_when_server_is_draining(self):
        class DrainingRuntime:
            def health(self, _active):
                return {"status": "ready", "ready": True, "auth_ready": True,
                    "redis_ready": True, "checkpoints_ready": True,
                    "checkpoint_storage_ready": True, "lease_coordinator_ready": True,
                    "lease_ready": True, "accepting_new_tasks": False, "draining": True,
                    "server_id": "server-a", "instance_id": "instance-a",
                    "recovery_state": "LEASE_ACTIVE"}
        with patch.object(main, "_task_runtime", DrainingRuntime()), \
             patch.object(main, "run_playwright_task", new=AsyncMock()) as runner, \
             patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post("/execute",
                headers={"Authorization": "Bearer test-secret"},
                json={"task_id": "draining-task", "prompt": "Read https://example.com"})
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["error_code"], "SERVER_DRAINING")
        self.assertEqual(response.json()["task_id"], "draining-task")
        self.assertFalse(response.json()["accepting_new_tasks"])
        self.assertTrue(response.json()["recovery_action"])
        runner.assert_not_awaited()

    async def test_unconfigured_tool_fails_closed_even_when_approved(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post(
                "/tools/terminal.execute/execute",
                headers={"Authorization": "Bearer test-secret"},
                json={"payload": {"command": "echo unsafe"}, "approved": True},
            )
        self.assertEqual(response.status_code, 503)
        self.assertIn("no configured execution adapter", response.json()["detail"])

    async def test_browser_interaction_endpoint_requires_approval_flag(self):
        payload = {
            "url": "https://tool.example/generate",
            "field_label": "Prompt",
            "text": "a cat",
            "submit_button": "Generate",
        }
        with patch("browser_scraper.run_playwright_interaction", new=AsyncMock(return_value={"text": "Generated"})) as browser, \
             patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            headers = {"Authorization": "Bearer test-secret"}
            denied = await self.client.post("/tools/browser.interact/execute", headers=headers, json={"payload": payload})
            allowed = await self.client.post("/tools/browser.interact/execute", headers=headers, json={"payload": payload, "approved": True})
        self.assertEqual(denied.status_code, 403)
        self.assertIn("requires explicit user approval", denied.json()["detail"])
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.json()["result"], {"text": "Generated"})
        browser.assert_awaited_once()

    async def test_task_inspection_returns_not_found_for_unknown_task(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.get(
                "/tasks/does-not-exist",
                headers={"Authorization": "Bearer test-secret"},
            )
        self.assertEqual(response.status_code, 404)


if __name__ == "__main__":
    unittest.main()
