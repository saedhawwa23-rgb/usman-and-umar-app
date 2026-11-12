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

The backend has a specialist registry for Manager/Orchestrator, Planner, Research, Browser, Coding, Terminal, GitHub, File Manager, Automation, Android Phone, Data Analysis, Document, Drive, Security, Verification, Delivery, and Persistent Memory. Registration does not mean an adapter is connected: `GET /capabilities` distinguishes registered agents from configured adapters. Model-backed agents require a configured provider; unconfigured tools fail explicitly. Browser access is limited to public HTTP(S) hosts allowed by the request.

Task lifecycle data, ordered task events, results, execution receipts, checkpoints, and lease metadata are stored as one JSON document in Upstash Redis under `task_{task_id}`. The adapter uses the Upstash REST API with JSON-array `SET` and `GET` commands through Python `requests`. Agent persistent memory remains a separate local SQLite feature; it is not part of task checkpoint persistence.

Production task persistence is Redis-only: the Colab notebook requires `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`, and validates a live JSON round-trip before starting the API. Google Drive and Firestore are not required by this backend. A local SQLite task-state fallback exists only for development and tests and reports the service as not ready; it must not be used as evidence that a production Colab node is eligible.

## Android Unified Agent and tool discovery

The Android app exposes three distinct chat targets: the direct API-model conversation, direct Cloud OpenSwarm execution, and **Unified Agent**. Unified Agent uses the user's selected API provider for planning and tool calls, while dispatching registered tools through local Android actions and the authenticated OpenSwarm connector. It is an Android client-side loop; it does not turn the backend's request/response task API into a background queue or imply that the API provider itself can access the phone.

Before planning, the agent refreshes the backend tool capabilities and registers only tools with an app-owned argument schema, a configured adapter, and a recognized risk label. High-risk or destructive calls wait for an explicit, one-call approval in the Android UI; secret-like argument fields are redacted in the dialog. The agent is bounded to 12 model steps and 10 minutes, treats tool output as untrusted input, records visible activity in chat, and does not automatically retry a failed tool dispatch that may already have produced side effects.

FMHY discovery is an Android connector feature implemented through `browser.extract`: it reads `https://fmhy.net`, extracts visible anchor URLs, and returns bounded, validated public HTTP(S) destinations as untrusted data. No host-specific extraction tools are registered. The agent can use the generic `colab_browser_extract` capability with a discovered destination URL and a per-call allowed-domain value, so external resource hosts are not locked to `fmhy.net`. The browser rejects DNS names that resolve to non-global addresses and checks browser network requests; the Android approval dialog warns when an interaction targets unencrypted HTTP.

`browser.interact` is a separate **high-risk** capability and the backend refuses it unless the Android client sends the explicit one-call approval flag. It is intentionally limited to one accessible-label ordinary text field and one named button, on a fresh browser context without saved user cookies. Credential, secret, payment, verification, and file-upload fields are blocked, as are purchase/login/delete/verification button labels. The approval dialog shows the target URL, field label, submitted text, and button. This is not blanket trust in public pages: page instructions and returned content remain untrusted, and CAPTCHA, login, payment, or account actions are not supported.

Chat-message delivery state and the queued outbox are persisted locally by the Android Room database, independently of the backend's Redis task documents. Messages appear in the UI immediately and are updated through `QUEUED`, `SENDING`, `SENT`, `COMPLETED`, or `FAILED` states; this local outbox is not a substitute for remote Redis task persistence. The Redis lease/update limitations described below still apply: REST GET followed by SET is best-effort and is not atomic distributed coordination.

## Upstash configuration

Create an Upstash Redis database and copy its REST URL and REST token. Add these values as Colab Secrets named `UPSTASH_REDIS_REST_URL` and `UPSTASH_REDIS_REST_TOKEN`, enable notebook access, and keep the token private. Also configure `APP_CLIENT_TOKEN`; use the same value in the Android app. The notebook runs a temporary JSON `SET`/`GET`/`DEL` probe before starting FastAPI and does not print secret contents.

From `cloud-backend/`, a live connectivity check can be run with:

```sh
export UPSTASH_REDIS_REST_URL='https://...'
export UPSTASH_REDIS_REST_TOKEN='...'
python test_redis.py
```

The script creates a unique temporary `task_*` key, verifies exact JSON round-trip, and attempts cleanup. It requires real Upstash credentials and network access. Unit tests use a local fake REST endpoint and do not prove live account configuration.

Relevant environment variables:

| Variable | Behavior |
|---|---|
| `UPSTASH_REDIS_REST_URL` | Required HTTPS REST endpoint for production task persistence. |
| `UPSTASH_REDIS_REST_TOKEN` | Required bearer credential; never log or commit it. |
| `APP_CLIENT_TOKEN` | Required API bearer token, configured to match the Android app. |
| `NVIDIA_API_KEY` | Optional preferred inference provider. |
| `OPENROUTER_API_KEY` | Optional fallback inference provider. |
| `GITHUB_TOKEN` | Optional GitHub adapter credential. |
| `OPENSWARM_SERVER_ID` / `OPENSWARM_INSTANCE_ID` | Runtime identity; the notebook generates unique values at startup. |
| `REDIS_TASK_LEASE_SECONDS` | Lease expiry interval (default 120 seconds). |
| `REDIS_TASK_HEARTBEAT_SECONDS` | Worker heartbeat interval (default 20 seconds). |
| `OPENSWARM_DRAIN_AFTER_SECONDS` | Runtime drain threshold (default 42,300 seconds). |
| `OPENSWARM_WORKSPACE` | Confined workspace root (default `/content/usman-workspace`). |
| `OPENSWARM_DB_PATH` | Local SQLite path used for the separate agent-memory feature. |

