---
title: USMAN AND UMAR OpenSwarm Multi-Agent Backend
emoji: "⚡"
colorFrom: blue
colorTo: purple
sdk: docker
app_port: 7860
---

# USMAN AND UMAR OpenSwarm Multi-Agent Backend

This FastAPI service uses raw Playwright for public-page scraping and optional named-link clicks. The optional Python OpenSwarm route keeps its Manager/Browser/Coder graph; its browser specialist also uses raw Playwright.

The browser helper accepts a public `http(s)` URL and returns the page's body text. For a click action, it finds the requested link by text, follows it, and returns the destination page text. Private/local hostnames and credential-bearing URLs are rejected. The scraper does not sign in, submit forms, handle payments, bypass CAPTCHAs, or change account settings.

## Run in Google Colab

Use `USMAN_COLAB_BACKEND.ipynb` from the repository. It installs FastAPI, Playwright and the optional OpenSwarm dependencies, installs Playwright-managed Chromium, starts the server on port 7860, then launches a Cloudflare Quick Tunnel.

Add these values in Colab **Secrets** and grant notebook access:

- `APP_CLIENT_TOKEN`: a strong random Bearer token. Configure the same value in the Android app.
- `NVIDIA_API_KEY`: preferred provider for OpenSwarm reasoning.
- `OPENROUTER_API_KEY`: fallback provider for OpenSwarm reasoning when `NVIDIA_API_KEY` is unset. The default model is `openrouter/free`; override it with optional `OPENROUTER_MODEL`.

Raw browser scraping does not require an inference API key. The optional OpenSwarm route requires `NVIDIA_API_KEY` or `OPENROUTER_API_KEY`.

The Quick Tunnel cell prints a temporary `https://….trycloudflare.com` URL. Copy that base URL—without `/execute`—into the Android app's **Task Manager → Cloud Browser Use settings → Cloud Backend URL**. The app appends `/execute` automatically. Quick Tunnel URLs are not reserved and may change after the tunnel or Colab runtime restarts; update the Android setting when a new URL is printed. Keep the Colab runtime and tunnel process running while using the API. Protect the Android Bearer token because the tunnel URL is publicly reachable.

## API

`GET /health` returns `{"status":"ok"}` and only confirms the server is running.

`POST /execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>` and JSON with `task_id` plus `prompt`. Browser plugin requests pass the URL in the prompt directly to Playwright. An explicit link phrase can trigger a click, for example `Open "About" on https://example.com`. An FMHY request without a URL starts at `https://fmhy.net`; requests such as `Find an image generator on FMHY` use the matching link text. `openswarm_agent` continues to use the optional Manager Agent graph.

```json
{
  "task_id": "task-123",
  "prompt": "https://docs.python.org/3/tutorial/"
}
```

FMHY example:

```json
{
  "task_id": "task-fmhy-123",
  "prompt": "Find an image generator on FMHY",
  "plugin_id": "fmhy_browser"
}
```

A successful response is `{"result":"<extracted page text>"}`. Execution is bounded by a server-side timeout and all failures remain JSON errors. Configuration, validation, rate-limit, and execution failures return JSON errors with appropriate HTTP status codes. The endpoint allows up to 20 requests per source IP per minute and runs one browser task at a time.

Full Python tracebacks are written to the backend server log for diagnosis and are not sent over the public API, where they could expose credentials or runtime internals. In the supplied Colab notebook the log is `/tmp/usman-fastapi.log`; inspect it with:

```bash
!tail -n 100 /tmp/usman-fastapi.log
```

## Docker or Render deployment

The Dockerfile installs the dependencies in `requirements.txt` and Playwright-managed Chromium, then copies the FastAPI, scraper, provider, and OpenSwarm modules. For Hugging Face Spaces or another Docker host, place the contents of `cloud-backend/` at the deployment repository root and set `APP_CLIENT_TOKEN`. Configure an inference key only when using OpenSwarm. For Render, the included manifest installs Chromium during the build.

## Local development

From `cloud-backend/`, install `requirements.txt`, install Playwright Chromium with `python -m playwright install chromium`, then run:

```sh
APP_CLIENT_TOKEN=local-test-token \
NVIDIA_API_KEY=your-nvidia-key \
python -m uvicorn main:app --host 0.0.0.0 --port 7860
```

Set an inference key only if you plan to use OpenSwarm. Run the backend contract and provider-selection tests with:

```sh
python -m unittest -v test_auth.py
```
