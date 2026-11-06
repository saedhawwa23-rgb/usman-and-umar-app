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

The backend has an extensible specialist registry with these agent identities:

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

Registration is not a claim that every adapter is connected. The `GET /capabilities` endpoint explicitly distinguishes registered agents from configured adapters. In this backend revision, Manager/Planner/Coding use the configured OpenAI-compatible provider; Coding can create a workspace file when the plan supplies `tool_input.output_path`, but the file write is approval-gated and confined to `OPENSWARM_WORKSPACE`; Research performs public DuckDuckGo result retrieval and summarizes returned source URLs; Browser uses Playwright; File Manager supports confined workspace listing, UTF-8 reads and approval-gated writes; Data Analysis summarizes bounded CSV/JSON files; Document creates approval-gated Markdown files in the workspace; and Persistent Memory stores, searches and retrieves SQLite-backed key/value context, with writes requiring approval. Drive, Android phone and general automation adapters remain unavailable until their authorized runtime connectors are configured. Terminal is enabled only with the explicit sandbox opt-in and Bubblewrap/`prlimit` availability. GitHub read/write/pull-request adapters are enabled only when `GITHUB_TOKEN` is configured. The Android app's separate local connectors and action-dispatch systems are not simulated by this backend.

The Manager requests a bounded structured dependency graph and dispatches independent specialist steps in parallel. Safe read-only/transient failures receive up to two bounded retries; write and other side-effecting capabilities are not automatically retried. The current task store supports cooperative pause, cancel and resume checkpoints in its local SQLite state. An in-flight external operation may finish before a control request takes effect. These task-state behaviors are separate from the Drive recovery records documented below: the Drive coordinator module is not, by itself, a replacement for the SQLite task store or a durable queue. Agent handoffs and results are recorded as structured task events, and dependent specialists receive prior result context. Specialist output is stored with explicit verification metadata. Generated code or model reasoning is not reported as executed code. A coder file-write result proves only that the UTF-8 artifact was written and read back with a matching digest; it does not prove that the generated program runs. Browser output is verified only to the extent that a real Playwright navigation and non-empty page extraction succeeded.

## Task state and API

- `GET /health` — explicit readiness. `status` is `ready` only when the bearer token is configured and a Drive JSON content write/read-back/delete probe succeeds; otherwise it is `not_ready`. The response includes `auth_ready`, `drive_ready`, `checkpoint_storage_ready`, `lease_coordinator_ready`, compatibility aliases `checkpoints_ready`/`lease_ready`, `accepting_new_tasks`, `draining`, `server_id`, `instance_id`, `uptime_seconds`, `active_tasks`, `recovery_state`, `recovery_reason`, and `lease_policy`. This is a point-in-time probe, not a guarantee of future Drive availability or transactional ownership.
- `GET /agents` — registered specialist agents.
- `GET /capabilities` — registered agents and adapter readiness.
- `GET /tools` — shared capability registry with adapter availability and risk levels.
- `POST /tools/{capability_id}/execute` — authenticated capability execution with approval gates.
- `POST /execute` — authenticated execution; retains the Android client's existing request/response contract.
- `GET /tasks/{task_id}` — local persisted task state, plan and result.
- `GET /tasks/{task_id}/logs` — task event journal.
- `POST /tasks/{task_id}/pause` — request a cooperative pause; the server confirms `PAUSED` only after the in-flight specialist reaches a checkpoint.
- `POST /tasks/{task_id}/resume` — resume a locally paused task from its saved completed steps.
- `POST /tasks/{task_id}/cancel` — cancel a task at the next specialist boundary.

Task states include `PENDING`, `PLANNING`, `READY`, `RUNNING`, `VERIFYING`, `COMPLETED`, `WAITING_FOR_USER`, `PAUSED`, `FAILED`, `RETRYING`, and `CANCELLED`. The SQLite store defaults to `/tmp/usman-openswarm.sqlite3` and can be redirected with `OPENSWARM_DB_PATH`. Colab's local filesystem is ephemeral: this database survives process restarts only while the underlying runtime storage remains available. It is not a cross-runtime durable store. A task requiring high-impact action moves to `WAITING_FOR_USER`; the Android app displays the requested steps and resubmits the same task ID with `approved_step_ids`. Approval resume is pinned to the originating configured cloud server so the task is not accidentally continued on a different server's local SQLite database.

