import base64
import os
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
import json

from agent_registry import build_default_registry
from task_state import TaskStateStore
from tool_registry import Capability, CapabilityRegistry, build_default_capability_registry
import openswarm_agent
import httpx
from tool_registry import _DuckDuckGoResultParser


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

    async def test_github_adapters_are_enabled_only_when_colab_token_is_present(self):
        with patch.dict(os.environ, {"GITHUB_TOKEN": "test-token"}):
            descriptions = {item["id"]: item for item in build_default_capability_registry().describe()}
        self.assertTrue(descriptions["github.read"]["adapter_configured"])
        self.assertTrue(descriptions["github.write"]["adapter_configured"])
        self.assertTrue(descriptions["github.pull_request"]["adapter_configured"])

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

        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"GITHUB_TOKEN": "test-token"}), \\
             patch("tool_registry.httpx.AsyncClient", side_effect=client_factory):
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
            plan = {"summary": "Commit the reviewed change", "steps": [{
                "id": "push-1", "agent_id": "terminal", "objective": "Run approved terminal action",
                "depends_on": [], "requires_approval": False,
            }]}
            executor = AsyncMock(return_value={
                "agent_id": "github", "status": "EXECUTED", "verified": True, "output": "command completed"
            })
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan), \
                 patch.object(openswarm_agent, "_run_specialist", new=executor):
                first = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="approval-task", prompt="Run approved terminal action", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
                self.assertEqual(first["status"], "WAITING_FOR_USER")
                self.assertEqual(store.get("approval-task")["state"], "WAITING_FOR_USER")
                self.assertEqual(executor.await_count, 0)

                second = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="approval-task", prompt="Run approved terminal action", plugin_id=None,
                    allowed_domains=[], max_steps=5, approved_step_ids=["push-1"],
                ))
                self.assertTrue(second["verified"])
                self.assertEqual(store.get("approval-task")["state"], "COMPLETED")
                self.assertEqual(executor.await_count, 1)




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
                store.transition("cancel-task", "CANCELLED", "test_cancel_requested")
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
                store.transition("pause-task", "PAUSED", "test_pause_requested")
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
