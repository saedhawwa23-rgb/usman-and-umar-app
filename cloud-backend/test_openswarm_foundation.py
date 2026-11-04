import os
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
import json

from agent_registry import build_default_registry
from task_state import TaskStateStore
from tool_registry import Capability, CapabilityRegistry, build_default_capability_registry
import openswarm_agent
from tool_registry import _DuckDuckGoResultParser


class AgentRegistryTests(unittest.TestCase):
    def test_specialist_team_is_registered(self):
        registry = build_default_registry()
        ids = {agent["id"] for agent in registry.all()}
        self.assertTrue({"manager", "planner", "research", "browser", "coder", "terminal",
                         "github", "files", "automation", "android", "data", "documents",
                         "drive", "security", "verification", "delivery"} <= ids)


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
    async def test_high_impact_agent_waits_for_explicit_approval_then_resumes_same_task(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(directory + "/approval.sqlite")
            plan = {"summary": "Commit the reviewed change", "steps": [{
                "id": "push-1", "agent_id": "github", "objective": "Push reviewed commit",
                "depends_on": [], "requires_approval": False,
            }]}
            executor = AsyncMock(return_value={
                "agent_id": "github", "status": "EXECUTED", "verified": True, "output": "commit pushed"
            })
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan), \
                 patch.object(openswarm_agent, "_run_specialist", new=executor):
                first = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="approval-task", prompt="Push the reviewed commit", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                ))
                self.assertEqual(first["status"], "WAITING_FOR_USER")
                self.assertEqual(store.get("approval-task")["state"], "WAITING_FOR_USER")
                self.assertEqual(executor.await_count, 0)

                second = json.loads(await openswarm_agent.run_openswarm_task(
                    task_id="approval-task", prompt="Push the reviewed commit", plugin_id=None,
                    allowed_domains=[], max_steps=5, approved_step_ids=["push-1"],
                ))
                self.assertTrue(second["verified"])
                self.assertEqual(store.get("approval-task")["state"], "COMPLETED")
                self.assertEqual(executor.await_count, 1)

    async def test_unknown_approval_does_not_advance_waiting_task(self):
        with tempfile.TemporaryDirectory() as directory:
            store = TaskStateStore(directory + "/approval.sqlite")
            plan = {"summary": "Commit the reviewed change", "steps": [{
                "id": "push-1", "agent_id": "github", "objective": "Push reviewed commit",
                "depends_on": [], "requires_approval": False,
            }]}
            with patch.object(openswarm_agent, "TASKS", store), \
                 patch.object(openswarm_agent, "_manager_plan", return_value=plan):
                await openswarm_agent.run_openswarm_task(
                    task_id="approval-invalid", prompt="Push the reviewed commit", plugin_id=None,
                    allowed_domains=[], max_steps=5,
                )
                with self.assertRaisesRegex(RuntimeError, "unknown or non-approval"):
                    await openswarm_agent.run_openswarm_task(
                        task_id="approval-invalid", prompt="Push the reviewed commit", plugin_id=None,
                        allowed_domains=[], max_steps=5, approved_step_ids=["wrong-step"],
                    )
                self.assertEqual(store.get("approval-invalid")["state"], "WAITING_FOR_USER")

if __name__ == "__main__":
    unittest.main()
