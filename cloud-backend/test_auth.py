import os
import unittest
from unittest.mock import patch

import httpx

from main import app


class BearerAuthenticationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://testserver",
        )

    async def asyncTearDown(self) -> None:
        await self.client.aclose()

    async def test_root_and_health_are_available_without_backend_secrets(self) -> None:
        for path in ("/", "/health"):
            response = await self.client.get(path)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["status"], "ok")

    async def post_execute(self, authorization: str | None = None) -> httpx.Response:
        headers = {"Authorization": authorization} if authorization else {}
        return await self.client.post(
            "/execute",
            headers=headers,
            json={"task_id": "task-1", "prompt": "test"},
        )

    async def test_missing_authorization_header_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute()
        self.assertEqual(response.status_code, 401)

    async def test_wrong_bearer_token_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute("Bearer wrong-secret")
        self.assertEqual(response.status_code, 401)

    async def test_non_bearer_authorization_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute("Basic test-secret")
        self.assertEqual(response.status_code, 401)

    async def test_mock_mode_returns_clearly_marked_test_output_without_upstream(self) -> None:
        environment = {"APP_CLIENT_TOKEN": "test-secret", "ENABLE_MOCK_EXECUTION": "true"}
        with patch.dict(os.environ, environment, clear=False):
            os.environ.pop("OPEN_SWARM_EXECUTE_URL", None)
            response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["status"], "mock")
        self.assertIs(payload["mock"], True)
        self.assertIn("[MOCK RESPONSE", payload["result"])
        self.assertEqual(payload["result"], payload["output"])

    async def test_live_mode_without_upstream_returns_json_503(self) -> None:
        environment = {"APP_CLIENT_TOKEN": "test-secret", "ENABLE_MOCK_EXECUTION": "false"}
        with patch.dict(os.environ, environment, clear=False):
            os.environ.pop("OPEN_SWARM_EXECUTE_URL", None)
            response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.headers["content-type"].split(";")[0], "application/json")
        self.assertEqual(response.json()["status"], "error")
        self.assertIn("OpenSwarm is not configured", response.json()["error"])

    async def test_unexpected_endpoint_error_returns_json_500(self) -> None:
        environment = {"APP_CLIENT_TOKEN": "test-secret", "ENABLE_MOCK_EXECUTION": "false"}
        with patch.dict(os.environ, environment, clear=False):
            with patch("main._mock_execution_enabled", side_effect=RuntimeError("test-only internal error")):
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.headers["content-type"].split(";")[0], "application/json")
        self.assertEqual(response.json()["status"], "error")
        self.assertNotIn("test-only internal error", response.text)


if __name__ == "__main__":
    unittest.main()
