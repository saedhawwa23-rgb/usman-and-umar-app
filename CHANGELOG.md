# Changelog

All notable changes to USMAN AND UMAR are recorded here.

## [3.10.8] - 2026-11-19

Real web AI agent platform integration: discover, connect, submit, monitor, retrieve.

### Added

- **`core/agentplatform/OpenHandsClient.kt`** — a real REST client for the OpenHands agent server,
  written against the server's published OpenAPI document and verified against a live v1.60.1
  server. Covers `GET /api/settings`, `POST /api/conversations`, `POST /api/conversations/{id}/events`,
  `GET /api/conversations/{id}`, `GET /api/conversations/{id}/events/search`,
  `GET /api/conversations/{id}/agent_final_response` and `POST /api/conversations/{id}/interrupt`.
  It also supports the documented authenticated workspace file upload and download endpoints,
  with a 25 MB transfer limit and absolute-path traversal checks. The UI lets users pick an input
  file and save a remote file by exact path; remote file enumeration is not exposed by this API.
  The session key travels only in the `X-Session-API-Key` header; no credential is placed in a URL,
  a log line, or an error string.
- **`core/agentplatform/PlatformConnectionStore.kt`** — connection management backed by the existing
  `KeystoreSecretRecords` AES-GCM stack. Only non-secret metadata (URL, model, timestamp, key hint)
  is stored in plain preferences, so a prefs dump cannot leak the key.
- **`core/agentplatform/AgentPlatformTaskRepository.kt`** — persistent task records in a dedicated
  Room database (`agent_platform_database`), deliberately separate from `opendroid_database` so the
  existing migration chain is untouched.
- **`ui/viewmodel/AgentPlatformViewModel.kt`** and **`ui/screens/AgentPlatformScreen.kt`** — the
  workflow: search the catalog, validate and store a connection, submit instructions, poll live
  execution every 3 s, read the final answer, cancel, attach/download files, and reopen past tasks
  from history. Blocking API calls run on the IO dispatcher, not the UI thread.
- **`OpenHandsLiveE2ETest`** — three JVM tests that run against a real server when
  `OH_E2E_BASE_URL`/`OH_E2E_SESSION_KEY` are supplied, and are skipped otherwise. The main test
  creates a real conversation, uploads an input file, waits for real execution, and asserts that the
  generated output can be downloaded with exact content.

### Changed

- `AgentPlatformCatalog.openHands` now advertises `CANCEL_TASK` and `OBSERVE_EVENTS` alongside
  `CREATE_SESSION`/`SUBMIT_TASK`, because all four were exercised against the live server. The
  capability set is no longer a guess.
- The Tool Bar gains a "Web agent platforms" entry that navigates to the new screen. Existing
  entries and their behaviour are unchanged.

### Fixed

- Event and error parsing no longer throws on non-string JSON. The previous `jsonPrimitive` accessor
  raised `IllegalArgumentException` when a state value was an object; the client now reads fields
  defensively and keeps every server event kind intact.

### Notes

- The server's own `agent_settings` is reused verbatim rather than the app inventing a model, so a
  self-hosted deployment keeps whatever model its operator configured.

## [3.10.7] - 2026-11-12

Production-readiness upgrade. Supersedes the local 3.11.0 release-candidate entry below, which was
never published: the target version for this change is 3.10.7 (versionCode 37), so the version
number moves down while the code content moves forward. Nothing is reverted from 3.11.0 — the
capability registry and adapters described there are included here.

### Added

- **Registry adapters** (`core/capability/CapabilityAdapters.kt`). Projects the two real tool
  registries (`AgentToolRegistry`, `UniversalToolRegistry`) into the canonical capability
  vocabulary, deriving ownership from each tool's own risk and credential declarations instead of a
  name table. A connector whose canonical name shadows a reviewed catalog id is reported by
  `collisions()` and is not merged, so a plugin can never override a reviewed definition.
- 9 JVM tests (`CapabilityAdaptersTest`) covering risk→mutating mapping, credential and cloud
  gating, catalog merging, shadow prevention, stable ordering and unique native-action ids.
- **Agent-platform domain and adapter contract** (`core/agentplatform/`). `AgentPlatform`,
  `IntegrationMode`, `PlatformCapability`, `PlatformAuthKind`, `TrustLevel`, `AgentTaskState`,
  `AgentTaskEvent` and `AgentArtifact` model web agent platforms separately from the LLM provider
  catalog. `AgentPlatformAdapter` defaults every operation to `PlatformResult.Unsupported`, so an
  unimplemented method can never be mistaken for a working one, and `AgentPlatformCatalog.guard()`
  refuses an operation a platform did not declare. 8 JVM tests cover the unsupported-by-default
  contract, declared-capability parity, the browser-only-is-not-an-API rule, the guard, id
  uniqueness, the artifacts-require-tasks invariant, and that limitations and a documentation URL
  are always stated.
