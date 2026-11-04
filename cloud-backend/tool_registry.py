"""Shared cloud capability registry with explicit permissions and real executor adapters."""
from __future__ import annotations

import asyncio
import base64
import csv
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from html.parser import HTMLParser
from pathlib import Path
from statistics import mean, median
from urllib.parse import parse_qs, quote, urljoin, urlsplit

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


def _terminal_sandbox_enabled() -> bool:
    return (
        sys.platform.startswith("linux")
        and os.getenv("OPENSWARM_ENABLE_SANDBOX", "").strip().lower() in {"1", "true", "yes"}
        and shutil.which("bwrap") is not None
        and shutil.which("prlimit") is not None
    )


async def _terminal_execute(payload: dict[str, Any]) -> dict[str, Any]:
    if not _terminal_sandbox_enabled():
        raise RuntimeError(
            "Isolated terminal is unavailable. Install bubblewrap in the Colab runtime and set "
            "OPENSWARM_ENABLE_SANDBOX=1; arbitrary unsandboxed shell execution is deliberately disabled."
        )
    argv = payload.get("argv")
    if not isinstance(argv, list) or not argv or len(argv) > 32:
        raise ValueError("terminal.execute requires an argv list containing 1–32 arguments.")
    if any(not isinstance(item, str) or not item or len(item) > 4096 or "\x00" in item for item in argv):
        raise ValueError("Terminal arguments must be non-empty strings of at most 4096 characters.")
    command_path = shutil.which(argv[0])
    if not command_path:
        raise ValueError(f"Command is not installed in the Colab runtime: {argv[0]}")
    resolved_command = Path(command_path).resolve()
    if not (resolved_command.is_relative_to(Path("/usr/bin")) or resolved_command.is_relative_to(Path("/bin"))):
        raise ValueError("Terminal commands must resolve inside /usr/bin or /bin.")
    workspace = _workspace_root()
    raw_cwd = str(payload.get("cwd") or ".")
    cwd = (workspace / raw_cwd).resolve()
    if not cwd.is_relative_to(workspace) or not cwd.is_dir():
        raise ValueError("Terminal working directory must be an existing directory inside the OpenSwarm workspace.")
    timeout_seconds = max(1, min(int(payload.get("timeout_seconds", 20)), 30))
    bwrap = shutil.which("bwrap")
    sandbox_argv = [bwrap, "--die-with-parent", "--unshare-all", "--new-session"]
    for host_path in ("/usr", "/bin", "/lib", "/lib64", "/etc"):
        if Path(host_path).exists():
            sandbox_argv.extend(["--ro-bind", host_path, host_path])
    sandbox_argv.extend([
        "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp",
        "--bind", str(workspace), "/workspace",
        "--chdir", "/workspace" if cwd == workspace else "/workspace/" + cwd.relative_to(workspace).as_posix(),
        "--clearenv", "--setenv", "PATH", "/usr/bin:/bin",
        "--setenv", "HOME", "/workspace", "--setenv", "TMPDIR", "/tmp",
        "--", str(resolved_command), *argv[1:],
    ])

    prlimit = shutil.which("prlimit")
    if not prlimit:
        raise RuntimeError("The Colab runtime does not provide prlimit; isolated terminal execution is disabled.")
    limited_argv = [
        prlimit, f"--cpu={timeout_seconds + 1}", "--as=1073741824",
        "--fsize=20971520", "--nofile=64", "--nproc=32", "--", *sandbox_argv,
    ]

    def run_command() -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            limited_argv, cwd="/", env={"PATH": "/usr/bin:/bin"},
            capture_output=True, text=True, timeout=timeout_seconds + 5,
            check=False, shell=False,
        )

    started = time.monotonic()
    try:
        completed = await asyncio.to_thread(run_command)
    except subprocess.TimeoutExpired as exc:
        raise TimeoutError(f"Sandboxed command exceeded {timeout_seconds} seconds.") from exc
    except OSError as exc:
        raise RuntimeError(f"Bubblewrap could not start the isolated terminal: {exc}") from exc
    return {
        "command": argv, "cwd": cwd.relative_to(workspace).as_posix() or ".",
        "exit_code": completed.returncode, "stdout": (completed.stdout or "")[:20_000],
        "stderr": (completed.stderr or "")[:20_000],
        "duration_ms": int((time.monotonic() - started) * 1000),
        "sandbox": "bubblewrap-unshare-all-network-disabled",
        "verified": completed.returncode == 0,
    }


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