For local development without Upstash variables, the task-state factory retains the SQLite store so unit tests and local inspection remain usable. Such a backend reports `not_ready`; the Colab production notebook requires both Redis secrets and a successful remote probe before advertising the server.

## Task API and lifecycle

- `GET /health` reports `ready` only when the Upstash REST SET/GET probe and `APP_CLIENT_TOKEN` are configured, and the instance is not draining. `redis_ready`, `checkpoint_storage_ready`, and `lease_coordinator_ready` are explicit readiness flags. Readiness is point-in-time, not a guarantee of future availability or exclusive task ownership.
- `GET /agents`, `GET /capabilities`, and `GET /tools` expose configured services.
- `POST /tools/{capability_id}/execute` runs a configured capability with approval gates.
- `POST /execute` retains the Android client's existing request/response contract.
- `GET /tasks/{task_id}` reads task state, plan, and result from the shared JSON document.
- `GET /tasks/{task_id}/logs` returns the ordered event journal stored in that document.
- `POST /tasks/{task_id}/pause`, `/cancel`, and `/resume` use the task document and its execution metadata. Pause/cancel remain cooperative at specialist boundaries.

Task states include `PENDING`, `PLANNING`, `READY`, `RUNNING`, `VERIFYING`, `COMPLETED`, `WAITING_FOR_USER`, `PAUSE_REQUESTED`, `PAUSED`, `CANCEL_REQUESTED`, `CANCELLED`, `FAILED`, and `RETRYING`. Approval waits are recorded under the same task ID. A critical action is given a stable idempotency key and a receipt state; ambiguous side effects are not automatically replayed. Completed specialist boundaries save a checkpoint in the task JSON. The endpoint is request/response execution, not a background job queue.

## Storage and lease limitations

The task key is a single JSON value containing task state, plan, result, events, runtime lease metadata, receipts, and the latest checkpoint payload. Updates in one Python process serialize GET/modify/SET operations with a process-local lock. **Upstash REST GET followed by SET is not compare-and-swap or a distributed transaction.** Concurrent servers can race while modifying the same task document; an observed lease generation is not a true fencing token. This implementation must not be described as guaranteeing a single owner, exactly-once side effects, or strict cross-server exclusion. Ambiguous state fails closed where detected, but race-free coordination requires an atomic Redis operation (for example, a vetted Lua/CAS protocol), which this simple REST SET/GET adapter does not claim to provide.

A Redis availability/health probe verifies one temporary SET/GET/DEL round-trip only. It does not prove long-term service availability, quotas, durability configuration, account billing, or concurrent lease safety. Upstash retention and quotas follow the configured account/database plan; this backend does not currently assign TTLs to task keys or implement automatic task-history pruning.

## Android readiness and error handling

The Android cloud client accepts a server only when `/health` returns HTTP 200, `status="ready"`, `accepting_new_tasks=true`, `draining=false`, and explicit `redis_ready`, `checkpoint_storage_ready`, and `lease_coordinator_ready` flags are true. Missing flags are treated as not ready. Structured error responses preserve error code, message, task ID, recovery state/action, and HTTP status with credential sanitization. The Localtunnel bypass header and existing execution timeouts remain unchanged. Cloud cancellation targets the server selected at dispatch and polls task state before showing `CANCELLED`; unknown outcomes are not reported as confirmed cancellation.

## Security boundaries

- API requests require a Bearer token.
- Browser navigation is limited to public HTTP(S) hostnames and the request allowlist.
- Private/local hostnames and credential-bearing URLs are rejected.
- Browser execution is bounded by a server timeout and one execution slot.
- High-impact capabilities require explicit approval.
- Secrets belong in Colab Secrets or Android protected storage, never source control or task logs.
- Redis stores task prompts, plans, results, receipts, and checkpoint content. Configure database access and retention accordingly; avoid placing unnecessary sensitive data in prompts.

Browser automation does not bypass CAPTCHAs, logins, paywalls, or access controls. Browser form submission, purchasing, and account changes are not enabled by this scraper.

## Tests

Run the following from `cloud-backend/` after installing `requirements.txt`:

```sh
python -m compileall -q .
python -m unittest -v test_redis_task_storage.py
python -m unittest discover -v
```

The unit suite uses a fake Upstash REST endpoint for deterministic protocol, task-state, event, lease, receipt, and checkpoint tests. It does not contact a live Upstash database or prove atomic multi-server ownership. Run `python test_redis.py` separately with real secrets to verify a live JSON SET/GET round-trip.