- Spec documents added under `docs/agents/`: `WEB_AGENT_DISCOVERY_SPEC.md` and
  `PRODUCTION_GAP_ANALYSIS.md`.
- **Atomic cross-process task mutation** (`cloud-backend/redis_task_storage.py`). `mutate()` now
  performs its read/modify/write inside a single Redis `EVAL`, comparing the stored bytes against
  what it read. A losing writer retries a bounded number of times and then raises
  `RedisLeaseConflict` instead of overwriting the winner. This replaces the process-local lock that
  the previous code documented as insufficient for cross-worker exclusivity.
- 4 backend tests covering a concurrent writer winning the race, exhaustion with the winner's state
  intact, `KeyError` on a missing task, and the exact `EVAL` wire arguments.

### Changed

- **Target SDK 36** (was 34); `compileSdk` 36, `versionCode` 37, `versionName` 3.10.7.
- **AGP 9.4.1** (was 8.x) with Gradle wrapper 9.7.0, Kotlin 2.4.0, KSP 2.3.10.
- Dependency upgrades that are genuinely compatible with `compileSdk` 36: `room` 2.8.5,
  `work-runtime` 2.12.0, `datastore` 1.3.0, `activity-compose` 1.13.0.
- **Removed three unused permissions** from `AndroidManifest.xml`: `ACCESS_BACKGROUND_LOCATION`,
  `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`. Each was verified to have zero call sites across
  Kotlin, XML and reflection before removal. `QUERY_ALL_PACKAGES` is retained because it backs the
  in-use `queryIntentActivities` calls.
- README version table corrected to the source values (it still claimed 3.8.2 / versionCode 28 /
  target 34).

### Rejected from the supplied readiness kit (verified, not applied)

- `lifecycle 2.12.0` and `activity-compose 1.14.0` **do not exist** on Google Maven; the highest
  published versions are 2.11.0 and 1.13.0.
- `lifecycle 2.11.0`, `navigation-compose 2.10.x` and Compose `1.12.x` (BOM 2026.08.00+) **require
  `compileSdk` 37**, not 36. Adopting them at `compileSdk` 36 fails `checkDebugAarMetadata` with 14
  errors. This build keeps Compose BOM 2026.06.01 (Compose 1.11.4), `lifecycle` 2.8.7 and
  `navigation` 2.9.8, which are the newest releases that compile at 36.
- The kit's PRoot findings do not apply to this repository: the APK ships no PRoot binary and the
  Kotlin sources contain no PRoot references. The terminal backend is Termux `RUN_COMMAND`.

### Verification (performed locally for this change)

- `:app:testDebugUnitTest` **964 tests, 0 failures, 0 errors, 2 skipped**.
- `cloud-backend` unittest suite **80 tests, 0 failures** (run with `GITHUB_TOKEN` unset; two
  OpenSwarm adapter-readiness tests assert "unconfigured" and fail only when an ambient GitHub token
  is present, which is an environment artifact, not a regression).
- `:app:lintDebug` 0 errors, 0 warnings (1 hint; 9 pre-existing baseline suppressions remain).
- `:app:assembleDebug` 91.2 MB and `:app:assembleRelease -PallowUnsignedRelease=true` 29.1 MB, both
  reporting `versionCode 37`, `versionName 3.10.7`, `targetSdkVersion 36` and ABIs
  `arm64-v8a armeabi-v7a x86 x86_64`. The `armeabi-v7a` libraries in the **release** APK were
  confirmed ELF32 / `Machine: ARM` with `readelf -h`.

### Still unverified

- No physical 32-bit ARM device was available. ARM32 execution, Termux handshake, GitHub OAuth,
  private-clone credentials and ZIP import remain device-unverified. ARM32 support is established
  only at packaging level (32-bit ARM ELF libraries present in the release APK), not on hardware.
- Release signing continuity is unresolved: no keystore is committed, and the release APK produced
  here is **unsigned**. It cannot upgrade an installed build.

## [3.11.0] - 2026-11-19 (superseded, never published)

Unified release candidate. v3.10.6 already contained all of v3.10.4 and v3.10.5 in one history
(`git merge-base --is-ancestor` for both), so there were no competing implementations to merge:
3.11.0 reconciles the two duplicated availability vocabularies into one registry and adds the
verification evidence that 3.10.6 lacked.

### Added

- **Central capability registry** (`core/capability/CapabilityRegistry.kt`). One catalog that names
  the owning module for every capability, the requirement it needs (Termux, credential, cloud
  server, GitHub token, network) and the real navigation route it opens. Availability is derived
  from a passed-in environment, never asserted, so a capability cannot report `READY` while a
  dependency is missing. Includes `fromToolBar()` to map the Tool Bar vocabulary in, and
  `duplicateIds()` to fail the build if two modules ever claim the same id.
