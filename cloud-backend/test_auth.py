import os
import unittest
from unittest.mock import patch

import httpx

from main import app


class BearerAuthenticationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://testserver",
        )

    async def asyncTearDown(self) -> None:
        await self.client.aclose()

    async def test_missing_authorization_header_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post(
                "/execute",
                json={"task_id": "task-1", "prompt": "test"},
            )
        self.assertEqual(response.status_code, 401)

    async def test_wrong_bearer_token_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post(
                "/execute",
                headers={"Authorization": "Bearer wrong-secret"},
                json={"task_id": "task-1", "prompt": "test"},
            )
        self.assertEqual(response.status_code, 401)

    async def test_non_bearer_authorization_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.client.post(
                "/execute",
                headers={"Authorization": "Basic test-secret"},
                json={"task_id": "task-1", "prompt": "test"},
            )
        self.assertEqual(response.status_code, 401)

    async def test_correct_bearer_passes_authentication_then_requires_real_upstream_config(self) -> None:
        environment = {"APP_CLIENT_TOKEN": "test-secret"}
        with patch.dict(os.environ, environment, clear=False):
            os.environ.pop("OPEN_SWARM_EXECUTE_URL", None)
            response = await self.client.post(
                "/execute",
                headers={"Authorization": "Bearer test-secret"},
                json={"task_id": "task-1", "prompt": "test"},
            )
        self.assertEqual(response.status_code, 503)
        self.assertIn("OpenSwarm is not configured", response.text)


if __name__ == "__main__":
    unittest.main()
