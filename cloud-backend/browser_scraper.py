"""Small raw-Playwright helper for bounded public-page text extraction."""
from __future__ import annotations

import ipaddress
import re
from urllib.parse import urljoin, urlsplit

from playwright.async_api import async_playwright

_BLOCKED_HOSTS = {"localhost", "metadata", "metadata.google.internal"}
_BLOCKED_SUFFIXES = (".localhost", ".local", ".internal", ".test", ".invalid")


def _public_http_url(url: str) -> tuple[str, str]:
    try:
        parsed = urlsplit(url)
        host = (parsed.hostname or "").rstrip(".").lower()
        port = parsed.port
    except ValueError as exc:
        raise ValueError("A valid public http(s) URL is required.") from exc
    if parsed.scheme.lower() not in {"http", "https"} or not host or parsed.username or parsed.password:
        raise ValueError("A valid public http(s) URL without embedded credentials is required.")
    if host in _BLOCKED_HOSTS or host.endswith(_BLOCKED_SUFFIXES) or "." not in host:
        raise ValueError("Only public internet hostnames are allowed.")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        try:
            host.encode("idna")
        except UnicodeError as exc:
            raise ValueError("The URL contains an invalid hostname.") from exc
    else:
        if not address.is_global:
            raise ValueError("Only public internet hostnames are allowed.")
    return host, f"{parsed.scheme.lower()}://{host}{':' + str(port) if port else ''}"


def _host_allowed(host: str, allowed_domains: list[str]) -> bool:
    return any(host == domain or host.endswith("." + domain) for domain in allowed_domains)


async def run_playwright_task(
    url: str,
    *,
    action: str = "scrape",
    link_text: str | None = None,
    allowed_domains: list[str] | None = None,
) -> str:
    """Scrape a URL or click a named link, then return the destination page text."""
    initial_host, _ = _public_http_url(url)
    action = action.strip().lower()
    if action not in {"scrape", "click"}:
        raise ValueError("Action must be 'scrape' or 'click'.")
    if action == "click" and not (link_text and link_text.strip()):
        raise ValueError("A link_text is required for the click action.")
    permitted = [domain.strip().lower() for domain in (allowed_domains or [initial_host]) if domain.strip()]
    if not _host_allowed(initial_host, permitted):
        raise ValueError("The requested URL is outside the allowed browser domains.")

    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(
            headless=True,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        try:
            page = await browser.new_page()
            await page.goto(url, wait_until="domcontentloaded", timeout=30_000)
            if action == "click":
                locator = page.get_by_role(
                    "link", name=re.compile(re.escape(link_text.strip()), re.IGNORECASE)
                ).first
                if await locator.count() == 0:
                    raise RuntimeError(f"No link matching {link_text!r} was found on the page.")
                href = await locator.get_attribute("href")
                if not href:
                    raise RuntimeError("The matching link does not have a destination URL.")
                target_url = urljoin(page.url, href)
                target_host, _ = _public_http_url(target_url)
                target = await locator.get_attribute("target")
                if target == "_blank":
                    async with page.expect_popup(timeout=15_000) as popup_info:
                        await locator.click(timeout=10_000)
                    page = await popup_info.value
                    await page.wait_for_load_state("domcontentloaded", timeout=15_000)
                else:
                    async with page.expect_navigation(wait_until="domcontentloaded", timeout=15_000):
                        await locator.click(timeout=10_000)
                final_host, _ = _public_http_url(page.url)
                if final_host != target_host and not final_host.endswith("." + target_host):
                    raise RuntimeError("The clicked link redirected to an unexpected hostname.")
            return await page.inner_text("body", timeout=15_000)
        finally:
            await browser.close()
