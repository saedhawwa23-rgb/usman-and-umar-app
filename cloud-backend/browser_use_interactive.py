"""Direct Playwright browser automation for the USMAN AND UMAR Colab backend."""
from __future__ import annotations
import os
import re
from typing import Iterable
from urllib.parse import urljoin, urlparse

NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"
NVIDIA_DEFAULT_MODEL = "nvidia/nemotron-3.5-bolt-e4b"
OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
OPENROUTER_FREE_MODEL = "openrouter/free"
FMHY_URL = "https://fmhy.net"
URL_RE = re.compile(r"""https?://[^\\s<>\\"']+""", re.IGNORECASE)
STOPWORDS = {"the", "and", "for", "with", "from", "please", "open", "click", "find", "use", "tool", "tell", "me", "about"}

def _select_provider(requested_model: str | None, nvidia_key: str | None, openrouter_key: str | None):
    """Provider selection retained for the separate OpenSwarm reasoning workflow."""
    override = requested_model.strip() if requested_model and requested_model.strip() else ""
    if nvidia_key:
        return "NVIDIA", nvidia_key, NVIDIA_DEFAULT_MODEL, NVIDIA_BASE_URL
    if openrouter_key:
        return "OpenRouter", openrouter_key, override or os.environ.get("OPENROUTER_MODEL", "").strip() or OPENROUTER_FREE_MODEL, OPENROUTER_BASE_URL
    return "Ollama", None, override or "llama3.1:8b", None

def _host(url: str) -> str:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower().rstrip(".")
    if parsed.scheme not in {"http", "https"} or not host or parsed.username or parsed.password:
        raise ValueError("Only public http(s) URLs without embedded credentials are allowed.")
    if host in {"localhost", "metadata", "metadata.google.internal"} or host.endswith((".localhost", ".local", ".internal", ".test", ".invalid")) or "." not in host:
        raise ValueError("Private, local, and metadata hosts are blocked.")
    try:
        import ipaddress
        ipaddress.ip_address(host)
    except ValueError:
        return host
    raise ValueError("IP-address URLs are not allowed.")

def _validate_domains(domains: Iterable[str]) -> list[str]:
    hosts = []
    for item in domains:
        host = str(item).strip().lower().rstrip(".")
        if not host or "/" in host or ":" in host or "@" in host:
            raise ValueError("allowed_domains must contain hostnames only.")
        if host not in hosts:
            hosts.append(host)
    if not hosts:
        raise ValueError("An explicit allowed_domains list is required.")
    return hosts

def _link_label(task: str) -> str | None:
    patterns = [
        r"""(?:click|open|follow)\\s+(?:the\\s+)?(?:link|button)?\\s*["']([^"']+)["']""",
        r"(?:click|open|follow)\\s+(?:the\\s+)?(?:link|button)?\\s*(?:named|called)\\s+(.+?)(?:[.!?]|$)",
    ]
    for pattern in patterns:
        match = re.search(pattern, task, re.IGNORECASE)
        if match:
            return match.group(1).strip()
    return None

async def _fmhy_target(page, task: str) -> str:
    await page.goto(FMHY_URL, wait_until="domcontentloaded", timeout=30000)
    links = await page.locator("a[href]").evaluate_all("els => els.map(a => ({text:(a.innerText||a.textContent||'').trim(),href:a.href}))")
    words = [w for w in re.findall(r"[a-z0-9]{3,}", task.lower()) if w not in STOPWORDS]
    ranked = []
    for item in links:
        href = str(item.get("href", ""))
        try:
            host = _host(href)
        except ValueError:
            continue
        if host == "fmhy.net" or host.endswith(".fmhy.net"):
            continue
        label = str(item.get("text", ""))
        score = sum(3 if w in label.lower() else 1 for w in words if w in (label + " " + href).lower())
        if score:
            ranked.append((score, href))
    ranked.sort(reverse=True)
    if not ranked:
        raise RuntimeError("No matching external FMHY link was found.")
    if len(ranked) > 1 and ranked[0][0] == ranked[1][0]:
        raise RuntimeError("The FMHY request matches multiple links; specify the exact tool name.")
    return ranked[0][1]

async def run_browser_task(task: str, *, allowed_domains: Iterable[str], model: str | None = None, max_steps: int = 15, allow_fmhy_external: bool = False) -> str:
    """Navigate to a public page, optionally click one named link, and return body text."""
    if not isinstance(task, str) or not task.strip():
        raise ValueError("Provide a non-empty browser task.")
    if not 1 <= int(max_steps) <= 30:
        raise ValueError("max_steps must be between 1 and 30.")
    domains = _validate_domains(allowed_domains)
    urls = URL_RE.findall(task)
    if not urls and not allow_fmhy_external:
        raise ValueError("Include a public http(s) URL in the prompt.")
    try:
        from playwright.async_api import async_playwright
    except ImportError as exc:
        raise RuntimeError("Playwright is not installed. Run the notebook Playwright setup cell.") from exc
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        try:
            page = await browser.new_page()
            if allow_fmhy_external:
                if "fmhy.net" not in domains:
                    domains.append("fmhy.net")
                target = await _fmhy_target(page, task)
                domains.append(_host(target))
                await page.goto(target, wait_until="domcontentloaded", timeout=30000)
            else:
                target = urls[0].rstrip(".,;:!?)]}")
                if _host(target) not in domains:
                    raise ValueError("The URL hostname is not in the approved allowlist.")
                await page.goto(target, wait_until="domcontentloaded", timeout=30000)
            if _host(page.url) not in domains:
                raise RuntimeError("Navigation redirected outside the approved hostname allowlist.")
            label = _link_label(task)
            if label:
                link = page.get_by_role("link", name=re.compile(re.escape(label), re.IGNORECASE))
                count = await link.count()
                if count != 1:
                    raise RuntimeError(f"Expected one link matching {label!r}; found {count}.")
                href = await link.first.get_attribute("href")
                if not href:
                    raise RuntimeError("The selected link has no destination.")
                destination = urljoin(page.url, href)
                if _host(destination) not in domains:
                    raise RuntimeError("The selected link points outside the approved hostname allowlist.")
                await link.first.click(timeout=15000)
                try:
                    await page.wait_for_load_state("domcontentloaded", timeout=10000)
                except Exception:
                    pass
                if _host(page.url) not in domains:
                    raise RuntimeError("The clicked link navigated outside the approved hostname allowlist.")
            title = (await page.title()).strip()
            body = (await page.locator("body").inner_text(timeout=15000)).strip()
            return f"Title: {title or '(no title)'}\\nURL: {page.url}\\n\\n{body[:60000]}"
        finally:
            await browser.close()

async def interactive_prompt(*, allowed_domains: Iterable[str], model: str | None = None, max_steps: int = 15, allow_fmhy_external: bool = False) -> str:
    task = input("Public browser task: ").strip()
    if not task:
        raise ValueError("No task entered.")
    return await run_browser_task(task, allowed_domains=allowed_domains, model=model, max_steps=max_steps, allow_fmhy_external=allow_fmhy_external)
