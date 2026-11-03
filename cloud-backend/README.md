---
title: USMAN AND UMAR OpenSwarm Multi-Agent Backend
emoji: "⚡"
colorFrom: blue
colorTo: purple
sdk: docker
app_port: 7860
---

# USMAN AND UMAR — OpenSwarm backend

FastAPI backend for the Android workspace. Browser execution uses direct Playwright; the Python backend does not require the `browser-use` package.

## Current OpenSwarm architecture

The backend now has an extensible specialist registry with these agent identities:

- Manager / Orchestrator
- Planner
- Research
- Browser
- Coding
- Terminal
- GitHub
- File Manager
- Automation
- Android Phone
- Data Analysis
- Document
- Drive
- Security
- Verification
- Delivery

Registration is not a claim that every adapter is connected. The `GET /capabilities` endpoint explicitly distinguishes registered agents from configured adapters. In this backend revision, the manager/planner/research/coder reasoning paths use the configured OpenAI-compatible provider, and Browser uses Playwright. Terminal, GitHub write, Drive, Android phone, general filesystem, document, data and automation adapters are not yet wired into the cloud runtime; those operations must be executed by an authorized connected adapter or are reported unavailable. The Android app already has separate local connectors and action-dispatch systems; they are not silently simulated by this backend.

The Manager requests a bounded structured plan and dispatches specialist steps. Specialist output is stored with explicit verification metadata. Generated code or model reasoning is not reported as executed code. Browser output is verified only to the extent that a real Playwright navigation and non-empty page extraction succeeded.

## Task state and API

- `GET /health` — lightweight service health.
- `GET /agents` — registered specialist agents.
- `GET /capabilities` — registered capabilities and adapter availability.
- `POST /execute` — authenticated execution; retains the Android client's existing request/response contract.
- `GET /tasks/{task_id}` — persisted task state, plan and result.
- `GET /tasks/{task_id}/logs` — task event journal.

Task states include `PENDING`, `PLANNING`, `READY`, `RUNNING`, `VERIFYING`, `COMPLETED`, `WAITING_FOR_USER`, `PAUSED`, `FAILED`, `RETRYING`, and `CANCELLED`. The SQLite store defaults to `/tmp/usman-openswarm.sqlite3` and can be redirected with `OPENSWARM_DB_PATH`. Colab's local filesystem is ephemeral: this database survives process restarts only while the underlying runtime storage remains available. It is not a cross-runtime durable store.

`POST /execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>` and JSON with `task_id` and `prompt`. It returns the result string in the existing `{"result":"..." }` envelope. OpenSwarm results contain a JSON report with specialist outputs and verification state. The endpoint is request/response execution, not a background job queue; the app's existing persistent Task Manager remains the client-side task history.

## Run in Google Colab

Use `USMAN_COLAB_BACKEND.ipynb`. Add these values in Colab **Secrets** and grant notebook access:

- `APP_CLIENT_TOKEN`: strong random Bearer token; configure the same value in the Android app.
- `NVIDIA_API_KEY`: preferred OpenAI-compatible reasoning provider.
- `OPENROUTER_API_KEY`: alternative provider; the default model is `openrouter/free`, optionally overridden by `OPENROUTER_MODEL`.

Public-page Playwright scraping does not need an inference key. OpenSwarm planning/reasoning does need a configured provider key. A model advertised as free can still have availability, rate, quota or routing limits; no paid API is mandatory for ordinary local Android actions.

The notebook installs Playwright-managed Chromium and starts FastAPI. Tunnel URLs are temporary unless the selected tunnel provider explicitly guarantees a reserved domain. Colab sessions can disconnect or expire; periodic pings cannot override Google's runtime and quota limits. Treat a tunnel or Colab server as available only after a real health check.

## Security boundaries

- API requests require a Bearer token.
- Browser navigation is limited to public HTTP(S) hostnames and the request's allowlist.
- Private/local hostnames and credential-bearing URLs are rejected.
- Browser execution is bounded by a server timeout and one execution slot.
- High-impact capabilities require explicit approval in the capability registry.
- Unconfigured adapters fail explicitly; they do not return fabricated success.
- Secrets must remain in Colab Secrets or the Android protected credential store, never in source control or task logs.

Browser automation does not bypass CAPTCHAs, logins, paywalls or access controls. Browser form submission, purchasing and account changes are not enabled by this scraper.

## Tests

From `cloud-backend/`:

```sh
python -m pip install -r requirements.txt
python -m playwright install chromium
python -m compileall -q .
python -m unittest discover -v
```

GitHub Actions runs backend syntax and unit/contract tests on changes to this directory.
