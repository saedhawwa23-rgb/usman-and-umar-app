import base64
import os
import tempfile
import subprocess
import time
import unittest
from unittest.mock import AsyncMock, patch
import json

from agent_registry import build_default_registry
from task_state import TaskStateStore
from tool_registry import Capability, CapabilityRegistry, build_default_capability_registry
import openswarm_agent
import tool_registry
import httpx
from tool_registry import _DuckDuckGoResultParser
from drive_task_lease import DriveTaskLeaseCoordinator, TaskLease
from drive_task_runtime import DriveTaskRuntime


class AgentRegistryTests(unittest.TestCase):
    def test_specialist_team_is_registered(self):
        registry = build_default_registry()
        ids = {agent["id"] for agent in registry.all()}
        self.assertTrue({"manager", "planner", "research", "browser", "coder", "terminal",
                         "github", "files", "automation", "android", "data", "documents",
                         "drive", "security", "verification", "delivery", "memory"} <= ids)


class TaskStateTests(unittest.TestCase):
    def test_lifecycle_is_persisted_and_invalid_transition_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(directory + "/tasks.sqlite")
            store.create("task-1", "test objective")
            store.transition("task-1", "PLANNING", "planning_started")
            store.transition("task-1", "READY", "plan_ready")
            store.transition("task-1", "RUNNING", "execution_started")
            store.transition("task-1", "VERIFYING", "verification_started")
            store.transition("task-1", "COMPLETED", "verification_passed")
            self.assertEqual(store.get("task-1")["state"], "COMPLETED")
            self.assertEqual(len(store.events("task-1")), 6)
            with self.assertRaises(ValueError):
                store.transition("task-1", "RUNNING", "invalid_reopen")


class CapabilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_default_registry_exposes_real_and_unconfigured_adapters_separately(self):
        registry = build_default_capability_registry()
        descriptions = {item["id"]: item for item in registry.describe()}
        self.assertTrue(descriptions["browser.extract"]["adapter_configured"])
        self.assertTrue(descriptions["research.search"]["adapter_configured"])
        self.assertTrue(descriptions["files.read"]["adapter_configured"])
        self.assertTrue(descriptions["files.write"]["adapter_configured"])
        self.assertTrue(descriptions["data.analyze"]["adapter_configured"])
        self.assertTrue(descriptions["documents.create"]["adapter_configured"])
        self.assertFalse(descriptions["github.write"]["adapter_configured"])
        self.assertFalse(descriptions["drive.upload"]["adapter_configured"])
        self.assertFalse(descriptions["memory.read"]["adapter_configured"])
        self.assertFalse(descriptions["terminal.execute"]["adapter_configured"])

    async def test_github_adapters_are_enabled_only_when_colab_token_is_present(self):
        with patch.dict(os.environ, {"GITHUB_TOKEN": "test-token"}):
            descriptions = {item["id"]: item for item in build_default_capability_registry().describe()}
        self.assertTrue(descriptions["github.read"]["adapter_configured"])
        self.assertTrue(descriptions["github.write"]["adapter_configured"])
        self.assertTrue(descriptions["github.pull_request"]["adapter_configured"])


    async def test_terminal_requires_approval_and_runs_only_inside_bubblewrap(self):
        with tempfile.TemporaryDirectory() as directory:
            command_paths = {"bwrap": "/usr/bin/bwrap", "prlimit": "/usr/bin/prlimit", "echo": "/usr/bin/echo"}
            with patch.dict(os.environ, {"OPENSWARM_ENABLE_SANDBOX": "1", "OPENSWARM_WORKSPACE": directory}):
                with patch.object(tool_registry.shutil, "which", side_effect=lambda command: command_paths.get(command)):
                    with patch.object(tool_registry.subprocess, "run",
                                      return_value=subprocess.CompletedProcess(["bwrap"], 0, "hello\n", "")) as run:
                        registry = build_default_capability_registry()
                        payload = {"argv": ["echo", "hello"], "cwd": ".", "timeout_seconds": 10}
                        with self.assertRaises(PermissionError):
                            await registry.execute("terminal.execute", payload)
                        result = await registry.execute("terminal.execute", payload, approved=True)
            self.assertTrue(result["verified"])
            self.assertEqual(result["stdout"], "hello\n")
            sandbox_argv = run.call_args.args[0]
            self.assertIn("--unshare-all", sandbox_argv)
            self.assertIn("--clearenv", sandbox_argv)
            self.assertFalse(run.call_args.kwargs["shell"])

    async def test_high_risk_capability_requires_approval(self):
        registry = CapabilityRegistry()
        async def run(_payload):
            return {"ok": True}
        registry.register(Capability("github.push", "Push code", "cloud", risk="high", configured=True), run)
        with self.assertRaises(PermissionError):
            await registry.execute("github.push", {})
        self.assertEqual(await registry.execute("github.push", {}, approved=True), {"ok": True})

    async def test_unconfigured_capability_never_fakes_success(self):
        registry = CapabilityRegistry()
        registry.register(Capability("drive.upload", "Upload file", "cloud"))
        with self.assertRaisesRegex(RuntimeError, "no configured execution adapter"):
            await registry.execute("drive.upload", {"path": "file.bin"})




