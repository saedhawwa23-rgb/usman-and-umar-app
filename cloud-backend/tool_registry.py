"""Shared cloud capability registry with explicit permissions and real executor adapters."""
from __future__ import annotations

import asyncio
from dataclasses import asdict, dataclass
from typing import Any, Awaitable, Callable


@dataclass(frozen=True)
class Capability:
    id: str
    description: str
    execution_location: str
    permissions: tuple[str, ...] = ()
    risk: str = "low"
    configured: bool = False


Executor = Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]


class CapabilityRegistry:
    def __init__(self) -> None:
        self._capabilities: dict[str, Capability] = {}
        self._executors: dict[str, Executor] = {}

    def register(self, capability: Capability, executor: Executor | None = None) -> None:
        if capability.id in self._capabilities:
            raise ValueError(f"Capability already registered: {capability.id}")
        self._capabilities[capability.id] = capability
        if executor is not None:
            self._executors[capability.id] = executor

    def replace(self, capability: Capability, executor: Executor | None = None) -> None:
        self._capabilities[capability.id] = capability
        if executor is None:
            self._executors.pop(capability.id, None)
        else:
            self._executors[capability.id] = executor

    def describe(self) -> list[dict[str, Any]]:
        return [{**asdict(item), "available": item.id in self._executors} for item in self._capabilities.values()]

    async def execute(self, capability_id: str, payload: dict[str, Any], approved: bool = False) -> dict[str, Any]:
        capability = self._capabilities.get(capability_id)
        if capability is None:
            raise KeyError(f"Unknown capability: {capability_id}")
        if capability.risk in {"high", "critical"} and not approved:
            raise PermissionError(f"Capability {capability_id} requires explicit user approval")
        executor = self._executors.get(capability_id)
        if executor is None:
            raise RuntimeError(f"Capability {capability_id} is registered but has no configured execution adapter")
        return await asyncio.wait_for(executor(payload), timeout=60)
