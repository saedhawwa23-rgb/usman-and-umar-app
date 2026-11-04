"""Shared cloud capability registry with explicit permissions and real executor adapters."""
from __future__ import annotations

import asyncio
import csv
import hashlib
import json
import os
import re
from html.parser import HTMLParser
from pathlib import Path
from statistics import mean, median
from urllib.parse import parse_qs, urljoin, urlsplit

import httpx
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

def _workspace_root() -> Path:
    root = Path(os.getenv("OPENSWARM_WORKSPACE", "/content/usman-workspace")).expanduser().resolve()
    root.mkdir(parents=True, exist_ok=True)
    return root

def _workspace_path(raw_path: Any) -> Path:
    if not isinstance(raw_path, str) or not raw_path.strip():
        raise ValueError("A relative workspace path is required.")
    root = _workspace_root()
    candidate = (root / raw_path).resolve()
    if candidate == root or root not in candidate.parents:
        raise ValueError("Workspace path escapes the configured workspace root.")
    return candidate



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

    def get(self, capability_id: str) -> Capability | None:
        return self._capabilities.get(capability_id)

    def describe(self) -> list[dict[str, Any]]:
        return [{**asdict(item), "available": item.id in self._executors,
                 "adapter_configured": item.id in self._executors}
                for item in self._capabilities.values()]

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



class _DuckDuckGoResultParser(HTMLParser):
    """Extract result title links from DuckDuckGo's lightweight HTML endpoint."""
    def __init__(self) -> None:
        super().__init__()
        self.results: list[dict[str, str]] = []
        self._capture_title = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        classes = set((values.get("class") or "").split())
        if tag == "a" and "result__a" in classes:
            self.results.append({"href": values.get("href") or "", "title": ""})
            self._capture_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._capture_title:
            self._capture_title = False

    def handle_data(self, data: str) -> None:
        if self._capture_title and self.results:
            self.results[-1]["title"] += data


def build_default_capability_registry() -> CapabilityRegistry:
    registry = CapabilityRegistry()

    async def browser_extract(payload: dict[str, Any]) -> dict[str, Any]:
        from browser_scraper import run_playwright_task
        url = str(payload.get("url") or "").strip()
        if not url:
            raise ValueError("browser.extract requires a URL.")
        allowed = payload.get("allowed_domains") or []
        if not isinstance(allowed, list) or any(not isinstance(item, str) for item in allowed):
            raise ValueError("allowed_domains must be a list of hostnames.")
        text = await run_playwright_task(url, action="scrape", allowed_domains=allowed or None)
        return {"url": url, "text": text, "verified": bool(text.strip())}

    async def research_search(payload: dict[str, Any]) -> dict[str, Any]:
        query = str(payload.get("query") or "").strip()
        if not query:
            raise ValueError("research.search requires a non-empty query.")
        limit = max(1, min(int(payload.get("limit", 5)), 10))
        async with httpx.AsyncClient(timeout=20, follow_redirects=False, headers={
            "User-Agent": "USMAN-AND-UMAR-OpenSwarm/1.0 (public web research)"
        }) as client:
            response = await client.get("https://html.duckduckgo.com/html/", params={"q": query})
            response.raise_for_status()
        parser = _DuckDuckGoResultParser()
        parser.feed(response.text)
        sources = []
        for item in parser.results:
            raw_href = item["href"].strip()
            if not raw_href:
                continue
            candidate = urljoin("https://html.duckduckgo.com", raw_href)
            redirect_query = parse_qs(urlsplit(candidate).query)
            target = (redirect_query.get("uddg") or [candidate])[0]
            try:
                from browser_scraper import _public_http_url
                _public_http_url(target)
            except (ValueError, ImportError):
                continue
            sources.append({"title": re.sub(r"\\s+", " ", item["title"]).strip(),
                            "url": target})
            if len(sources) >= limit:
                break
        return {"query": query, "sources": sources, "verified": bool(sources)}

    entries = (
        Capability("browser.extract", "Navigate to an approved public URL and extract page text.", "cloud", ("network",), "low"),
        Capability("research.search", "Search public web results and return source titles and URLs.", "cloud", ("network",), "low"),
        Capability("code.generate", "Generate or review source code using the configured reasoning provider.", "cloud", ("model",), "low"),
        Capability("terminal.execute", "Run a command in an isolated, policy-controlled execution environment.", "cloud", ("terminal",), "critical"),
        Capability("github.read", "Read authorized GitHub repository resources.", "cloud", ("github.read",), "low"),
        Capability("github.write", "Create branches, commits, pushes and pull requests.", "cloud", ("github.write",), "high"),
        Capability("files.read", "Read files from an authorized workspace.", "cloud", ("files",), "low"),
        Capability("files.write", "Create or modify files in an authorized workspace.", "cloud", ("files",), "high"),
        Capability("automation.execute", "Execute an approved registered workflow.", "cloud", ("automation",), "high"),
        Capability("android.intent", "Request an approved Android intent through the connected app.", "android", ("android",), "high"),
        Capability("android.accessibility", "Execute an explicitly approved Accessibility workflow.", "android", ("accessibility",), "critical"),
        Capability("data.analyze", "Analyze structured data in a configured runtime.", "cloud", ("files",), "low"),
        Capability("documents.create", "Generate a document using a configured document adapter.", "cloud", ("files",), "low"),
        Capability("drive.search", "Search files in an authorized Google Drive account.", "cloud", ("drive.read",), "low"),
        Capability("drive.upload", "Upload a file to an authorized Google Drive account.", "cloud", ("drive.write",), "high"),
        Capability("verification.check", "Verify an operation using independent execution evidence.", "cloud", (), "low"),
        Capability("delivery.package", "Package and report verified task artifacts.", "cloud", ("files",), "low"),
    )
    for item in entries:
        executor = browser_extract if item.id == "browser.extract" else research_search if item.id == "research.search" else None
        registry.register(item, executor)
    return registry