class GitHubAdapterTests(unittest.IsolatedAsyncioTestCase):

    async def test_github_write_requires_approval_and_verifies_readback(self):
        stored = {}

        def handler(request):
            self.assertEqual(request.headers.get("Authorization"), "Bearer test-token")
            if request.method == "GET" and request.url.path.endswith("/contents/src/demo.py"):
                if "content" not in stored:
                    return httpx.Response(404, json={"message": "Not Found"})
                encoded = base64.b64encode(stored["content"]).decode("ascii")
                return httpx.Response(200, json={"type": "file", "size": len(stored["content"]),
                                                  "sha": "file-sha", "content": encoded})
            if request.method == "PUT" and request.url.path.endswith("/contents/src/demo.py"):
                body = json.loads(request.content.decode("utf-8"))
                stored["content"] = base64.b64decode(body["content"])
                return httpx.Response(200, json={
                    "commit": {"sha": "commit-sha"},
                    "content": {"sha": "file-sha", "html_url": "https://github.com/example/repo/blob/main/src/demo.py"},
                })
            return httpx.Response(404, json={"message": "Unexpected mocked route"})

        transport = httpx.MockTransport(handler)
        original_client = httpx.AsyncClient

        def client_factory(*args, **kwargs):
            return original_client(transport=transport, **kwargs)

        with patch.dict(os.environ, {"GITHUB_TOKEN": "test-token"}):
            with patch("tool_registry.httpx.AsyncClient", side_effect=client_factory):
                registry = build_default_capability_registry()
                payload = {"repo": "example/repo", "path": "src/demo.py", "content": "print('hello')",
                           "message": "Add demo", "branch": "main"}
                with self.assertRaises(PermissionError):
                    await registry.execute("github.write", payload)
                result = await registry.execute("github.write", payload, approved=True)
                self.assertTrue(result["verified"])
                read = await registry.execute("github.read", {"repo": "example/repo", "path": "src/demo.py"})
                self.assertEqual(read["content"], "print('hello')")


class ResearchParserTests(unittest.TestCase):
    def test_extracts_search_result_title_and_redirect_url(self):
        parser = _DuckDuckGoResultParser()
        parser.feed('<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Farticle">Example Article</a>')
        self.assertEqual(len(parser.results), 1)
        self.assertEqual(parser.results[0]["title"], "Example Article")
        from urllib.parse import parse_qs, urljoin, urlsplit
        candidate = urljoin("https://html.duckduckgo.com", parser.results[0]["href"])
        target = parse_qs(urlsplit(candidate).query)["uddg"][0]
        self.assertEqual(target, "https://example.com/article")

class ResearchAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def test_search_retrieves_source_page_text_before_marking_research_verified(self):
        html = '<a class="result__a" href="https://example.com/article">Example Article</a>'
        transport = httpx.MockTransport(lambda _request: httpx.Response(200, text=html))
        original_client = httpx.AsyncClient

        def client_factory(*args, **kwargs):
            return original_client(transport=transport, **kwargs)

        browser_fetch = AsyncMock(return_value="Actual article body with source evidence.")
        with patch("tool_registry.httpx.AsyncClient", side_effect=client_factory), \
             patch("browser_scraper.run_playwright_task", new=browser_fetch):
            registry = build_default_capability_registry()
            result = await registry.execute("research.search", {"query": "example research", "limit": 1})

        self.assertTrue(result["verified"])
        self.assertEqual(result["retrieved_count"], 1)
        self.assertEqual(result["sources"][0]["url"], "https://example.com/article")
        self.assertEqual(result["sources"][0]["content"], "Actual article body with source evidence.")
        self.assertTrue(result["sources"][0]["retrieved"])
        browser_fetch.assert_awaited_once_with(
            "https://example.com/article", action="scrape", allowed_domains=["example.com"]
        )


class WorkspaceToolTests(unittest.IsolatedAsyncioTestCase):


    async def test_persistent_memory_is_shared_and_write_requires_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "memory.sqlite"))
            registry = build_default_capability_registry(store)
            with self.assertRaises(PermissionError):
                await registry.execute("memory.write", {"key": "project", "value": {"branch": "feature/demo"}})
            saved = await registry.execute(
                "memory.write", {"key": "project", "value": {"branch": "feature/demo"}}, approved=True
            )
            self.assertTrue(saved["verified"])
            loaded = await registry.execute("memory.read", {"key": "project"})
            self.assertEqual(loaded["item"]["value"]["branch"], "feature/demo")
            matches = await registry.execute("memory.search", {"query": "feature demo"})
            self.assertEqual(matches["items"][0]["key"], "project")

    async def test_workspace_write_requires_approval_and_read_is_confined(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"OPENSWARM_WORKSPACE": directory}):
            registry = build_default_capability_registry()
            with self.assertRaises(PermissionError):
                await registry.execute("files.write", {"path": "reports/summary.md", "content": "# Report"})
            written = await registry.execute(
                "files.write", {"path": "reports/summary.md", "content": "# Report"}, approved=True
            )
            self.assertTrue(written["verified"])
            read = await registry.execute("files.read", {"path": "reports/summary.md"})
            self.assertEqual(read["content"], "# Report")
            with self.assertRaisesRegex(ValueError, "escapes"):
                await registry.execute("files.read", {"path": "../outside.txt"})



    async def test_coder_writes_generated_workspace_file_only_after_explicit_approval(self):
        class FakeModel:
            def invoke(self, _messages):
                return type("Response", (), {"content": "print('hello from OpenSwarm')\n"})()

        with tempfile.TemporaryDirectory() as directory, \
             patch.dict(os.environ, {"OPENSWARM_WORKSPACE": directory}):
            store = TaskStateStore(os.path.join(directory, "tasks.sqlite"))
            plan = {"summary": "Create a Python script", "steps": [{
                "id": "coder-write", "agent_id": "coder", "objective": "Write a hello-world Python script",
                "depends_on": [], "requires_approval": False,
                "tool_input": {"output_path": "src/hello.py"},
            }]}
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan), \
                 patch.object(openswarm_agent, "_model", return_value=FakeModel()):
                first = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="coder-write-task", prompt="Create a Python script", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
                self.assertEqual(first["status"], "WAITING_FOR_USER")
                self.assertFalse(os.path.exists(os.path.join(directory, "src", "hello.py")))
                second = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="coder-write-task", prompt="Create a Python script", plugin_id=None,
                    allowed_domains=[], max_steps=5, approved_step_ids=["coder-write"],
                ))
            artifact = os.path.join(directory, "src", "hello.py")
            self.assertTrue(os.path.isfile(artifact))
            self.assertEqual(open(artifact, encoding="utf-8").read(), "print('hello from OpenSwarm')\n")
            self.assertTrue(second["verified"])
            self.assertEqual(second["results"][0]["status"], "GENERATED_AND_WRITTEN")
            self.assertEqual(second["results"][0]["artifact_path"], "src/hello.py")

    async def test_verification_and_delivery_require_verified_dependencies(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"OPENSWARM_WORKSPACE": directory}):
            with open(os.path.join(directory, "evidence.txt"), "w", encoding="utf-8") as handle:
                handle.write("verified source evidence")
            store = TaskStateStore(os.path.join(directory, "tasks.sqlite"))
            plan = {"summary": "Read and verify evidence", "steps": [
                {"id": "read-1", "agent_id": "files", "objective": "Read evidence",
                 "depends_on": [], "requires_approval": False, "capability_id": "files.read",
                 "tool_input": {"path": "evidence.txt"}},
                {"id": "verify-1", "agent_id": "verification", "objective": "Verify file read",
                 "depends_on": ["read-1"], "requires_approval": False},
                {"id": "deliver-1", "agent_id": "delivery", "objective": "Prepare delivery manifest",
                 "depends_on": ["read-1", "verify-1"], "requires_approval": False},
            ]}
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan):
                report = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="verify-deliver", prompt="Read and verify evidence", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
            self.assertTrue(report["verified"])
            self.assertEqual(store.get("verify-deliver")["state"], "COMPLETED")
            self.assertEqual(report["results"][-1]["status"], "PACKAGED")

    async def test_document_specialist_waits_for_approval_then_creates_workspace_file(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"OPENSWARM_WORKSPACE": directory}):
            store = TaskStateStore(os.path.join(directory, "tasks.sqlite"))
            plan = {"summary": "Create a project note", "steps": [{
                "id": "doc-1", "agent_id": "documents", "objective": "Create a project note",
                "depends_on": [], "requires_approval": False, "capability_id": "documents.create",
                "tool_input": {"path": "reports/project.md", "markdown": "# Project\n\nReviewed draft."},
            }]}
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan):
                first = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="document-task", prompt="Create a project note", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
                self.assertEqual(first["status"], "WAITING_FOR_USER")
                self.assertFalse(os.path.exists(os.path.join(directory, "reports", "project.md")))
                second = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="document-task", prompt="Create a project note", plugin_id=None,
                    allowed_domains=[], max_steps=5, approved_step_ids=["doc-1"],
                ))
                self.assertTrue(second["verified"])
                self.assertEqual(store.get("document-task")["state"], "COMPLETED")
                with open(os.path.join(directory, "reports", "project.md"), encoding="utf-8") as handle:
                    self.assertEqual(handle.read(), "# Project\n\nReviewed draft.")

    async def test_csv_analysis_returns_numeric_summary_from_workspace(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"OPENSWARM_WORKSPACE": directory}):
            with open(os.path.join(directory, "metrics.csv"), "w", encoding="utf-8") as handle:
                handle.write("month,revenue\nJan,10\nFeb,20\n")
            result = await build_default_capability_registry().execute(
                "data.analyze", {"path": "metrics.csv"}
            )
            self.assertTrue(result["verified"])
            self.assertEqual(result["rows"], 2)
            self.assertEqual(result["numeric_summary"]["revenue"]["mean"], 15)


