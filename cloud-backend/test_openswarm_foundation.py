import tempfile
import unittest

from agent_registry import build_default_registry
from task_state import TaskStateStore
from tool_registry import Capability, CapabilityRegistry, build_default_capability_registry


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
        self.assertFalse(descriptions["github.write"]["adapter_configured"])
        self.assertFalse(descriptions["drive.upload"]["adapter_configured"])

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


if __name__ == "__main__":
    unittest.main()