def build_default_capability_registry(memory_store: Any | None = None) -> CapabilityRegistry:
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
        candidates = []
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
            candidates.append({"title": re.sub(r"\\s+", " ", item["title"]).strip(),
                               "url": target})
            if len(candidates) >= limit:
                break

        semaphore = asyncio.Semaphore(3)

        async def retrieve_source(source: dict[str, str]) -> dict[str, Any]:
            hostname = urlsplit(source["url"]).hostname
            if not hostname:
                return {**source, "retrieved": False, "retrieval_error": "invalid_hostname"}
            async with semaphore:
                try:
                    page = await registry.execute("browser.extract", {
                        "url": source["url"], "allowed_domains": [hostname],
                    })
                    content = str(page.get("text") or "").strip()
                    if page.get("verified") is not True or not content:
                        return {**source, "retrieved": False, "retrieval_error": "empty_or_unverified_page"}
                    return {**source, "content": content[:5000], "retrieved": True}
                except Exception as exc:
                    return {**source, "retrieved": False, "retrieval_error": type(exc).__name__}

        fetched = await asyncio.gather(*(retrieve_source(source) for source in candidates[:5]))
        evidence = [source for source in fetched if source.get("retrieved") is True]
        unavailable = [{"title": source["title"], "url": source["url"],
                        "error": source.get("retrieval_error", "unavailable")}
                       for source in fetched if source.get("retrieved") is not True]
        return {
            "query": query, "sources": evidence, "unavailable_sources": unavailable,
            "retrieved_count": len(evidence), "verified": bool(evidence),
            "verification": "Source-page text was retrieved through Playwright; citations identify retrieved pages but do not independently certify factual claims.",
        }


    async def file_read(payload: dict[str, Any]) -> dict[str, Any]:
        path = _workspace_path(payload.get("path"))
        if not path.is_file():
            raise FileNotFoundError("Workspace file not found.")
        if path.stat().st_size > 1_000_000:
            raise ValueError("File exceeds the 1 MB text-read limit.")
        content = path.read_text(encoding="utf-8")
        return {"path": str(path.relative_to(_workspace_root())), "content": content,
                "size_bytes": path.stat().st_size, "verified": True}

    async def file_write(payload: dict[str, Any]) -> dict[str, Any]:
        path = _workspace_path(payload.get("path"))
        content = payload.get("content")
        if not isinstance(content, str):
            raise ValueError("files.write requires UTF-8 text content.")
        encoded = content.encode("utf-8")
        if len(encoded) > 1_000_000:
            raise ValueError("File exceeds the 1 MB write limit.")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(encoded)
        return {"path": str(path.relative_to(_workspace_root())), "size_bytes": len(encoded),
                "sha256": hashlib.sha256(encoded).hexdigest(), "verified": path.read_bytes() == encoded}

    async def file_list(payload: dict[str, Any]) -> dict[str, Any]:
        raw = payload.get("path", ".")
        base = _workspace_root() if raw == "." else _workspace_path(raw)
        if not base.is_dir():
            raise NotADirectoryError("Workspace directory not found.")
        items = [{"name": item.name, "type": "directory" if item.is_dir() else "file",
                  "size_bytes": item.stat().st_size if item.is_file() else None}
                 for item in sorted(base.iterdir(), key=lambda item: item.name.lower())[:200]]
        return {"path": "." if base == _workspace_root() else str(base.relative_to(_workspace_root())),
                "items": items, "verified": True}

    async def data_analyze(payload: dict[str, Any]) -> dict[str, Any]:
        path = _workspace_path(payload.get("path"))
        if not path.is_file() or path.stat().st_size > 2_000_000:
            raise ValueError("data.analyze requires an existing workspace CSV or JSON file up to 2 MB.")
        if path.suffix.lower() == ".csv":
            with path.open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle))
            numeric = {}
            for key in (rows[0].keys() if rows else []):
                values = []
                for row in rows:
                    try:
                        values.append(float(row.get(key, "")))
                    except (TypeError, ValueError):
                        continue
                if values:
                    numeric[key] = {"count": len(values), "mean": mean(values), "median": median(values),
                                    "min": min(values), "max": max(values)}
            return {"path": str(path.relative_to(_workspace_root())), "rows": len(rows),
                    "columns": list(rows[0].keys()) if rows else [], "numeric_summary": numeric, "verified": True}
        if path.suffix.lower() == ".json":
            data = json.loads(path.read_text(encoding="utf-8"))
            return {"path": str(path.relative_to(_workspace_root())),
                    "top_level_type": type(data).__name__,
                    "top_level_keys": list(data.keys())[:200] if isinstance(data, dict) else None,
                    "item_count": len(data) if isinstance(data, (list, dict)) else None, "verified": True}
        raise ValueError("data.analyze supports .csv and .json files only.")

    async def document_create(payload: dict[str, Any]) -> dict[str, Any]:
        path = _workspace_path(payload.get("path"))
        content = payload.get("markdown")
        if not isinstance(content, str) or not content.strip():
            raise ValueError("documents.create requires non-empty Markdown content.")
        return await file_write({"path": str(path.relative_to(_workspace_root())), "content": content})



    async def memory_read(payload: dict[str, Any]) -> dict[str, Any]:
        if memory_store is None:
            raise RuntimeError("Persistent agent memory store is not configured.")
        item = memory_store.memory_get(payload.get("key", ""))
        return {"item": item, "verified": item is not None}

    async def memory_write(payload: dict[str, Any]) -> dict[str, Any]:
        if memory_store is None:
            raise RuntimeError("Persistent agent memory store is not configured.")
        if "value" not in payload:
            raise ValueError("memory.write requires a value.")
        item = memory_store.memory_set(payload.get("key", ""), payload["value"])
        return {"item": item, "verified": True}

    async def memory_search(payload: dict[str, Any]) -> dict[str, Any]:
        if memory_store is None:
            raise RuntimeError("Persistent agent memory store is not configured.")
        items = memory_store.memory_search(str(payload.get("query") or ""), payload.get("limit", 10))
        return {"items": items, "verified": True}

    def github_headers() -> dict[str, str]:
        token = os.getenv("GITHUB_TOKEN", "").strip()
        if not token:
            raise RuntimeError("GitHub adapter is not configured. Add GITHUB_TOKEN to Colab Secrets and restart the backend.")
        return {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "USMAN-AND-UMAR-OpenSwarm"}

    def github_repo_path(payload: dict[str, Any]) -> tuple[str, str]:
        repo = str(payload.get("repo") or "").strip()
        path = str(payload.get("path") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError("GitHub repo must be owner/repository.")
        if not path or path.startswith("/") or any(part in {"", ".", ".."} for part in path.split("/")):
            raise ValueError("GitHub path must be a safe repository-relative file path.")
        return repo, path

    async def github_read(payload: dict[str, Any]) -> dict[str, Any]:
        repo, path = github_repo_path(payload)
        ref = str(payload.get("ref") or "").strip()
        url = f"https://api.github.com/repos/{repo}/contents/{quote(path, safe='/')}"
        async with httpx.AsyncClient(timeout=25, headers=github_headers()) as client:
            response = await client.get(url, params={"ref": ref} if ref else None)
            response.raise_for_status()
        item = response.json()
        if item.get("type") != "file":
            raise ValueError("github.read only reads a single file, not a directory.")
        if int(item.get("size") or 0) > 1_000_000:
            raise ValueError("GitHub file exceeds the 1 MB text-read limit.")
        encoded = str(item.get("content") or "").replace("\n", "")
        content = base64.b64decode(encoded).decode("utf-8")
        if len(content.encode("utf-8")) > 1_000_000:
            raise ValueError("GitHub file exceeds the 1 MB text-read limit.")
        return {"repo": repo, "path": path, "ref": ref or "default branch", "sha": item.get("sha"),
                "content": content, "verified": True}

    async def github_write(payload: dict[str, Any]) -> dict[str, Any]:
        repo, path = github_repo_path(payload)
        content = payload.get("content")
        message = str(payload.get("message") or "").strip()
        if not isinstance(content, str) or not message:
            raise ValueError("github.write requires text content and a commit message.")
        encoded = content.encode("utf-8")
        if len(encoded) > 1_000_000:
            raise ValueError("GitHub file exceeds the 1 MB write limit.")
        branch = str(payload.get("branch") or "main").strip()
        if (not re.fullmatch(r"[A-Za-z0-9._/-]+", branch) or ".." in branch or branch.startswith("/")
                or any(part in {"", ".", ".."} for part in branch.split("/"))):
            raise ValueError("Invalid GitHub branch name.")
        headers = github_headers()
        base_url = f"https://api.github.com/repos/{repo}"
        async with httpx.AsyncClient(timeout=30, headers=headers) as client:
            if payload.get("create_branch") is True:
                metadata = await client.get(base_url)
                metadata.raise_for_status()
                default_branch = metadata.json()["default_branch"]
                source_ref = await client.get(f"{base_url}/git/ref/heads/{quote(default_branch, safe='/')}")
                source_ref.raise_for_status()
                source_sha = source_ref.json()["object"]["sha"]
                created = await client.post(f"{base_url}/git/refs", json={
                    "ref": f"refs/heads/{branch}", "sha": source_sha,
                })
                created.raise_for_status()
            file_url = f"{base_url}/contents/{quote(path, safe='/')}"
            current = await client.get(file_url, params={"ref": branch})
            sha = None
            if current.status_code == 200:
                sha = current.json().get("sha")
            elif current.status_code != 404:
                current.raise_for_status()
            request_body = {"message": message, "content": base64.b64encode(encoded).decode("ascii"),
                            "branch": branch}
            if sha:
                request_body["sha"] = sha
            updated = await client.put(file_url, json=request_body)
            updated.raise_for_status()
            commit = updated.json().get("commit") or {}
            file_info = updated.json().get("content") or {}
            check = await client.get(file_url, params={"ref": branch})
            check.raise_for_status()
            observed = base64.b64decode(str(check.json().get("content") or "").replace("\n", ""))
        return {"repo": repo, "path": path, "branch": branch, "commit_sha": commit.get("sha"),
                "file_sha": file_info.get("sha"), "url": file_info.get("html_url"),
                "verified": observed == encoded}

    async def github_pull_request(payload: dict[str, Any]) -> dict[str, Any]:
        repo = str(payload.get("repo") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError("GitHub repo must be owner/repository.")
        head, base = str(payload.get("head") or "").strip(), str(payload.get("base") or "").strip()
        title, body = str(payload.get("title") or "").strip(), str(payload.get("body") or "")
        if not head or not base or not title:
            raise ValueError("github.pull_request requires head, base and title.")
        async with httpx.AsyncClient(timeout=25, headers=github_headers()) as client:
            response = await client.post(f"https://api.github.com/repos/{repo}/pulls",
                                         json={"head": head, "base": base, "title": title, "body": body})
            response.raise_for_status()
        result = response.json()
        return {"repo": repo, "number": result.get("number"), "url": result.get("html_url"),
                "state": result.get("state"), "verified": bool(result.get("html_url"))}

    entries = (
        Capability("browser.extract", "Navigate to an approved public URL and extract page text.", "cloud", ("network",), "low"),
        Capability("research.search", "Search public web results and return source titles and URLs.", "cloud", ("network",), "low"),
        Capability("code.generate", "Generate or review source code using the configured reasoning provider.", "cloud", ("model",), "low"),
        Capability("terminal.execute", "Run an approved command inside a Bubblewrap sandbox with no network and a workspace-only writable mount.", "cloud", ("terminal",), "critical"),
        Capability("github.read", "Read a file from an authorized GitHub repository.", "cloud", ("github.read",), "low"),
        Capability("github.write", "Create or update a repository file and optionally create a branch.", "cloud", ("github.write",), "high"),
        Capability("github.pull_request", "Open a pull request between authorized repository branches.", "cloud", ("github.write",), "high"),
        Capability("files.read", "Read UTF-8 text files inside the configured workspace.", "cloud", ("files",), "low"),
        Capability("files.write", "Create or replace UTF-8 text files inside the configured workspace.", "cloud", ("files",), "high"),
        Capability("files.list", "List entries inside the configured workspace.", "cloud", ("files",), "low"),
        Capability("automation.execute", "Execute an approved registered workflow.", "cloud", ("automation",), "high"),
        Capability("android.intent", "Request an approved Android intent through the connected app.", "android", ("android",), "high"),
        Capability("android.accessibility", "Execute an explicitly approved Accessibility workflow.", "android", ("accessibility",), "critical"),
        Capability("data.analyze", "Summarize CSV and JSON files inside the configured workspace.", "cloud", ("files",), "low"),
        Capability("documents.create", "Create a Markdown document inside the configured workspace.", "cloud", ("files",), "high"),
        Capability("drive.search", "Search files in an authorized Google Drive account.", "cloud", ("drive.read",), "low"),
        Capability("drive.upload", "Upload a file to an authorized Google Drive account.", "cloud", ("drive.write",), "high"),
        Capability("verification.check", "Verify an operation using independent execution evidence.", "cloud", (), "low"),
        Capability("delivery.package", "Package and report verified task artifacts.", "cloud", ("files",), "low"),
        Capability("memory.read", "Read a named persistent agent memory entry.", "cloud", ("memory.read",), "low"),
        Capability("memory.search", "Search persistent agent memory entries.", "cloud", ("memory.read",), "low"),
        Capability("memory.write", "Create or update a persistent agent memory entry.", "cloud", ("memory.write",), "high"),
    )
    github_configured = bool(os.getenv("GITHUB_TOKEN", "").strip())
    for item in entries:
        executors = {
            "browser.extract": browser_extract, "research.search": research_search,
            "files.read": file_read, "files.write": file_write, "files.list": file_list,
            "data.analyze": data_analyze, "documents.create": document_create,
            "github.read": github_read, "github.write": github_write,
            "github.pull_request": github_pull_request,
            "terminal.execute": _terminal_execute if _terminal_sandbox_enabled() else None,
            "memory.read": memory_read if memory_store is not None else None,
            "memory.search": memory_search if memory_store is not None else None,
            "memory.write": memory_write if memory_store is not None else None,
        }
        executor = executors.get(item.id)
        if item.id.startswith("github.") and not github_configured:
            executor = None
        registry.register(item, executor)
    return registry
