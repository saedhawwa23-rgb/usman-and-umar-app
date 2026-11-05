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
- Persistent Memory

Registration is not a claim that every adapter is connected. The `GET /capabilities` endpoint explicitly distinguishes registered agents from configured adapters. In this backend revision, Manager/Planner/Coding use the configured OpenAI-compatible provider; Coding can create a workspace file when the plan supplies `tool_input.output_path`, but the file write is approval-gated and confined to `OPENSWARM_WORKSPACE`; Research performs public DuckDuckGo result retrieval and summarizes the returned source URLs; Browser uses Playwright; File Manager supports confined workspace listing, UTF-8 reads and approval-gated writes; Data Analysis summarizes bounded CSV/JSON files; Document creates approval-gated Markdown files in the workspace; and Persistent Memory stores, searches and retrieves SQLite-backed key/value context, with writes requiring approval. Drive, Android phone and general automation adapters remain unavailable until their authorized runtime connectors are configured. Terminal is enabled only with the explicit sandbox opt-in and Bubblewrap/`prlimit` availability. GitHub read/write/pull-request adapters are enabled only when `GITHUB_TOKEN` is configured. The Android app already has separate local connectors and action-dispatch systems; they are not silently simulated by this backend.

The Manager requests a bounded structured dependency graph and dispatches independent specialist steps in parallel. Safe read-only/transient failures receive up to two bounded retries; write and other side-effecting capabilities are not automatically retried. Tasks support cooperative pause, cancel and resume checkpoints; an in-flight external operation is allowed to finish before the control request takes effect. Agent handoffs and results are recorded as structured task events, and dependent specialists receive prior result context. Specialist output is stored with explicit verification metadata. Generated code or model reasoning is not reported as executed code. A coder file-write result proves only that the UTF-8 artifact was written and read back with a matching digest; it does not prove that the generated program runs. Use a separate approved Terminal step for sandboxed execution and tests when the runtime supports it. Browser output is verified only to the extent that a real Playwright navigation and non-empty page extraction succeeded.

## Task state and API

- `GET /health` — lightweight service health.
- `GET /agents` — registered specialist agents.
- `GET /capabilities` — registered agents and adapter readiness.
- `GET /tools` — shared capability registry with adapter availability and risk levels.
- `POST /tools/{capability_id}/execute` — authenticated capability execution with approval gates.
- `POST /execute` — authenticated execution; retains the Android client's existing request/response contract.
- `GET /tasks/{task_id}` — persisted task state, plan and result.
- `GET /tasks/{task_id}/logs` — task event journal.
- `POST /tasks/{task_id}/pause` — request a cooperative pause; the server confirms `PAUSED` only after the in-flight specialist reaches a checkpoint.
- `POST /tasks/{task_id}/resume` — resume a paused task from its saved completed steps.
- `POST /tasks/{task_id}/cancel` — cancel a task at the next specialist boundary.

Task states include `PENDING`, `PLANNING`, `READY`, `RUNNING`, `VERIFYING`, `COMPLETED`, `WAITING_FOR_USER`, `PAUSED`, `FAILED`, `RETRYING`, and `CANCELLED`. The SQLite store defaults to `/tmp/usman-openswarm.sqlite3` and can be redirected with `OPENSWARM_DB_PATH`. Colab's local filesystem is ephemeral: this database survives process restarts only while the underlying runtime storage remains available. It is not a cross-runtime durable store. A task requiring high-impact action moves to `WAITING_FOR_USER`; the Android app displays the requested steps and resubmits the same task ID with `approved_step_ids`. Approval resume is pinned to the originating configured cloud server so the task is not accidentally continued on a different server's local SQLite database.

`POST /execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>` and JSON with `task_id` and `prompt`. It returns the result string in the existing `{"result":"..." }` envelope. OpenSwarm results contain a JSON report with specialist outputs and verification state. The endpoint is request/response execution, not a background job queue; the app's existing persistent Task Manager remains the client-side task history. `OPENSWARM_WORKSPACE` selects the confined file/data/document workspace root (default `/content/usman-workspace`). Memory is stored in the same SQLite database as task state; it is persistent across backend process restarts only while that runtime's database file survives.

## Run in Google Colab

Use `USMAN_COLAB_BACKEND.ipynb`. Add these values in Colab **Secrets** and grant notebook access:

- `APP_CLIENT_TOKEN`: strong random Bearer token; configure the same value in the Android app.
- `NVIDIA_API_KEY`: preferred OpenAI-compatible reasoning provider.
- `OPENROUTER_API_KEY`: alternative provider; the default model is `openrouter/free`, optionally overridden by `OPENROUTER_MODEL`.
- `GITHUB_TOKEN` (optional): fine-grained GitHub token limited to the intended repositories, with Contents read/write and Pull requests permissions as required. It is read from Colab Secrets, never printed or stored in the APK. GitHub writes and pull-request creation pause for explicit user approval.
- `OPENSWARM_ENABLE_SANDBOX=1` (optional): enables the Terminal adapter only when Bubblewrap (`bwrap`) and `prlimit` are installed. It runs without network access, clears inherited environment variables, limits CPU/memory/file/process resources and exposes only the OpenSwarm workspace as writable storage.

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


## Drive task lease prototype — important limitation

`drive_task_lease.py` contains a Google Drive API v3 adapter that names lease records `task_{task_id}_lease.json` and stores `task_id`, `owner_server_id`, `owner_instance_id`, `lease_version`, `lease_expiry`, and `ownership_status` in Drive `appProperties`. A visible existing lease causes immediate conflict/backoff, and Drive read/create/renewal ambiguity fails closed. The adapter has focused unit tests.

This is **best-effort coordination only**. Google Drive permits duplicate file names, and its file API does not provide atomic create-if-absent, transactions, or compare-and-swap. The list-then-create sequence can race, file listing may not immediately expose another creator's file, and updating/deleting metadata is not conditional on a version. Therefore the adapter does **not** guarantee that two servers can never execute concurrently, even when it detects ordinary conflicts. It is not yet wired into `/execute`, `/resume`, startup Drive authentication, or the task-step execution boundary. Do not describe this prototype as a safe distributed lock or use it as the sole mechanism for high-impact side effects. Strict mutual exclusion requires a transactional coordinator or another service with atomic conditional writes; absent that, callers must treat Drive ownership as advisory and execution as potentially overlapping.
