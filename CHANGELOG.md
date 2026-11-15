# Changelog

All notable changes to USMAN AND UMAR are recorded here.

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