`POST /execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>` and JSON with `task_id` and `prompt`. It returns the result string in the existing `{"result":"..."}` envelope. OpenSwarm results contain a JSON report with specialist outputs and verification state. The endpoint is request/response execution, not a background job queue; the app's existing persistent Task Manager remains the client-side task history. `OPENSWARM_WORKSPACE` selects the confined file/data/document workspace root (default `/content/usman-workspace`). Memory is stored in the same SQLite database as task state; it persists across backend process restarts only while that runtime's database file survives.

## Google Drive recovery module: architecture and current integration status

`drive_task_lease.py` implements a Google Drive API v3 adapter for leases, immutable checkpoints, action receipts and persisted recovery-history records. `drive_task_runtime.py` lazily obtains ADC credentials, resolves the configured folder, creates per-task execution sessions, heartbeats leases, guards local task-store mutations, records critical-action receipts, writes checkpoints after completed specialist steps, and attempts conservative recovery when an existing lease blocks acquisition. `main.py` wires these lifecycle callbacks into OpenSwarm `/execute` and `/tasks/{task_id}/resume`; the direct browser execution route is not Drive-leased. These paths are still best-effort: Drive files and `appProperties` are the only coordinator persistence, with no local fallback. Drive errors, malformed/duplicate records, integrity errors and ambiguous outcomes fail closed. This does not make external side effects transactional or exactly-once.

**Health/readiness caveat:** startup health performs a small temporary Drive JSON file write, raw-content read-back verification, and deletion; `checkpoint_storage_ready` and `lease_coordinator_ready` indicate that this content path and coordinator construction succeeded at probe time. It does not perform a task-specific lease takeover or prove atomic acquisition. `ready` also requires `APP_CLIENT_TOKEN` to be set. The notebook requires all explicit readiness booleans and `draining=false` before publishing a tunnel. The runtime wires OpenSwarm execution and `/tasks/{task_id}/resume`; direct browser requests are gated by server readiness/drain but do not use per-task Drive leases. External side effects remain subject to the Drive race limitations below.

The data path is: Colab obtains user-authorized Google credentials with the Drive scope; notebook resolves/creates the folder only under the unambiguous rules below, verifies folder access and a temporary JSON write/read-back/content comparison/cleanup; runtime configuration provides the folder ID and per-runtime server/instance IDs; backend uses ADC and Drive API v3 for lease/checkpoint/receipt/history files. Notebook preflight is stronger than backend health, but verifies only access at that moment—not future availability or transactional behavior.

### Drive folder and environment configuration

The notebook uses the folder name `USMAN_UMAR_CHECKPOINTS` as its default. If exactly one matching folder is found in the authenticated user's Drive, it uses that folder. If none is found, it creates one. If multiple matches are found, or the search is incomplete, it stops rather than choosing one. An optional Colab Secret `OPENSWARM_DRIVE_FOLDER_ID` selects a specific folder ID instead; the notebook verifies that it identifies a writable folder. Folder name is not an identity guarantee; Drive permits duplicate names.

The notebook reads these optional values from Colab Secrets into the runtime environment without printing their contents:

| Variable | Default / behavior |
|---|---|
| `APP_CLIENT_TOKEN` | No default. Set a strong token and configure the same value in the Android app. The current API requires it for authenticated routes. |
| `NVIDIA_API_KEY` | Unset unless configured; preferred inference provider. |
| `OPENROUTER_API_KEY` | Unset unless configured; fallback provider when NVIDIA is unset. |
| `OPENROUTER_MODEL` | Backend provider default is `openrouter/free` when OpenRouter is selected and no model override is configured. |
| `GITHUB_TOKEN` | Optional; unset by default. Fine-grained token limited to intended repositories and required permissions. |
| `OPENSWARM_DRIVE_FOLDER_ID` | Optional explicit folder ID. Without it, resolve/create the unambiguous default folder described above. |
| `OPENSWARM_SERVER_ID` | Generated once per Colab runtime by the notebook (`colab-` plus a random identifier); runtime fallback is `socket.gethostname()`. |
| `OPENSWARM_INSTANCE_ID` | Generated once per Colab runtime by the notebook (random UUID); runtime fallback is a generated UUID. |
| `TUNNEL_SUBDOMAIN` | Optional Colab Secret; notebook default is `usman-umar-1`. Use distinct subdomains for concurrent runtimes. |
| `OPENSWARM_WORKSPACE` | Backend default `/content/usman-workspace`. |
| `OPENSWARM_DB_PATH` | Backend default `/tmp/usman-openswarm.sqlite3`; local/ephemeral, not Drive recovery storage. |
| `OPENSWARM_DRAIN_AFTER_SECONDS` | Runtime default `42300` seconds (11h45m); after this uptime health reports draining and new OpenSwarm tasks are rejected. |

