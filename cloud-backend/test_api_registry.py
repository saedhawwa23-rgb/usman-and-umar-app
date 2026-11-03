import os
import unittest
from unittest.mock import patch

import httpx
from main import app


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
                         "drive", "security", "verification", "delivery"} <= ids)
        self.assertEqual(capabilities.status_code, 200)
        browser = next(item for item in capabilities.json()["capabilities"] if item["id"] == "browser")
        github = next(item for item in capabilities.json()["capabilities"] if item["id"] == "github")
        self.assertTrue(browser["adapter_configured"])
        self.assertFalse(github["adapter_configured"])


    async def test_unconfigured_tool_fails_closed_even_when_approved(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post(
                "/tools/terminal.execute",
                headers={"Authorization": "Bearer test-secret"},
                json={"payload": {"command": "echo unsafe"}, "approved": True},
            )
        self.assertEqual(response.status_code, 503)
        self.assertIn("no configured execution adapter", response.json()["detail"])

    async def test_task_inspection_returns_not_found_for_unknown_task(self):
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.get(
                "/tasks/does-not-exist",
                headers={"Authorization": "Bearer test-secret"},
            )
        self.assertEqual(response.status_code, 404)


if __name__ == "__main__":
    unittest.main()