- 11 JVM tests (`CapabilityRegistryTest`) covering: unique ids, no false `READY`, Termux/cloud/
  token gating, offline downgrade, label parity with the Tool Bar, and a guard that every Tool Bar
  entry has a registry counterpart.

### Changed

- App version to 3.11.0 (versionCode 37). No feature, navigation target, setting, integration or
  stored user data is removed by this change.
- `release-candidate.yml` version assertions updated to 3.11.0/37 so the signed candidate can be
  verified. The workflow still creates a throwaway signing key and does not publish.

### Verification (performed locally in this change)

- `:app:compileDebugKotlin` BUILD SUCCESSFUL.
- `:app:testDebugUnitTest` 955 tests, 0 failures; `:remote-protocol` 26; `:desktop-agent` 23.
- `:app:lintDebug` 0 errors, 0 warnings (9 pre-existing baseline suppressions remain; they are
  icon-shape and modifier-ordering hints, none security-related).
- `:app:assembleDebug` produced `com.usmanandumar.app` versionCode 37, versionName 3.11.0,
  ABIs `arm64-v8a armeabi-v7a x86 x86_64`. The three `armeabi-v7a` native libraries were confirmed
  ELF32 / `Machine: ARM` with `readelf -h`.

### Still unverified

- No physical 32-bit ARM device was available. ARM32 execution, Termux handshake, GitHub OAuth and
  the private-clone credential path remain device-unverified. ARM32 compatibility is established
  only at the packaging level (32-bit ARM ELF libraries present), not by running on hardware.
- The v3.10.6 CHANGELOG stated the version had never been compiled locally. That is no longer true:
  it compiles and its full test suite passes, as recorded above.

## [3.10.6] - 2026-11-18

The platform screens continue the shared navy · emerald · electric-blue design system with
glass-style surfaces, branded headers, stronger hierarchy and responsive phone layouts. This
release-prep change preserves the existing navigation, provider discovery, GitHub search,
Termux/Linux execution, plugin platform and remote-control actions; it does not add placeholder
capabilities or publish an APK or GitHub Release.

### Changed
- Restored the premium presentation across the Task Bar, Tool Bar, AI Providers, GitHub,
  Termux/Linux, Plugin Platform and Remote Platform surfaces using the existing functional
  destinations and view models.
- Added/repaired real Compose visual-evidence capture for the v3.10.5 baseline on the x86_64
  GitHub Actions runner; baseline evidence remains separate from the v3.10.6 production changes.
- Bumped the app to version 3.10.6 (versionCode 36).

### Verification status
- Local Android compilation is unavailable in the current sandbox because no Android SDK is
  installed; CI verification is required before release approval.
- No GitHub Release or APK distribution is performed by this change.

## [3.10.5] - 2026-11-17

The Task Bar and the Tool Bar are redesigned as two professional 2026 dashboards on the shared
navy · emerald · electric-blue identity introduced in 3.10.4. The Task Bar gains a live summary
strip, a status pill and progress on every task card, and clearer grouping; the Tool Bar is
restructured into three clearly labelled sections — AI providers and web-based agents, GitHub
project management, and Termux and the Linux environment. Both screens keep every existing feature,
navigation target, integration and execution workflow: this is a presentation and information
architecture upgrade, not a rewrite. No capability is claimed that the code does not perform, and
nothing is labelled "connected" until the real integration reports it is. The app version moves to
3.10.5 (versionCode 35).

### Added

- **Task Bar summary model** (`ui/screens/taskbar/TaskBarModel.kt`): a pure, unit-testable view of
  the task list — active, queued, waiting, succeeded and failed counts, the derived state for every
  task, and the exact action set the current state allows. No Compose or Android dependency, so it
  is asserted directly in JVM tests.
- **Tool Bar section model** (`ui/screens/toolbar/ToolBarModel.kt`): the three tool sections and
  their entries as data, each carrying an honest availability status and the real destination it
  opens. The UI renders whatever the model reports, so a section cannot silently claim more than
  the app provides.

### Changed

- **Task Bar redesign** (`ui/screens/WorkspaceTaskScreen.kt`): a brand header, a segmented
  Inbox / Dashboard / Kanban control with live counts, a metric summary strip, and restyled task
  cards that show a status pill, the execution mode and a progress/state line. Every existing
  action (Add to inbox, Run, Resume, Pause, Cancel, Approve & resume, Save to Drive, drag-and-drop
  on the Kanban board) is preserved, and the OpenSwarm roster, cloud-server settings, remote Browser
  Use and auto-run code settings cards remain reachable.
- **Tool Bar redesign** (`ui/screens/CapabilitiesScreen.kt`): the flat list of cards becomes three
  segmented sections, each with a heading, an honest status pill and its cards. The AI section
  routes to the provider/model discovery screen, the GitHub section routes to repository search,
  lookup and clone, and the Termux section routes to the real Termux RUN_COMMAND integration and
  the cloud tool-agent screen. The integration platform screen stays reachable from the header.
