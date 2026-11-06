import os
import unittest
from unittest.mock import AsyncMock, patch

import httpx

from main import app
import main
from model_provider import (
    NVIDIA_BASE_URL,
    NVIDIA_DEFAULT_MODEL,
    OPENROUTER_BASE_URL,
    select_provider,
)


class DirectBrowserExecutionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        class Session:
            async def start(self): return self
            async def close(self): return None
        class ReadyRuntime:
            draining = False
            server_id = "test-server"
            instance_id = "test-instance"
            def health(self, active_count):
                return {"status": "ready", "ready": True, "auth_ready": True,
                    "drive_ready": True, "checkpoints_ready": True,
                    "checkpoint_storage_ready": True, "lease_coordinator_ready": True,
                    "lease_ready": True, "accepting_new_tasks": True, "draining": False,
                    "recovery_state": "LEASE_ACTIVE", "server_id": self.server_id,
                    "instance_id": self.instance_id, "active_count": active_count}
            def acquire(self, _task_id): return Session()
        self.runtime_patch = patch("main._drive_runtime", ReadyRuntime())
        self.runtime_patch.start()
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app, raise_app_exceptions=False),
            base_url="http://testserver",
        )

    async def asyncTearDown(self) -> None:
        await self.client.aclose()
        self.runtime_patch.stop()

    async def test_root_and_health_are_available_without_backend_secrets(self) -> None:
        class NotReadyRuntime:
            def health(self, _active):
                return {"status": "not_ready", "ready": False, "auth_ready": False,
                    "drive_ready": False, "checkpoint_storage_ready": False,
                    "lease_coordinator_ready": False, "accepting_new_tasks": False,
                    "draining": False, "recovery_state": "RECOVERY_BLOCKED"}
        with patch.object(main, "_drive_runtime", NotReadyRuntime()):
            root = await self.client.get("/")
            health = await self.client.get("/health")
        self.assertEqual(root.status_code, 200)
        self.assertEqual(root.json()["status"], "ok")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()["status"], "not_ready")
        self.assertFalse(health.json()["accepting_new_tasks"])

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
            with self.assertLogs("main", level="WARNING") as captured:
                response = await self.post_execute()
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["status"], "error")
        self.assertTrue(any("auth=failed status=401" in entry for entry in captured.output))
        self.assertFalse(any("test-secret" in entry for entry in captured.output))

    async def test_execute_payload_validation_failure_is_traced(self) -> None:
        with self.assertLogs("main", level="WARNING") as captured:
            response = await self.client.post(
                "/execute",
                headers={"Authorization": "Bearer validation-test-secret"},
                json={"task_id": "missing-prompt"},
            )

        self.assertEqual(response.status_code, 422)
        self.assertTrue(any("execute_validation task_id=missing-prompt payload_validation=failed" in entry for entry in captured.output))
        self.assertFalse(any("validation-test-secret" in entry for entry in captured.output))

    async def test_wrong_bearer_token_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute("Bearer wrong-secret")
        self.assertEqual(response.status_code, 401)

    async def test_non_bearer_authorization_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            response = await self.post_execute("Basic test-secret")
        self.assertEqual(response.status_code, 401)

    async def test_prompt_url_is_passed_directly_to_playwright(self) -> None:
        environment = {
            "APP_CLIENT_TOKEN": "test-secret",
            "OPEN_SWARM_EXECUTE_URL": "https://unused.example/execute",
        }
        with patch.dict(os.environ, environment, clear=False):
            with patch("main.run_playwright_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "Extracted actual page content."
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"result": "Extracted actual page content."})
        browser_task.assert_awaited_once_with(
            "https://example.com",
            allowed_domains=["example.com"],
            action="scrape",
            link_text=None,
        )

    async def test_browser_plugin_uses_raw_playwright_directly(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_playwright_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "Page text"
                response = await self.client.post(
                    "/execute",
                    headers={"Authorization": "Bearer test-secret"},
                    json={"task_id": "browser-1", "prompt": "https://example.com", "plugin_id": "browser_use"},
                )
        self.assertEqual(response.status_code, 200)
        browser_task.assert_awaited_once_with(
            "https://example.com",
            allowed_domains=["example.com"],
            action="scrape",
            link_text=None,
        )

    async def test_openswarm_plugin_runs_manager_graph_without_fabricating_output(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_openswarm_task", new_callable=AsyncMock) as swarm:
                swarm.return_value = "Manager delegated to a real specialist and returned this answer."
                with self.assertLogs("main", level="INFO") as captured:
                    response = await self.client.post(
                        "/execute",
                        headers={"Authorization": "Bearer test-secret"},
                        json={
                            "task_id": "swarm-1",
                            "prompt": "Go to https://example.com and return the page title",
                            "plugin_id": "openswarm_agent",
                            "plugins": ["openswarm_agent"],
                            "think_harder": True,
                        },
                    )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"result": "Manager delegated to a real specialist and returned this answer."})
        swarm.assert_awaited_once()
        self.assertTrue(any("request_received method=POST path=/execute" in entry for entry in captured.output))
        self.assertTrue(any("task_id=swarm-1 auth=passed payload_validation=passed" in entry for entry in captured.output))
        self.assertTrue(any("task_id=swarm-1 execution_status=success status=200" in entry for entry in captured.output))
        self.assertFalse(any("test-secret" in entry for entry in captured.output))

    async def test_multi_plugin_request_routes_to_swarm_with_fmhy_and_github_domains(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_openswarm_task", new_callable=AsyncMock) as swarm:
                swarm.return_value = "Combined browser result"
                response = await self.client.post(
                    "/execute",
                    headers={"Authorization": "Bearer test-secret"},
                    json={
                        "task_id": "multi-1",
                        "prompt": "Find the best open-source video editor on FMHY and inspect its GitHub repository.",
                        "plugin_id": "fmhy_browser",
                        "plugins": ["fmhy_browser", "github_search"],
                    },
                )
        self.assertEqual(response.status_code, 200)
        kwargs = swarm.await_args.kwargs
        self.assertEqual(kwargs["plugin_ids"], ["fmhy_browser", "github_search"])
        self.assertEqual(kwargs["allowed_domains"], ["github.com", "fmhy.net"])

    async def test_think_harder_requests_thirty_steps_without_model_override(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_openswarm_task", new_callable=AsyncMock) as swarm:
                swarm.return_value = "Deep result from the selected provider."
                response = await self.client.post(
                    "/execute",
                    headers={"Authorization": "Bearer test-secret"},
                    json={
                        "task_id": "deep-1",
                        "prompt": "Write a careful implementation plan.",
                        "plugin_id": "openswarm_agent",
                        "think_harder": True,
                        "browser_max_steps": 15,
                    },
                )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(swarm.await_args.kwargs["max_steps"], 30)

    async def test_browser_scraping_does_not_require_or_use_inference_keys(self) -> None:
        environment = {
            "APP_CLIENT_TOKEN": "test-secret",
            "NVIDIA_API_KEY": "nvidia-test-key",
            "NVIDIA_MODEL": "nvidia/stale-model-from-colab",
        }
        with patch.dict(os.environ, environment, clear=False):
            with patch("main.run_playwright_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "Actual page text."
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 200)
        browser_task.assert_awaited_once_with(
            "https://example.com",
            allowed_domains=["example.com"],
            action="scrape",
            link_text=None,
        )

    async def test_missing_public_url_is_rejected_before_browser_starts(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_playwright_task", new_callable=AsyncMock) as browser_task:
                response = await self.post_execute("Bearer test-secret", "Summarize this page.")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Include at least one public http(s) URL", response.json()["error"])
        browser_task.assert_not_awaited()

    async def test_fmhy_plugin_clicks_the_matching_link_text(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_playwright_task", new_callable=AsyncMock) as browser_task:
                browser_task.return_value = "FMHY tool result"
                response = await self.client.post(
                    "/execute",
                    headers={"Authorization": "Bearer test-secret"},
                    json={
                        "task_id": "fmhy-1",
                        "prompt": "Find a public image generator on FMHY and generate a cat.",
                        "plugin_id": "fmhy_browser",
                    },
                )
        self.assertEqual(response.status_code, 200)
        browser_task.assert_awaited_once_with(
            "https://fmhy.net",
            allowed_domains=["fmhy.net"],
            action="click",
            link_text="image generator",
        )

    async def test_private_hostname_is_rejected(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch("main.run_playwright_task", new_callable=AsyncMock) as browser_task:
                response = await self.post_execute(
                    "Bearer test-secret", "Read http://localhost:7860/health"
                )
        self.assertEqual(response.status_code, 400)
        self.assertIn("public internet hostnames", response.json()["error"])
        browser_task.assert_not_awaited()

    async def test_browser_exception_is_logged_and_returned_as_json_500(self) -> None:
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-secret"}):
            with patch(
                "main.run_playwright_task",
                new_callable=AsyncMock,
                side_effect=RuntimeError("provider unavailable"),
            ):
                response = await self.post_execute("Bearer test-secret")
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.headers["content-type"].split(";")[0], "application/json")
        self.assertIn("Playwright browser execution failed (RuntimeError)", response.json()["error"])
        self.assertIn("full traceback is in the colab backend log", response.json()["error"].lower())
        self.assertNotIn("Traceback", response.text)


class InferenceProviderSelectionTests(unittest.TestCase):
    def test_nvidia_takes_priority_and_ignores_stale_model_overrides(self) -> None:
        with patch.dict(os.environ, {"NVIDIA_MODEL": "nvidia/stale-model-from-colab"}, clear=True):
            provider = select_provider("nvidia/custom-model", "nvidia-key", "openrouter-key")
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
            provider = select_provider(None, None, "openrouter-key")
        self.assertEqual(provider, ("OpenRouter", "openrouter-key", "openrouter/custom-model", OPENROUTER_BASE_URL))

    def test_requested_model_overrides_openrouter_default(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            provider = select_provider("openrouter/requested-model", None, "openrouter-key")
        self.assertEqual(provider[0], "OpenRouter")
        self.assertEqual(provider[2], "openrouter/requested-model")

    def test_local_ollama_remains_last_fallback(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            provider = select_provider(None, None, None)
        self.assertEqual(provider[0], "Ollama")
        self.assertIsNone(provider[1])


if __name__ == "__main__":
    unittest.main()
