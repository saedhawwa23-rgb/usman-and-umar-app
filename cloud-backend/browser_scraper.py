"""Small raw-Playwright helper for bounded public-page text extraction."""
from __future__ import annotations

import asyncio
import ipaddress
import re
import socket
import time
from typing import Any
from urllib.parse import urljoin, urlsplit

from playwright.async_api import async_playwright

_BLOCKED_HOSTS = {"localhost", "metadata", "metadata.google.internal"}
_BLOCKED_SUFFIXES = (".localhost", ".local", ".internal", ".test", ".invalid")
_DNS_CACHE: dict[str, tuple[float, bool]] = {}


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
    canonical_host = host.rstrip(".").lower()
    return canonical_host in {domain.rstrip(".").lower() for domain in allowed_domains}


async def _assert_public_dns(url: str) -> None:
    host, _ = _public_http_url(url)
    try:
        ipaddress.ip_address(host)
        return  # Literal addresses were checked by _public_http_url.
    except ValueError:
        pass
    cached = _DNS_CACHE.get(host)
    now = time.monotonic()
    if cached and cached[0] > now:
        if cached[1]:
            return
        raise ValueError("The requested hostname does not resolve exclusively to public IP addresses.")
    try:
        records = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(host, None, type=socket.SOCK_STREAM),
            timeout=5,
        )
        addresses = {ipaddress.ip_address(record[4][0].split("%", 1)[0]) for record in records}
    except Exception as exc:
        raise ValueError("The requested public hostname could not be resolved safely.") from exc
    public_only = bool(addresses) and all(address.is_global for address in addresses)
    _DNS_CACHE[host] = (now + 60, public_only)
    if not public_only:
        raise ValueError("The requested hostname does not resolve exclusively to public IP addresses.")


async def _guard_public_requests(page: Any, allowed_domains: list[str]) -> None:
    if not hasattr(page, "route"):
        return  # Lightweight test doubles do not implement Playwright routing.

    async def validate_request(route: Any) -> None:
        scheme = urlsplit(route.request.url).scheme.lower()
        if scheme in {"data", "blob", "about"}:
            await route.continue_()
            return
        try:
            if scheme not in {"http", "https"}:
                raise ValueError("Only public HTTP(S) browser requests are allowed.")
            request_host, _ = _public_http_url(route.request.url)
            if not _host_allowed(request_host, allowed_domains):
                raise ValueError("The browser request is outside the exact approved host allowlist.")
            await _assert_public_dns(route.request.url)
        except (ValueError, OSError, asyncio.TimeoutError):
            await route.abort()
            return
        await route.continue_()

    await page.route("**/*", validate_request)