`DriveTaskRuntime` reads `OPENSWARM_DRIVE_FOLDER_ID`, `OPENSWARM_SERVER_ID`, and `OPENSWARM_INSTANCE_ID`; without a folder ID it only accepts exactly one already-existing folder with the default name (it does not create one). `DriveTaskLeaseCoordinator` itself requires the Drive service object, folder ID, server ID and instance ID as constructor arguments. Its exact optional timing variables/defaults are:

| Variable | Default | Validation |
|---|---:|---|
| `DRIVE_TASK_LEASE_SECONDS` | `120` seconds | Numeric, at least 1. |
| `DRIVE_TASK_HEARTBEAT_SECONDS` | `20` seconds | Numeric, at least 1. |
| `DRIVE_TASK_MISSED_HEARTBEAT_THRESHOLD` | `3` missed intervals | Numeric, at least 1; converted to integer. |
| `DRIVE_TASK_RECOVERY_GRACE_SECONDS` | `120` seconds | Numeric, at least 0. |
| `DRIVE_TASK_CLOCK_SKEW_SECONDS` | `5` seconds | Numeric, at least 0. |

These are adapter constructor defaults; they are not currently listed in `.env.example`. A per-task session starts a heartbeat loop during OpenSwarm execution; the adapter does not run a separate task-discovery scheduler or periodic fleet-wide recovery worker.

### Drive file names and schemas

All records are JSON files with MIME type `application/json`, stored under the selected Drive folder. Record names are not unique in Drive; readers detect and reject duplicates rather than assuming a unique name.

**Lease:** `task_{task_id}_lease.json`. The canonical content (also mirrored as Drive `appProperties`) contains `task_id`, `owner_server_id`, `owner_instance_id`, `lease_version`, UTC `lease_created_at`, UTC `lease_expiry`, UTC `last_heartbeat`, `status`/`ownership_status`, `checkpoint_id`, `checkpoint_version`, and UTC `updated_at`. Creation first lists for an existing name, then creates, lists again and validates identity. Renewal verifies the observed owner/generation and safety margin, updates, then verifies again; release verifies ownership then deletes. The generation is only a label, not a fencing token.

**Immutable checkpoint:** `task_{task_id}_checkpoint_{checkpoint_id}_v{version}.json`. Content fields: `schema="drive-task-checkpoint"`, `schema_version=1`, `task_id`, `checkpoint_id`, `checkpoint_version`, `owner_server_id`, `owner_instance_id`, `lease_version`, UTC `created_at`, arbitrary object `payload`, and SHA-256 `checksum` over canonical JSON of the other fields. Checkpoint creation rejects an already visible name, writes and verifies visibility, rechecks owner, then links checkpoint ID/version into the lease. Loading requires exactly one matching file and validates bytes/JSON, checksum, schema/version, identity, timestamp and optional expected owner/generation. During later recovery the checkpoint's original owner/generation is provenance; it may be older than the current lease generation.

**Action receipt:** `task_{task_id}_receipt_{idempotency_key}.json`, where `idempotency_key` is SHA-256 of UTF-8 `task_id + NUL + step_name + NUL + logical_action_id`. Content fields: `schema="drive-task-receipt"`, `schema_version=1`, `task_id`, `step_name`, `logical_action_id`, `idempotency_key`, `state`, UTC `created_at`, UTC `updated_at`, and object `details`. Supported states are `PREPARED`, `EXECUTING`, `CONFIRMED`, `FAILED`, `UNKNOWN`, `CANCEL_REQUESTED`, `CANCELLED`, `PENDING`, and `RECONCILIATION_REQUIRED`. A previously confirmed receipt is not overwritten with a non-confirmed state. Duplicate or inconsistent records fail closed. Receipt recording does not make an external operation idempotent and does not prove that an action happened exactly once.

**Recovery history:** `task_{task_id}_recovery_history_{uuid}.json`. Content has `schema="drive-task-recovery-history"`, `schema_version=1`, `task_id`, UTC `recorded_at`, `server_id`, `instance_id`, `generation`, and a `transitions` array. Each transition records prior/new state, timestamp, runtime identity, generation, reason, checkpoint reference, and relevant receipt keys. `recover()` persists a history file and blocks execution if its write/read-back is uncertain.

