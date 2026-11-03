"""Extensible OpenSwarm specialist registry with explicit capability availability."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Callable, Any


@dataclass(frozen=True)
class AgentDefinition:
    id: str
    name: str
    description: str
    capabilities: tuple[str, ...]
    execution_location: str = "cloud"
    requires_model: bool = False


class AgentRegistry:
    def __init__(self) -> None:
        self._agents: dict[str, AgentDefinition] = {}

    def register(self, agent: AgentDefinition) -> None:
        if agent.id in self._agents:
            raise ValueError(f"Agent already registered: {agent.id}")
        self._agents[agent.id] = agent

    def replace(self, agent: AgentDefinition) -> None:
        self._agents[agent.id] = agent

    def get(self, agent_id: str) -> AgentDefinition | None:
        return self._agents.get(agent_id)

    def all(self) -> list[dict[str, Any]]:
        return [asdict(agent) for agent in self._agents.values()]


def build_default_registry() -> AgentRegistry:
    registry = AgentRegistry()
    agents = (
        ("manager", "Manager / Orchestrator", "Plans work, delegates specialists, tracks execution and assembles results.", ("planning", "delegation", "coordination"), True),
        ("planner", "Planner", "Decomposes objectives into ordered, dependency-aware steps.", ("planning", "dependencies"), True),
        ("research", "Research", "Collects evidence from approved sources and records source URLs.", ("web_research", "source_analysis"), True),
        ("browser", "Browser", "Uses bounded Playwright navigation and page extraction.", ("browser.navigate", "browser.extract", "browser.click"), False),
        ("coder", "Coding", "Produces code and patches; execution is only claimed when a runner verifies it.", ("code.generate", "code.review"), True),
        ("terminal", "Terminal", "Runs approved commands in a configured isolated execution adapter.", ("terminal.execute",), False),
        ("github", "GitHub", "Uses an authorized GitHub adapter for repository and PR operations.", ("github.read", "github.write"), False),
        ("files", "File Manager", "Reads and writes files through a configured workspace adapter.", ("files.read", "files.write"), False),
        ("automation", "Automation", "Runs registered deterministic workflows and macros.", ("workflow.execute", "macros.execute"), False),
        ("android", "Android Phone", "Delegates permitted device actions to the Android client.", ("android.intent", "android.accessibility"), False),
        ("data", "Data Analysis", "Processes datasets through a configured analysis runtime.", ("data.analyze", "data.transform"), False),
        ("documents", "Document", "Creates and validates documents through a configured document adapter.", ("documents.create", "documents.read"), False),
        ("drive", "Drive", "Uses authorized Google Drive account adapters.", ("drive.search", "drive.upload", "drive.download"), False),
        ("security", "Security", "Applies URL, command, path, secret and approval policies.", ("security.validate", "security.approval"), False),
        ("verification", "Verification", "Independently verifies operation evidence and generated artifacts.", ("verification.check",), False),
        ("delivery", "Delivery", "Packages verified outputs and reports their locations.", ("delivery.package",), False),
    )
    for agent_id, name, description, capabilities, requires_model in agents:
        registry.register(AgentDefinition(agent_id, name, description, capabilities, requires_model=requires_model))
    return registry