class OpenSwarmApprovalTests(unittest.IsolatedAsyncioTestCase):

    async def test_mocked_drive_lifecycle_receipts_and_confirmed_checkpoint(self):
        class Request:
            def __init__(self, value):
                self.value = value
            def execute(self):
                return self.value
        class Files:
            def __init__(self, service):
                self.service = service
            def get(self, **_kwargs):
                return Request({"id": "folder", "name": "USMAN_UMAR_CHECKPOINTS",
                                "mimeType": "application/vnd.google-apps.folder", "trashed": False})
            def list(self, **_kwargs):
                return Request({"files": []})
            def create(self, body, media_body=None, **_kwargs):
                file_id = f"probe-{len(self.service.contents) + 1}"
                self.service.contents[file_id] = media_body._fd.getvalue() if media_body else b""
                return Request({"id": file_id, **body})
            def get_media(self, fileId):
                return Request(self.service.contents[fileId])
            def delete(self, fileId):
                self.service.contents.pop(fileId, None)
                return Request({})
        class Service:
            def __init__(self):
                self.contents = {}
            def files(self):
                return Files(self)
        class Coordinator:
            heartbeat_seconds = 3600
            def __init__(self, service, _folder, server, instance):
                self.service, self.folder_id, self.server, self.instance = service, _folder, server, instance
                self.receipts, self.writes, self.checkpoints = {}, [], []
            def acquire(self, task_id):
                now = time.time()
                self.lease = TaskLease(task_id, self.server, self.instance, 1, now + 3600,
                    "ACTIVE", "lease-file", now, now, now)
                return self.lease
            def assert_owner(self, lease):
                if lease.owner_instance_id != self.instance:
                    raise RuntimeError("stale owner")
            def renew(self, lease):
                return lease
            def idempotency_key(self, task_id, step, action):
                return DriveTaskLeaseCoordinator.idempotency_key(task_id, step, action)
            def list_receipts(self, _task_id):
                return list(self.receipts.values())
            def write_receipt(self, task_id, step, action, state, *, details=None):
                key = self.idempotency_key(task_id, step, action)
                self.writes.append(state)
                doc = {"task_id": task_id, "step_name": step, "logical_action_id": action,
                       "idempotency_key": key, "state": state, "details": details or {}}
                self.receipts[key] = doc
                return doc
            def load_checkpoint(self, *_args, **_kwargs):
                return self.checkpoints[-1]
            def save_checkpoint(self, task_id, payload, *, owner, checkpoint_version):
                doc = {"task_id": task_id, "checkpoint_id": "cp-1",
                       "checkpoint_version": checkpoint_version, "payload": payload}
                self.checkpoints.append(doc)
                return doc

        coordinator_holder = {}
        def coordinator_factory(*args):
            coordinator_holder["coordinator"] = Coordinator(*args)
            return coordinator_holder["coordinator"]
        runtime = DriveTaskRuntime(service_factory=Service, coordinator_factory=coordinator_factory,
                                   folder_id="folder", server_id="test-server", instance_id="test-instance")
        with patch.dict(os.environ, {"APP_CLIENT_TOKEN": "test-token"}):
            health = runtime.health(0)
        self.assertEqual(health["status"], "ready")
        self.assertTrue(health["drive_ready"])
        self.assertTrue(health["checkpoint_storage_ready"])
        self.assertTrue(health["lease_coordinator_ready"])
        self.assertTrue(health["accepting_new_tasks"])
        # TaskStateStore opens a fresh SQLite connection per operation; use a
        # file-backed temporary DB because plain :memory: is connection-local.
        store = TaskStateStore(tempfile.mktemp(suffix="-openswarm-drive-test.sqlite3"))
        plan = {"summary": "Write one reviewed artifact", "steps": [{
            "id": "write-artifact", "agent_id": "files", "objective": "Write an artifact",
            "depends_on": [], "requires_approval": False, "capability_id": "files.write",
            "tool_input": {"path": "artifact.txt", "content": "hello"},
        }]}
        capabilities = CapabilityRegistry()
        executions = []
        async def write(_payload):
            executions.append("called")
            return {"verified": True, "path": "artifact.txt"}
        capabilities.register(Capability("files.write", "Write artifact", "workspace", risk="high", configured=True), write)
        session = await runtime.acquire("mocked-drive-task").start()
        with patch.object(openswarm_agent, "TASKS", store), \
             patch.object(openswarm_agent, "CAPABILITIES", capabilities), \
             patch.object(openswarm_agent, "_manager_plan", return_value=plan):
            pending = json.loads(await openswarm_agent.run_openswarm_task(
                task_id="mocked-drive-task", prompt="Write an artifact", plugin_id=None,
                allowed_domains=[], max_steps=3, lifecycle=session))
            self.assertEqual(pending["status"], "WAITING_FOR_USER")
            report = json.loads(await openswarm_agent.run_openswarm_task(
                task_id="mocked-drive-task", prompt="Write an artifact", plugin_id=None,
                allowed_domains=[], max_steps=3, approved_step_ids=["write-artifact"], lifecycle=session))
        await session.close()
        coordinator = coordinator_holder["coordinator"]
        self.assertTrue(report["verified"])
        self.assertEqual(executions, ["called"])
        self.assertEqual(coordinator.writes, ["PREPARED", "EXECUTING", "CONFIRMED"])
        self.assertEqual(len(coordinator.checkpoints), 1)
        payload = coordinator.checkpoints[0]["payload"]
        self.assertIn("plan", payload)
        self.assertIn("completed_steps", payload)
        self.assertIn("pending_steps", payload)
        self.assertIn("results", payload)
        self.assertIn("task_state", payload)
        self.assertEqual(payload["lease_generation"], 1)

    async def test_safe_specialist_retries_transient_timeout_and_records_event(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "retry.sqlite"))
            plan = {"summary": "Research a topic", "steps": [{
                "id": "research-1", "agent_id": "research", "objective": "Find reliable sources",
                "depends_on": [], "requires_approval": False,
            }]}
            executor = AsyncMock(side_effect=[
                TimeoutError("temporary network timeout"),
                {"agent_id": "research", "status": "EXECUTED", "verified": True, "output": "sources retrieved"},
            ])
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan), \
                 patch.object(openswarm_agent, "_run_specialist", new=executor):
                result = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="retry-task", prompt="Research a topic", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
                self.assertTrue(result["verified"])
                self.assertEqual(executor.await_count, 2)
                self.assertIn("agent_retry", [event["event"] for event in store.events("retry-task")])

    async def test_high_impact_agent_waits_for_explicit_approval_then_resumes_same_task(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(directory + "/approval.sqlite")
            plan = {"summary": "Run the reviewed command", "steps": [{
                "id": "terminal-1", "agent_id": "terminal", "objective": "Run approved terminal action",
                "depends_on": [], "requires_approval": True, "capability_id": "terminal.execute",
                "tool_input": {"argv": ["echo", "command completed"], "cwd": ".", "timeout_seconds": 10},
            }]}
            capability_registry = CapabilityRegistry()

            async def execute_terminal(_payload):
                return {"verified": True, "stdout": "command completed"}

            capability_registry.register(
                Capability("terminal.execute", "Run approved command", "cloud", risk="high", configured=True),
                execute_terminal,
            )
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "CAPABILITIES", capability_registry), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan):
                first = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="approval-task", prompt="Run approved terminal action", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
                self.assertEqual(first["status"], "WAITING_FOR_USER")
                self.assertEqual(store.get("approval-task")["state"], "WAITING_FOR_USER")

                second = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="approval-task", prompt="Run approved terminal action", plugin_id=None,
                    allowed_domains=[], max_steps=5, approved_step_ids=["terminal-1"],
                ))
                self.assertTrue(second["verified"])
                self.assertEqual(store.get("approval-task")["state"], "COMPLETED")



    async def test_cancelled_task_stops_at_next_execution_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "cancel.sqlite"))
            plan = {"summary": "Research then code", "steps": [
                {"id": "research-1", "agent_id": "research", "objective": "Collect sources",
                 "depends_on": [], "requires_approval": False},
                {"id": "coder-1", "agent_id": "coder", "objective": "Draft code",
                 "depends_on": ["research-1"], "requires_approval": False},
            ]}
            async def cancel_after_research(agent_id, objective, state):
                store.transition("cancel-task", "CANCEL_REQUESTED", "test_cancel_requested")
                return {"agent_id": agent_id, "status": "EXECUTED", "verified": True, "output": "sources"}
            executor = AsyncMock(side_effect=cancel_after_research)
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan), \
                 patch.object(openswarm_agent, "_run_specialist", new=executor):
                report = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="cancel-task", prompt="Research then code", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
            self.assertEqual(report["status"], "CANCELLED")
            self.assertEqual(store.get("cancel-task")["state"], "CANCELLED")
            self.assertEqual(executor.await_count, 1)

    async def test_paused_task_resumes_from_last_completed_step(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "pause.sqlite"))
            plan = {"summary": "Research then code", "steps": [
                {"id": "research-1", "agent_id": "research", "objective": "Collect sources",
                 "depends_on": [], "requires_approval": False},
                {"id": "coder-1", "agent_id": "coder", "objective": "Draft code from sources",
                 "depends_on": ["research-1"], "requires_approval": False},
            ]}
            async def pause_after_research(agent_id, objective, state):
                store.transition("pause-task", "PAUSE_REQUESTED", "test_pause_requested")
                return {"agent_id": agent_id, "status": "EXECUTED", "verified": True, "output": "sources"}
            first_executor = AsyncMock(side_effect=pause_after_research)
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan), \
                 patch.object(openswarm_agent, "_run_specialist", new=first_executor):
                paused = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="pause-task", prompt="Research then code", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
            self.assertEqual(paused["status"], "PAUSED")
            self.assertEqual(len(paused["results"]), 1)

            second_executor = AsyncMock(return_value={
                "agent_id": "coder", "status": "EXECUTED", "verified": True, "output": "code reviewed"
            })
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_run_specialist", new=second_executor):
                completed = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="pause-task", prompt="Research then code", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
            self.assertEqual(store.get("pause-task")["state"], "COMPLETED")
            self.assertTrue(completed["verified"])
            self.assertEqual([item["step_id"] for item in completed["results"]], ["research-1", "coder-1"])
            self.assertEqual(first_executor.await_count, 1)
            self.assertEqual(second_executor.await_count, 1)

    async def test_stale_approval_cannot_execute_without_saved_task_record(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(os.path.join(directory, "approval.sqlite"))
            with patch.object(openswarm_agent, "TASKS", store):
                with self.assertRaisesRegex(RuntimeError, "No saved pending-approval record"):
                    await openswarm_agent.run_openswarm_task(
                        task_id="lost-task", prompt="Push an old change", plugin_id=None,
                        allowed_domains=[], max_steps=5, approved_step_ids=["step-1"],
                    )
                with self.assertRaises(KeyError):
                    store.get("lost-task")

    async def test_unknown_approval_does_not_advance_waiting_task(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(directory + "/approval.sqlite")
            plan = {"summary": "Commit the reviewed change", "steps": [{
                "id": "push-1", "agent_id": "terminal", "objective": "Run approved terminal action",
                "depends_on": [], "requires_approval": False,
            }]}
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan):
                await openswarm_agent.run_openswarm_task(
                    task_id="approval-invalid", prompt="Run approved terminal action", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                )
                with self.assertRaisesRegex(RuntimeError, "unknown or non-approval"):
                    await openswarm_agent.run_openswarm_task(
                        task_id="approval-invalid", prompt="Run approved terminal action", plugin_id=None,
                        allowed_domains=[], max_steps=5, approved_step_ids=["wrong-step"],
                    )
                self.assertEqual(store.get("approval-invalid")["state"], "WAITING_FOR_USER")

if __name__ == "__main__":
    unittest.main()