### Lifecycle and recovery states

Lease freshness is assessed as follows:

- `LEASE_ACTIVE`: not expired and last heartbeat has not crossed the configured missed-heartbeat threshold.
- `LEASE_SUSPECT`: not expired, but heartbeat age is at least `heartbeat_seconds × missed_heartbeat_threshold`. This is diagnostic only; it is not takeover authorization.
- `LEASE_EXPIRED`: lease expiry is at or before current time minus the configured clock-skew allowance.
- `RECOVERY_WAITING`: lease expiry has been recorded, but the full additional grace (plus configured clock-skew allowance) has not elapsed.
- `RECOVERY_VALIDATING`: after the wait, recovery rereads ownership and inspects checkpoint and receipt state.
- `RECOVERY_BLOCKED`: ambiguity, corrupt/missing data, unresolved external work, Drive failure, or uncertain history persistence prevented a safe handoff.
- `RECOVERY_READY`: validation passed under the best-effort policy and a new generation may be proposed; this is not proof of exclusive ownership.
- `RECOVERY_COMPLETED`: the generation proposal and read-back succeeded, not a guarantee that another concurrent writer did not race.

Recovery begins by reading exactly one valid lease. A non-expired lease blocks takeover. For an expired lease, the coordinator waits until `lease_expiry + recovery_grace_seconds + clock_skew_seconds`, unless `recover(wait=False)` returns `RECOVERY_WAITING`. It then rereads and requires the lease owner, generation, creation time and heartbeat to be unchanged and still safely expired. It validates the linked checkpoint, then enumerates and validates receipts. Unresolved receipts in `UNKNOWN`, `EXECUTING`, `PREPARED`, `PENDING`, `RECONCILIATION_REQUIRED`, or `CANCEL_REQUESTED` yield `RECOVERY_BLOCKED`; callers must reconcile them rather than replaying those actions. `CONFIRMED` receipt keys are returned to the caller; the adapter itself does not dispatch or skip execution steps. When validations pass, recovery reaches `RECOVERY_READY`, writes a proposed next lease generation, rereads it and may return `RECOVERY_COMPLETED`. Recovery requires a validated checkpoint; without one it refuses takeover because Drive-only recovery cannot prove that a task was genuinely unstarted. A confirmed receipt is reused in the current critical-step path only if it contains a recoverable dictionary outcome. Any detected conflict, invalid data or uncertain Drive result returns a blocked outcome; successful read-back remains best-effort, not proof of exclusive ownership.

The module exposes `LeaseConflict`, `LeaseUnavailable`, and `CheckpointIntegrityError`; runtime errors are surfaced by `/execute` as structured `error_code` responses (lease conflict/stale owner/recovery/checkpoint conflict typically HTTP 409; Drive or other unavailable cases HTTP 503) and resume similarly returns explicit error codes. Fail-closed cases include missing/duplicate lease, Drive errors, invalid identity/timestamp, duplicate checkpoint/receipt, checksum/schema mismatch, changed lease, absent checkpoint, unresolved receipt, or uncertain recovery-history persistence. The runtime has no background fleet-wide task discovery or autonomous recovery worker. Closing an execution session stops its heartbeat but does not delete/release the Drive lease; it becomes eligible only after expiry, grace, validation, and the best-effort recovery path.

### Starting in Colab

Use `USMAN_COLAB_BACKEND.ipynb` with `APP_CLIENT_TOKEN` and any provider/GitHub values entered in Colab **Secrets**, with notebook access enabled. The notebook reads `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, and `GOOGLE_OAUTH_REFRESH_TOKEN` from Colab Secrets and obtains Application Default Credentials using `https://www.googleapis.com/auth/drive.file` plus `https://www.googleapis.com/auth/cloud-platform`. The `drive.file` scope is per-file: the checkpoint folder and existing contents must have been created by or explicitly opened/shared with this OAuth app. A folder ID alone does not grant access. The notebook selects or creates the folder only under the unambiguous rules above, checks folder write capability, writes a uniquely named temporary JSON file, downloads it, checks exact bytes and parsed content, and deletes it. Any failed check (including cleanup) raises visibly and prevents FastAPI startup; it does not fall back to `/tmp` for Drive persistence.