- Version bumped to 3.10.5 (versionCode 35).
- **Render and routing coverage** (`src/test/.../ToolBarRenderTest.kt`, `TaskBarRenderTest.kt`):
  the real Tool Bar and Task Bar presentation now compose under Robolectric on the JVM, so CI asserts
  that each section and entry is displayed, that availability pills match the runtime context, and
  that every entry invokes the correct navigation callback — with no device required.
- **Release workflow version** (`.github/workflows/android-release.yml`): the dispatch `tag` input
  default and the built-APK version assertions move from 3.10.4 / versionCode 34 to 3.10.5 /
  versionCode 35 to match the app.

### Not changed

- No PRoot, rootfs or execution logic was touched. No API key, token or credential is stored or
  logged. Android 9 / API 28 and the ARM32 (armeabi-v7a) build are preserved.

## [3.10.4] - 2026-11-12

The Plugin and Remote features are professionally redesigned as two dashboards on a shared navy ·
emerald · electric-blue brand identity, and both are upgraded from static screens into working
platforms: the Plugin screen discovers, connects and revokes real integrations and preflights an
agent against its provider's protocol, and the Remote screen gains file transfer, an authorized
terminal and a gateway view over the existing signed desktop session. The app version moves to
3.10.4 (versionCode 34). No security rule was relaxed: every new action routes through the existing
approval gate, kill switch and persistent grant store.

### Added

- **Shared premium component library** (`ui/components/PlatformComponents.kt`): a brand header,
  dashboard card, status pill, metric tile, segmented tabs, live dot, section heading, empty state
  and brand buttons, built on the existing design tokens so every new screen is consistent and keeps
  the 48 dp minimum touch target.
- **Brand identity** (`ui/theme/Color.kt`): a navy page surface, emerald primary and electric-blue
  accent added to both the light and dark schemes, with contrast chosen so text and marks stay
  legible on each surface. The addition is purely additive; existing color names are unchanged.
- **Built-in integration catalog** (`core/plugin/PluginCatalog.kt`): AI platforms, MCP servers and
  OpenAPI/OIDC services seeded into the registry as DISCOVERED and UNAPPROVED records. The catalog
  makes no network call and declares only the capabilities each protocol actually needs.
- **Plugin platform service** (`core/plugin/PluginPlatformService.kt`): the one place a Plugin-screen
  gesture becomes an enforced change - seed the catalog, connect an entry, import an OpenAPI
  document, discover an MCP server over `tools/list`, approve capabilities with an expiry, and
  revoke. It reuses the existing `PluginRegistry`, `PluginInstaller`, `McpToolDiscovery` and
  `PersistentAuthorizationStore`, so no security rule is re-implemented. A server that fails
  discovery is removed rather than left registered as if it worked.
- **Agent and provider preflight** (`core/plugin/PluginPreflight.kt`): each agent is mapped to the
  protocol it requires (Claude Code → Anthropic Messages, Antigravity → Gemini, DeepSeek Harness →
  OpenAI-compatible), and an incompatible pairing is refused with a specific message before any
  download or task start. The preflight checks the key, the pairing, and whether the agent is
  installed and verified, and reports the first blocker by name.
- **Secret masking** (`core/plugin/PluginPreflight.kt`): a key is shown only as a short prefix and
  suffix, and `redact` scrubs bearer tokens and `api_key`/`token` shapes from any log or error line.
  No key value enters view-model state, a log, or a message.
- **Multi-plugin workflow bridge** (`core/plugin/PluginWorkflowBridge.kt`): every registered
  connector tool is projected into the workspace `ToolRegistry` as `plugin_<tool>`, and a workflow
  step is executed back through `UniversalToolRegistry.execute`, so the kill switch, the argument
  limit, the approval gate and the execution log apply to a workflow exactly as they do to a direct
  tool call.
- **Plugin dashboard** (`ui/screens/PluginPlatformScreen.kt`, `ui/viewmodel/PluginViewModel.kt`):
  four tabs - Discover (AI platforms, MCP servers, OpenAPI services), Manage (grants, disable,
  revoke), Automate (the live plugin tools available to a workflow) and Developer (agent/provider
  wiring with a runnable preflight, and an OpenAPI 3.1 import).
- **Remote dashboard** (`ui/screens/RemoteControlScreen.kt`): five tabs - Overview (pairing, active
  sessions, signed actions), Devices (paired phones and transfer codes), Transfer (browse and
  download files from the paired computer), Terminal (send one authorized command), and Gateway
  (LAN listener, desktop agent, screen view, kill switch). File listing, download and the remote
  terminal are backed by new actions on `RemoteControlViewModel` over the existing signed desktop
  session.

### Changed

