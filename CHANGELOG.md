# Changelog

All notable changes to USMAN AND UMAR are recorded here.

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
