import os
import unittest
from unittest.mock import AsyncMock, patch

import httpx

from main import app
from browser_use_interactive import (
    NVIDIA_BASE_URL,
    NVIDIA_DEFAULT_MODEL,
    OPENROUTER_BASE_URL,
    _select_provider,
)


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

    async def test_upstream_url_is_ignored_and_browser_use_runs_directly(self) -> None:
        environment = {
            "APP_CLIENT_TOKEN": "test-secret",
            "OPEN_SWARM_EXECUTE_URL": "https://unused.example/execute",
        }
        with patch.dict(os.environ, environment, clear=False):
            with patch("main.run_browser_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "Extracted actual page content."
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"result": "Extracted actual page content."})
        browser_task.assert_awaited_once_with(
            "Read https://example.com and summarize the page.",
            allowed_domains=["example.com"],
            model=None,
            max_steps=8,
        )

    async def test_nvidia_key_forces_the_provisioned_model_in_execute(self) -> None:
        environment = {
            "APP_CLIENT_TOKEN": "test-secret",
            "NVIDIA_API_KEY": "nvidia-test-key",
            "NVIDIA_MODEL": "nvidia/stale-model-from-colab",
        }
        with patch.dict(os.environ, environment, clear=False):
            with patch("main.run_browser_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "Actual Browser Use output."
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 200)
        browser_task.assert_awaited_once_with(
            "Read https://example.com and summarize the page.",
            allowed_domains=["example.com"],
            model=NVIDIA_DEFAULT_MODEL,
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


class InferenceProviderSelectionTests(unittest.TestCase):
    def test_nvidia_takes_priority_and_ignores_stale_model_overrides(self) -> None:
        with patch.dict(os.environ, {"NVIDIA_MODEL": "nvidia/stale-model-from-colab"}, clear=True):
            provider = _select_provider("nvidia/custom-model", "nvidia-key", "openrouter-key")
        self.assertEqual(
            provider,
            ("NVIDIA", "nvidia-key", NVIDIA_DEFAULT_MODEL, NVIDIA_BASE_URL),
        )
        self.assertEqual(
            NVIDIA_BASE_URL + "/chat/completions",
            "https://integrate.api.nvidia.com/v1/chat/completions",
        )

    def test_openrouter_is_fallback_when_nvidia_key_is_missing(self) -> None:
        with patch.dict(os.environ, {"OPENROUTER_MODEL": "openrouter/custom-model"}, clear=True):
            provider = _select_provider(None, None, "openrouter-key")
        self.assertEqual(provider, ("OpenRouter", "openrouter-key", "openrouter/custom-model", OPENROUTER_BASE_URL))

    def test_requested_model_overrides_openrouter_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            provider = _select_provider("openrouter/requested-model", None, "openrouter-key")
        self.assertEqual(provider[0], "OpenRouter")
        self.assertEqual(provider[2], "openrouter/requested-model")

    def test_local_ollama_remains_last_fallback(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            provider = _select_provider(None, None, None)
        self.assertEqual(provider[0], "Ollama")
        self.assertIsNone(provider[1])


if __name__ == "__main__":
    unittest.main()