- `ui/viewmodel/RemoteControlViewModel.kt` gains the desktop file-list, file-download, remote
  terminal and VPN/gateway-status actions; `forgetDesktops()` now also clears the message state.
- `di/AppModule.kt` provides `UniversalToolRegistry`, `McpToolDiscovery`, `OAuthTokenVault`,
  `PluginInstaller` and `PluginPlatformService` as singletons, so the Plugin screen and the agent
  loop share one registry and one set of security primitives.
- `ui/Navigation.kt` adds the `plugin_platform` route and passes the plugin entry point to both the
  chat composer and the Tools screen; `ui/screens/CapabilitiesScreen.kt` adds the Integration
  Platform card.

## [3.10.2] - 2026-11-12

Encrypted persistence for the unattended-operation groundwork, durable workflow execution, and a
real MCP client plus OpenAPI route execution. The app version moves to 3.10.2 (versionCode 33) and
this work is released as the `v3.10.2` GitHub Release (debug-signed).

Unattended-operation groundwork on the Plugin and Remote features: a persistent, revocable
authorization grant that is enforced at the real execution boundary, automatic reconnection that
survives an app or phone restart and a network drop, and three built-in integrations that are
registered at startup so the workflow engine has real tools before any external plugin exists.
All additions are covered by unit tests (937 total: 888 in `:app`, 26 in `:remote-protocol`, 23 in
`:desktop-agent`) and are not device-verified yet.

### Added

- **Persistent unattended authorization** (`core/plugin/PersistentAuthorizationStore.kt`): a grant
  records its capabilities, an optional expiry, an optional maximum number of uses, and a
  revocation timestamp, and every mutation is persisted before it is reported as done. `isAuthorized`
  returns a decision with a specific reason (not granted, revoked, expired, out of scope, budget
  exhausted) so an unattended run can explain a refusal instead of stopping silently. A grant can
  never widen beyond the capabilities the plugin declares, and `check` answers a status query
  without spending the use budget.
- **The grant is enforced where tools actually run** (`core/plugin/PluginInstaller.kt`):
  `PluginExecutionGate` now refuses a plugin tool that has no live grant, so `revoke` takes effect
  on the very next call with no restart. Seven tests drive the real gate, including
  "revoke stops the very next call" and "an expired grant is refused at the gate". The gate is also
  composed with the interactive approval gate (`core/connector/CloudToolAgent.kt`,
  `PluginExecutionGate.withDelegate`), because the interactive gate allows read-only tools by
  default and a read-only plugin tool would otherwise run with no grant.
- **Automatic reconnection** (`core/remote/ReconnectionCoordinator.kt`): "the owner wants this
  connection" is persisted while "we are connected right now" is not, so a cold start reconnects a
  desired target after an app restart or a phone reboot, and a dropped heartbeat retries on a
  bounded, deterministic backoff instead of giving up. The connect function and the clock are
  injected, so the whole schedule is tested on the JVM: retry-then-succeed, give-up after the
  attempt cap, and "a target the owner disconnected stays disconnected".
- **Reconnection wired into the app** (`core/remote/RemoteReconnectSupervisor.kt`, `di/AppModule.kt`,
  `OpenDroidApp.kt`): the coordinator is bound to the real paired-desktop store, so on every cold
  start the app reconnects the computers the owner had left connected, and a computer the owner
  forgot stops being wanted instead of retrying forever. The stores are file-backed
  (`files/remote/reconnect.json`, `files/plugins/grants.json`) so the desire and the grants survive
  a process death. Five tests drive the supervisor over the real coordinator and a real file store.
- **Built-in integrations** (`core/connector/BuiltInIntegrations.kt`): date/time, text transform,
  and an HTTP status integration, each a real connector that validates its arguments and returns a
  real result. They are registered at startup by `UnifiedAgentLoop`, so the workflow engine has
  tools before any external plugin is installed.
- **Workflow engine output mapping** (`core/workflow/WorkflowEngine.kt`): an input mapping may now
  pass a literal with the `literal:` prefix and substitute an earlier step's output with a
  `{stepId.field}` placeholder, so a step can build a value (such as a URL) from two upstream steps.
  `MultiPluginWorkflowDemoTest` runs one workflow across three real registered integrations in
  sequence and asserts the exact order and that the HTTP integration fetched the URL the text
  integration produced.
- **Encrypted reconnect and grant stores** (`core/remote/ReconnectionCoordinator.kt`,
  `core/plugin/PersistentAuthorizationStore.kt`, `di/AppModule.kt`): the reconnect record and the
  plugin grant are now persisted through the Keystore-backed secret store instead of a plaintext
  JSON file, so a record that cannot be decrypted reads as absent rather than being trusted.
