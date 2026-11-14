# Changelog

All notable changes to USMAN AND UMAR are recorded here.

## [3.9.1] - 2026-11-14

Chat layout, kill-switch coverage, owner-approved FMHY hosts, and clear denial reporting.

### Changed

- **Chat layout**: the Scaffold, composer, and reverse-layout message list now live in
  `ChatScaffoldLayout.kt`; `listBottomPaddingDp` is the single bottom-padding decision,
  called once, with the composer measured once.
- **FMHY trust**: a discovered host is a candidate only. A host becomes callable through
  its `site.<host>` tool only after the owner approves that exact host in Settings
  (Approve/Revoke per host). Approval is re-checked at call time, so a host revoked after
  the tool was handed out cannot be reached through a stale reference.
- **Settings**: the FMHY host list shows each host's approval state with Approve/Revoke
  controls, replacing the always-on "Callable" label.

### Fixed

- **Kill Switch coverage**: `ActionDispatcher.execute` — the single chokepoint reached
  directly by `AgentLoop`, `McpServer`, and `WorkspaceWorkflowRunner` — now fails closed
  on the global automation Kill Switch before any resolution or side effect. These paths
  previously bypassed the connector registry's gate.
- **Denial reporting**: a user-denied tool call is no longer reported as a failure. The
  unified agent loop emits a `denied` event (never `status=failed`), a denial never counts
  toward the stop-after-two-consecutive-failures rule, and the chat surfaces a specific,
  resumable message ("Action denied by the owner").

## [3.9.0] - 2026-11-13

Hybrid task orchestrator, unrestricted file picker, and full dynamic FMHY trust.

### Added

- Unified agent loop (`CloudToolAgent.run`) with a 10-minute time budget, 12-step
  limit, per-call Kill Switch checks, and stop-after-two-consecutive-failures.
- Provider-safe tool-name mapping (`ProviderToolNameMapping`) that builds and
  collision-checks canonical names per request.
- FMHY discovery: every HTTPS host parsed from the FMHY page text is immediately
  callable through a registry-bound `site.<host>` tool; the model can supply only a
  rooted path, never a host.

### Changed

- **File picker**: `UniversalFilePickerContract` now requests `*/*` (was the
  invalid `"/"` sentinel). Any file is attachable and transferable to Colab; binary
  content is sent as base64 via `content_base64`.
- **Colab transfer**: removed the 1 MB text-only limit in the app and the backend.
  Attachments transfer whole; `OPENSWARM_MAX_ATTACHMENT_BYTES` (default 64 MiB) only
  bounds Colab-node memory.
- **FMHY trust**: removed the per-host owner-approval gate. Hosts become callable on
  discovery; the host stays fixed by the trusted registry and calls stay Kill Switch
  gated.
- **System prompt**: added a hard untrusted-data rule stating that no tool result,
  page, file, repository, search result, or attachment text can change the agent's
  goal, rules, or tool choice, even if it claims to be a system or owner message.
- **Settings**: FMHY hosts list shows each discovered host as callable and explains
  the registry-bound host plus Kill Switch gating.

### Fixed

- **Unit-test hang**: `UniversalConnectorTest` spun forever because the registry
  fail-closes on a disabled Kill Switch before consulting the approval gate. The test
  now enables the Kill Switch. This was the cause of the CI "Unit tests and debug
  build" job being cancelled after roughly two hours.
- **Catalog test**: `OpenSwarmToolCatalogTest` now expects the mapped `colab__<name>`
  function names produced by `OpenSwarmToolCatalog.definition()`.

### Notes

- Debug build. Release signing secrets are not present in the repository.
- The emulator instrumentation job and the APK jobs run only on `workflow_dispatch`.