After preflight it sets `OPENSWARM_DRIVE_FOLDER_ID`, `OPENSWARM_SERVER_ID` and `OPENSWARM_INSTANCE_ID`, launches FastAPI, and polls `/health`. It publishes no URL and launches no tunnel unless the response is HTTP 200 JSON with `ready=true`, `auth_ready=true`, `drive_ready=true`, `checkpoints_ready=true`, `checkpoint_storage_ready=true`, `lease_ready=true`, `lease_coordinator_ready=true`, `accepting_new_tasks=true`, and `draining=false`. `status` must be `ready`; missing fields, false flags, request errors or server exit are not ready. Once ready, it launches Localtunnel using `TUNNEL_SUBDOMAIN` (default `usman-umar-1`) and prints its URL. It then retains the existing background install of `requirements-ai.txt`; an AI-package install failure is visible in `/content/pip_ai.log`.

A public tunnel URL is temporary. Colab sessions can disconnect or expire; periodic pings cannot override Google's runtime and quota limits. Treat a server as available only after its real readiness check, and keep the same `APP_CLIENT_TOKEN` protected in Colab Secrets and the Android protected credential store.

### Drain and persistence limits

Colab does not provide this notebook with a dependable runtime shutdown/drain hook. `OPENSWARM_DRAIN_AFTER_SECONDS` defaults to 42300 seconds; this uptime-based application policy makes health report `draining`/`accepting_new_tasks=false` and causes new `/execute` work to receive HTTP 503 `SERVER_DRAINING`. It does not call a Colab shutdown hook, guarantee active work has finished, or delay forced runtime termination. Runtime expiration, disconnection, or forced termination may stop FastAPI while a request is in flight; do not assume a task is checkpointed before shutdown or that the notebook can run final cleanup. The temporary Drive preflight file is cleaned up during ordinary notebook execution, but forced process termination can interrupt cleanup.

The Drive coordinator is **shared persistence, not transactional locking**. Google Drive allows duplicate file names and does not provide atomic create-if-absent or conditional compare-and-swap for this implementation. The list-then-create sequence can race; listing/read-back may not reveal a concurrent writer; updates/deletes have no conditional generation guard. There is no guaranteed single owner, no guaranteed fencing against stale workers, and no guarantee from a successful read-back. A new lease generation is not a fencing token. Two runtimes can both believe they own work, particularly around simultaneous acquisition or takeover. This design reduces ordinary duplicate starts, detects many conflicts and fails closed on ambiguity, but it does not guarantee at-most-once external side effects. Do not use Drive ownership alone for high-impact side effects or strict exclusivity; callers requiring those guarantees must fail closed or use an independently selected coordinator with atomic conditional writes.

### Android readiness and error handling

The Android cloud client accepts a server for new work only when `/health` returns HTTP 200, `status="ready"`, `accepting_new_tasks=true`, `draining=false`, and explicit `drive_ready`, `checkpoint_storage_ready`, and `lease_coordinator_ready` flags are all true. Missing readiness fields are treated as not ready. The existing Settings server rows show the reported server/instance, recovery state/reason, active count, and readiness flags. Structured error responses preserve `error_code`, message, task ID, recovery state/action and HTTP status with credential sanitization. The existing Localtunnel bypass header and execution timeouts remain unchanged. Cloud chat cancellation targets the server ID captured at dispatch, keeps control requests available to reachable draining servers, and polls task state before showing `CANCELLED`; a timeout or unconfirmed state remains `UNKNOWN`, never false cancellation success.

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

No live Drive test result is claimed here. The lease and lifecycle tests use fake Drive clients; the concurrent-acquisition case intentionally demonstrates a race and does not prove a single-owner guarantee. Mocked API tests cover fail-closed readiness, drain responses, structured errors, and the OpenSwarm checkpoint/receipt integration, but do not verify Google authentication or actual Drive consistency. From `cloud-backend/`, run the following commands in an environment with `requirements.txt` installed:

```sh
python -m compileall -q .
python -m unittest -v test_drive_task_lease.py
python -m unittest discover -v
```

Verified in the implementation environment with Python 3.12 and the declared requirements installed: `python -m compileall -q .` passed; `python -m unittest discover -v` passed **65 tests**. The focused Drive module `python -m unittest -v test_drive_task_lease.py` passed **15 tests**: **14 sequential best-effort tests** and **1 simulated concurrency limitation test**. The latter uses two clients with a deliberately stale visibility view to show both may accept an acquisition proposal; it is evidence of the race, not proof of atomic exclusivity. No live Google Drive, Colab shutdown/drain, or Android execution test was run.

GitHub Actions also runs backend syntax and unit/contract tests on changes to this directory.