- **Durable execution receipts** (`core/workflow/ExecutionReceiptStore.kt`,
  `core/workflow/WorkflowEngine.kt`, `data/repository/WorkspaceWorkflowRunner.kt`): a step's
  external effect is recorded by a stable receipt key before the tool runs, so an interrupted run
  resumed at application start does not repeat an action that already happened; an explicit owner
  retry clears the receipt for that step only.
- **MCP client and OpenAPI route execution** (`core/connector/McpToolDiscovery.kt`,
  `core/plugin/OpenApiToolRegistrar.kt`): an MCP server's tools are discovered with a JSON-RPC
  `tools/list` call and registered on the existing MCP connector, and an imported OpenAPI
  document's operations are registered so they execute through the generic HTTP connector. Both
  run through the same `UniversalToolRegistry.execute` path, so the kill switch, the approval gate
  and the execution log apply unchanged.

### Fixed

- **A workflow that fails leaves no step stuck PENDING** (`core/workflow/WorkflowEngine.kt`): skip
  propagation is now transitive, so a step downstream of a skipped step is itself marked SKIPPED
  rather than remaining PENDING and tripping the deadlock guard.
- **A cancel from another thread is no longer overwritten** (`core/workflow/WorkflowEngine.kt`): the
  loop re-reads the persisted run after a step completes, so a cancel that landed while the step ran
  is honoured instead of being clobbered by the in-memory copy.
- **Re-arming a failed step gives it a fresh attempt budget** (`core/workflow/WorkflowEngine.kt`):
  `retryStep` resets the step's attempt counter, so a step that already used its retries on the first
  pass can actually be retried on resume.

### Plugin and Remote standards-alignment pass (same release)

Standards-alignment pass on the Plugin and Remote features: a real session lifecycle for remote
control, a documented plugin manifest with interoperability and version compatibility, plugin
health/execution history, and a real desktop companion agent for remote control. All additions are
covered by unit tests (862 total: 813 in `:app`, 26 in `:remote-protocol`, 23 in `:desktop-agent`)
and are not device-verified yet.

### Changed

- **CI runs the new modules' tests** (`.github/workflows/android-ci.yml`): the unit-test job ran only
  `:app:testDebugUnitTest`, so the 49 tests added with `:remote-protocol` and `:desktop-agent` were
  never executed on CI even though the changelog counted them. The job now runs
  `:remote-protocol:test` and `:desktop-agent:test` as well. Both are plain JUnit over JVM modules
  with no AWT `Robot` in the tests, so they run headless.

### Added

- **Desktop companion agent** (`desktop-agent/`, `remote-protocol/`): a real Windows/Linux/macOS
  agent that captures the screen through AWT `Robot`, injects mouse, wheel, key and text input,
  lists and launches installed applications, and transfers files inside a single shared folder.
  Pairing exchanges a one-time code for a shared secret derived with HKDF-SHA256 over both handshake
  nonces; every later connection is proved with an HMAC over a fresh challenge. Input and file
  transfer can each be disabled, and a client can be revoked. Covered by 23 tests over a real
  loopback socket, including a cross-implementation check that the phone and agent derive the same
  secret.
- **Remote desktop client** (`core/remote/RemoteDesktopClient.kt`, `KeystorePairedDesktopStore.kt`):
  the phone side of the agent protocol, with a Keystore-backed store for the pairing secret, and a
  Remote screen section to pair, connect, view the screen, list and start applications, and forget
  paired computers. Covered by 4 integration tests that drive the real client against the real
  agent session handler.
- **Remote session lifecycle** (`core/remote/RemoteSessionManager.kt`): sessions now have explicit
  states (Pairing, Connected, Reconnecting, Disconnected, Expired, Revoked) and explicit end
  reasons. Heartbeat monitoring moves a silent session to Reconnecting, then to Disconnected after
  the reconnect grace window. Inactivity and maximum-duration policies end a session, and every end
  reports why. The pairing code's five-minute expiry never bounds an established session.
- **Plugin manifest and interoperability** (`core/plugin/PluginManifest.kt`): a machine-readable
  manifest with declared kind, interoperability protocols (OpenAPI 3.1, MCP, OAuth 2.0/OIDC,
  REST/JSON, browser, local device), capabilities, dependencies and version compatibility.
  `checkManifest` fails closed on a missing dependency, an app version that is too low, or an
  unsatisfied semver range.
- **Plugin lifecycle** (`core/plugin/PluginRegistry.kt`): install, connect, enable, disable, update
  and remove, plus `noteUpdate`/`applyUpdate` that can never widen the owner's grant, and
  health (UNKNOWN/HEALTHY/DEGRADED/FAILING) derived from a bounded execution history.

### Fixed

- **Remote session no longer ends with the pairing code**: there was no session model at all; the
  transport was one-shot. A completed pairing now opens a Connected session that survives past the
  five-minute pairing window while heartbeats continue.
- **A disabled plugin is refused before any request**: `authorize` now denies a plugin whose
  install state is Disabled or Removed, not only an unapproved or revoked one.