async def run_playwright_task(
    url: str,
    *,
    action: str = "scrape",
    link_text: str | None = None,
    allowed_domains: list[str] | None = None,
    include_title: bool = False,
    include_links: bool = False,
) -> str:
    """Scrape a URL or click a named link, then return destination page text.

    ``include_title`` adds the browser-observed document title to the returned
    text without changing the default string contract used by direct callers.
    """
    initial_host, _ = _public_http_url(url)
    action = action.strip().lower()
    if action not in {"scrape", "click"}:
        raise ValueError("Action must be 'scrape' or 'click'.")
    if action == "click" and not (link_text and link_text.strip()):
        raise ValueError("A link_text is required for the click action.")
    permitted = [domain.strip().rstrip(".").lower() for domain in (allowed_domains or [initial_host]) if domain.strip()]
    if not _host_allowed(initial_host, permitted):
        raise ValueError("The requested URL is outside the allowed browser domains.")

    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(
            headless=True,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        try:
            page = await browser.new_page()
            await _guard_public_requests(page, permitted)
            await page.goto(url, wait_until="domcontentloaded", timeout=30_000)
            landed_host, _ = _public_http_url(page.url)
            if landed_host not in permitted:
                raise ValueError("The page redirected outside the exact approved host allowlist.")
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
                if target_host not in permitted:
                    raise ValueError("The discovered link is outside the exact approved host allowlist.")
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
                if final_host != target_host:
                    raise RuntimeError("The clicked link redirected to an unexpected hostname.")
            body_text = await page.inner_text("body", timeout=15_000)
            if include_links:
                links = await page.eval_on_selector_all(
                    "a[href]",
                    "els => els.map(a => ({text: (a.innerText || a.getAttribute('aria-label') || '').trim(), href: a.href})).slice(0, 200)",
                )
                rendered_links = []
                seen_links: set[str] = set()
                for item in links:
                    if not isinstance(item, dict):
                        continue
                    candidate = str(item.get("href") or "").strip()
                    try:
                        _public_http_url(candidate)
                    except ValueError:
                        continue
                    if candidate in seen_links:
                        continue
                    seen_links.add(candidate)
                    label = re.sub(r"\s+", " ", str(item.get("text") or "")).strip()[:240]
                    rendered_links.append(f"{label}: {candidate}")
                if rendered_links:
                    body_text += "\n\nDiscovered public links (untrusted data):\n" + "\n".join(rendered_links)
            if include_title:
                title = (await page.title()).strip()
                return f"Page title: {title}\n\n{body_text}" if title else body_text
            return body_text
        finally:
            await browser.close()


async def run_playwright_interaction(
    url: str,
    *,
    field_label: str,
    text: str,
    submit_button: str,
) -> dict[str, str]:
    """Fill one non-sensitive public text field and click one named button.

    The caller must obtain explicit, one-call user approval before invoking this
    operation. Browser sessions are fresh and do not carry user cookies.
    """
    initial_host, _ = _public_http_url(url)
    field_label = field_label.strip()
    submit_button = submit_button.strip()
    if not field_label or len(field_label) > 160:
        raise ValueError("A text-field label of 1–160 characters is required.")
    if not submit_button or len(submit_button) > 160:
        raise ValueError("A button label of 1–160 characters is required.")
    if not text or len(text) > 4_000:
        raise ValueError("Interaction text must contain 1–4000 characters.")
    if re.search(r"(?i)\b(pay|purchase|buy|checkout|log\s*in|sign\s*in|delete|remove|password|captcha|two[- ]factor)\b", submit_button):
        raise ValueError("This browser action is not available for payments, account access, deletion, or verification flows.")

    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(
            headless=True,
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        try:
            page = await browser.new_page()
            await _guard_public_requests(page, [initial_host])
            await page.goto(url, wait_until="domcontentloaded", timeout=30_000)
            landed_host, _ = _public_http_url(page.url)
            if landed_host != initial_host:
                raise ValueError("The interaction page redirected to an unexpected host.")

            fields = await page.locator("input, textarea").evaluate_all(
                "els => els.map(el => ({tag: el.tagName.toLowerCase(), type: (el.getAttribute('type') || 'text').toLowerCase(), name: el.getAttribute('name') || '', id: el.id || '', label: (el.labels && Array.from(el.labels).map(x => x.innerText).join(' ')) || '', disabled: !!el.disabled, readOnly: !!el.readOnly}))"
            )
            sensitive = re.compile(r"(?i)(password|secret|token|credential|api.?key|credit.?card|payment|ssn|tax.?id|one.?time.?code|verification)")
            for item in fields:
                if not isinstance(item, dict):
                    continue
                metadata = " ".join(str(item.get(key) or "") for key in ("name", "id", "label", "type"))
                if item.get("type") in {"password", "file"} or sensitive.search(metadata):
                    raise ValueError("Interaction is blocked because the page exposes a credential, secret, payment, verification, or file-upload field.")

            matching_fields = page.get_by_label(re.compile(re.escape(field_label), re.IGNORECASE))
            if await matching_fields.count() != 1:
                raise ValueError("The requested text field was not found unambiguously by its visible label.")
            field = matching_fields.first
            metadata = await field.evaluate(
                "el => ({tag: el.tagName.toLowerCase(), type: (el.getAttribute('type') || 'text').toLowerCase(), disabled: !!el.disabled, readOnly: !!el.readOnly})"
            )
            if metadata.get("tag") not in {"input", "textarea"} or metadata.get("type") not in {"text", "search", "url"}:
                raise ValueError("Only ordinary text, search, or URL fields may be filled.")
            if metadata.get("disabled") or metadata.get("readOnly"):
                raise ValueError("The requested text field is not editable.")
            matching_buttons = page.get_by_role("button", name=re.compile(re.escape(submit_button), re.IGNORECASE))
            if await matching_buttons.count() != 1:
                raise ValueError("The requested button was not found unambiguously by its accessible label.")
            button = matching_buttons.first
            submission_url = str(await button.evaluate(
                "el => el.formAction || (el.form && el.form.action) || location.href"
            ))
            page_host, page_origin = _public_http_url(page.url)
            submission_host, submission_origin = _public_http_url(submission_url)
            if submission_host != page_host or submission_origin != page_origin:
                raise ValueError("Interaction is blocked because the form submits to a different public origin.")
            await field.fill(text, timeout=10_000)
            await button.click(timeout=10_000)
            await page.wait_for_timeout(1_000)
            final_host, _ = _public_http_url(page.url)
            if final_host != initial_host:
                raise ValueError("The interaction redirected to an unexpected host.")
            return {
                "initial_url": url,
                "final_url": page.url,
                "title": (await page.title()).strip()[:300],
                "text": (await page.inner_text("body", timeout=15_000))[:20_000],
                "interaction": "One labeled text field was filled and one named button was clicked after explicit user approval.",
            }
        finally:
            await browser.close()
