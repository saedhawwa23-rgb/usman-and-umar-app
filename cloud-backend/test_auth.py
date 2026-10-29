import os
import unittest
from unittest.mock import AsyncMock, patch

import httpx

from main import app


class DirectBrowserExecutionTests(unittest.IsolatedAsyncioTestCase):
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

    async def post_execute(
        self,
        authorization: str | None = None,
        prompt: str = "Read https://example.com and summarize the page.",
    ) -> httpx.Response:
        headers = {"Authorization": authorization} if authorization else {}
        return await self.client.post(
            "/execute",
            headers=headers,
            json={"task_id": "task-1", "prompt": prompt},
        )

    async def test_missing_authorization_header_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute()
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["status"], "error")

    async def test_wrong_bearer_token_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute("Bearer wrong-secret")
        self.assertEqual(response.status_code, 401)

    async def test_non_bearer_authorization_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute("Basic test-secret")
        self.assertEqual(response.status_code, 401)

    async def test_missing_upstream_url_runs_browser_use_directly(self) -> None:
        environment = {"APP_CLIENT_TOKEN": "test-secret"}
        with patch.dict(os.environ, environment, clear=False):
            os.environ.pop("OPEN_SWARM_EXECUTE_URL", None)
            with patch("main.run_browser_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "Extracted actual page content."
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"result": "Extracted actual page content."})
        browser_task.assert_awaited_once_with(
            "Read https://example.com and summarize the page.",
            allowed_domains=["example.com"],
            max_steps=8,
        )

    async def test_missing_public_url_is_rejected_before_browser_starts(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_browser_task", new_callable=AsyncMock) as browser_task:
                response = await self.post_execute("Bearer test-secret", "Summarize this page.")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Include at least one public http(s) URL", response.json()["error"])
        browser_task.assert_not_awaited()

    async def test_private_hostname_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_browser_task", new_callable=AsyncMock) as browser_task:
                response = await self.post_execute(
                    "Bearer test-secret", "Read http://localhost:7860/health"
                )
        self.assertEqual(response.status_code, 400)
        self.assertIn("public internet hostnames", response.json()["error"])
        browser_task.assert_not_awaited()

    async def test_browser_exception_is_logged_and_returned_as_json_500(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch(
                "main.run_browser_task",
                new_callable=AsyncMock,
                side_effect=RuntimeError("provider unavailable"),
            ):
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.headers["content-type"].split(";")[0], "application/json")
        self.assertIn("Browser Use failed (RuntimeError)", response.json()["error"])
        self.assertIn("full traceback is in the colab backend log", response.json()["error"].lower())
        self.assertNotIn("Traceback", response.text)


if __name__ == "__main__":
    unittest.main()