- **The Remote screen explains the two timers**: the pairing-code text now states that the
  five-minute code is not a session limit, and an Active sessions list shows each session's state
  and end reason with an immediate Disconnect.

### Known limitations (not verified on a device)

- The desktop agent is built and unit-tested on this machine, but no real phone-to-desktop session
  has been run: screen capture on a real desktop, input on a real desktop, and the phone UI against
  a real agent all need a device and a computer and are **not verified**.
- Routing a phone's internet through a desktop gateway is **BLOCKED**, not implemented. It needs a
  `VpnService` on the phone, a NAT gateway on the desktop, and the school network administrator's
  permission. See `docs/remote/remote-internet-status.md`.

## [3.10.1] - 2026-11-12

Fixes for the attachment picker, the chat layout, and the remote-control execution path. The
existing security core, approval gate, kill switch, and provider wiring are unchanged; the
additions are covered by unit tests and are not device-verified yet.

### Fixed

- **Universal file picker** (`ui/screens/UniversalFilePickerContract.kt`,
  `ui/screens/ChatScreen.kt`): the attachment sheet passed a MIME hint list that mixed `*/*`
  with specific types. Android applies `EXTRA_MIME_TYPES` as a filter even when the intent
  type is the wildcard, so document providers greyed out APK, BIN, ZIP, IPYNB, source and
  config files. The filter is no longer applied, and the "Photos" entry now opens the same
  unfiltered multi-select picker instead of the single-select `GetContent()` path, which was
  a second source of greyed-out files.
- **Chat layout on a narrow screen** (`ui/screens/ChatScreen.kt`, `ui/Navigation.kt`): the
  top bar held four actions plus a text "Clear" button, which pushed the title off a 240 dp
  wide screen. New chat, the chat list and clear now live in an overflow menu, the title and
  the agent status line are single-line with ellipsis, and the empty-state heading no longer
  uses a fixed 96 dp top padding that overlapped the first message. Bottom navigation shows a
  short label per tab, with the full name kept as the icon's content description.
- **Chat scrolling** (`ui/screens/ChatScreen.kt`): the list auto-scrolled to the newest
  message on every history change, which pulled the view back to the bottom while the user
  was reading older messages. It now follows only when the newest item is already in view.

### Added

- **One shared tool registry** (`core/agent/AgentToolRegistry.kt`): the set of executable
  tool actions used to be a hard-coded `when` inside `ChatViewModel`, so no other caller
  could run a tool and adding one meant editing the view model. The set is now data
  (`AgentTool`), resolved by name and alias through a registry, with the platform side
  supplied by an `AgentToolEnvironment`. The chat agent, the remote-control handler, and any
  future caller execute the same actions with the same names. Behaviour of every existing
  action is unchanged.
- **Remote-control execution** (`core/remote/RemoteRequestHandler.kt`): v3.10.0 shipped the
  security core but nothing executed an authenticated request. The handler is the missing
  link: authenticate, ask the owner, then execute, in that order, using the shared registry.
  A rejection, a denial, a timeout, the kill switch, or an unknown tool each stop before
  execution. `RemoteControlService` remains the only authority on pairing, replay, the clock
  window and the kill switch; the handler does not re-implement any of those checks.
- **Device pairing screen** (`ui/screens/RemoteControlScreen.kt`,
  `ui/viewmodel/RemoteControlViewModel.kt`, `core/remote/RemoteControlCoordinator.kt`): the
  remote-control core was reachable only from its own tests. The new Remote Control screen,
  opened from the chat drawer's "Remote" entry, starts a pairing session, shows the one-time
  code, completes pairing with a code from another device, lists paired devices with an
  unpair action, and engages the kill switch. The coordinator only sequences
  `RemoteControlService`; it holds no key material and performs no cryptography. The screen
  states plainly that it cannot reach a Windows, macOS or Linux computer and that requests
  need a transport the owner provides.
- **Remote-control DI** (`di/AppModule.kt`): the device key store, service, and coordinator
  are provided as singletons. Keys persist through the same Keystore-backed encrypted store
  the other secrets use, so pairing survives a restart and a lost key fails closed.
- **LAN transport** (`core/remote/LanRemoteControlServer.kt`,
  `core/remote/LanRemoteClient.kt`, `core/remote/RemoteRequestCodec.kt`,
  `core/remote/RemoteApprovalGate.kt`, `core/remote/RemoteTransportController.kt`): v3.10.0
  and the pairing slice let a device pair but no request could arrive. The owner can now
  turn on a line-oriented TCP listener (default port 45871, off until switched on) that
  accepts one signed request at a time and answers it. Every connection goes through
  `RemoteControlService.authenticate` (kill switch, pairing, clock window, nonce, signature),
  then the owner's approval gate (deny on timeout), then the shared tool registry; a
  rejection, denial, timeout, unknown tool, or bad line stops before execution. The app can
  also act as the controller: it signs an action and sends it to a paired device's address.
  This is the change to the 3.10.0 statement that the app "never opens an inbound port": it
  now can, but only when the owner explicitly turns the listener on, and only on the owner's
  own network. No public relay is used, and the LAN traffic itself is not encrypted, so the
  screen says to use it only on a trusted network.
- **Key transfer** (`core/remote/RemoteControlService.kt`,
  `core/remote/RemoteControlCoordinator.kt`): the controlling phone needs the pairing key to
  sign. The owner can export a base64 transfer code for a paired device and import it on the
  controller by a channel they choose. The code is never logged and the app never sends it
  over the network.
- **Shared tool environment** (`accessibility/AndroidAgentToolEnvironment.kt`): the Android
  side of the tool registry used to be a private inner class of `ChatViewModel`, so only the
  chat agent could run a tool. It is now an injected `AgentToolEnvironment`, so the chat agent
  and the LAN transport execute the same actions through the same code. Behaviour is
  unchanged; the Android-specific code is now covered by the LAN transport tests through a
  fake environment.

### Tests

- `core/agent/AgentToolRegistryTest` (14), `core/remote/RemoteRequestHandlerTest` (8) and
  `core/remote/RemoteControlCoordinatorTest` (11) are new; `UniversalFilePickerContractTest`
  (2) now asserts arbitrary file types are accepted. The LAN transport adds
  `core/remote/RemoteRequestCodecTest` (6), `core/remote/RemoteApprovalGateTest` (6) and
  `core/remote/LanRemoteControlServerTest` (8), the last driving a real loopback socket
  through a real service, gate, and registry. Full JVM suite: 720 tests, 0 failures, 0
  errors, 0 skipped.

## [3.10.0] - 2026-11-14

Plugin registry, owner-paired remote control, one design system, and durable state. All stages
are unit-tested; nothing here is device-verified yet.

### Added

- **Stage 1 - Plugin registry** (`core/plugin/PluginRegistry.kt`): a plugin is a
  record (id, display name, publisher, homepage, supported languages and regions,
  declared capabilities, risk LOW/HIGH/CRITICAL, owner approval state). The registry
  has no fixed count and behaves identically at 300 entries. Discovery only
  suggests a candidate; the owner must accept and approve it. Effective capabilities
  are the intersection of the owner's grant and the plugin's declaration, so a plugin
  cannot widen its own permissions and an owner cannot over-grant. Unknown,
  unapproved, and revoked ids are refused before any request, as is a call for a
  capability that was not granted.
- **Stage 2 - Remote control** (`core/remote/RemoteControlService.kt`): pairing with
  a one-time code that expires after 5 minutes and is single-use; a per-device key
  behind an injectable encrypted-storage boundary; every action carries the key id,
  device id, timestamp, nonce, and an HmacSHA256 signature over the tool name, exact
  arguments, and risk; replay, expiry, and bad signatures are rejected; the owner's
  approval defaults to Deny after 60 seconds; a kill switch disables remote control
  immediately with no remote re-enable; audit records never carry screen text, typed
  text, or secrets; unpair removes keys on both sides. The app never opens an inbound
  port and never uses a public relay - the owner supplies the transport.
- **Stage 3 - Design and internationalisation**: `ui/theme/DesignTokens.kt` is the
  single source of truth for the 8 dp grid, radii, the 48 dp touch target, and the
  2.0 font scale; `Contrast` implements WCAG 2.2 contrast maths and the tests assert
  AA for primary text in both themes; `ui/feedback/ErrorCatalogue.kt` is one
  (category -> message -> one action) catalogue with technical detail kept separate.
- **Stage 5 - Persistence** (`core/plugin/PluginRegistryPersistence.kt`,
  `core/remote/DeviceKeyStore.kt`, `core/security/SecretBytesStore.kt`): plugin
  approvals and device pairings now survive a restart. Device keys are stored
  through the Keystore-backed encrypted store; a value that cannot be decrypted is
  treated as absent (fail closed). Restoring a plugin re-applies the capability
  intersection, so a shrunk manifest can only narrow a grant, never widen it.

### Tests

- 667 unit tests, 0 failures (baseline 623 before this release).
- New: PluginRegistryTest (8), RemoteControlServiceTest (12), DesignTokensTest (9),
  ErrorCatalogueTest (4), PluginRegistryPersistenceTest (5),
  PersistentDeviceKeyStoreTest (6).

### Not verified

- No real-device or emulator run yet; all Stage 1-5 checks are JVM unit tests only.
- Stage 3 is the design-system and catalogue core: hardcoded English has not yet
  been extracted from every screen into `strings.xml`, and RTL/emulator screenshot
  tests are not yet present.
- Remote control needs the owner to pair a device and supply a private network or
  tunnel before any of it can be exercised end to end.

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
