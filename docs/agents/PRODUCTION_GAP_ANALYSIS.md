# Production Gap Analysis — Source Baseline 0562962

**Repository:** `saedhawwa23-rgb/usman-and-umar-app`  
**Branch:** `feature/hybrid-task-orchestrator`  
**Reviewed head:** `0562962a971c57db1c49d10734a463ca37342b62`  
**Package:** `com.usmanandumar.app`

This document records source-backed findings. It is not a claim that the listed improvements have been implemented or that the app is production-ready.

## Confirmed findings

### 1. AI provider discovery is not web-agent discovery

`app/src/main/java/com/opendroid/ai/ui/viewmodel/DiscoveryViewModel.kt`, `searchAI(query)`, searches configured provider names and model catalogs using `ProviderCatalog` and `ModelFetcher`. It does not implement an indexed catalogue of web-based AI coding agents, nor does this method create an external agent session, submit an agent job, stream its events, or retrieve its artifacts.

**Required design:** keep model-provider discovery separate from agent-platform discovery. Add an `AgentPlatform` domain model and repository with explicit capability metadata, connection type, authentication method, supported operations, session/task lifecycle, and trust classification. Support only documented APIs, SDKs, MCP servers, or explicitly approved browser automation. A website URL alone must never be represented as an integrated agent.

### 2. Redis task mutation is not distributed atomic coordination

`cloud-backend/redis_task_storage.py` explicitly states that its GET/modify/SET mutation lock is process-local and cannot provide cross-process compare-and-swap or fencing.

**Required design:** replace lease acquisition/renewal/release with atomic Redis Lua scripts or Redis transactions using compare-and-set semantics, unique lease tokens, monotonically increasing fencing tokens, expiry, and ownership validation. Add multi-process concurrency tests. For longer workflows, use a durable queue/worker architecture with retry policy, idempotency keys, dead-letter handling, cancellation, and recovery.

### 3. Documentation does not match source

The checked-in README describes version 3.8.2 while `app/build.gradle` declares version 3.10.6/versionCode 36, compileSdk 36, minSdk 28 and targetSdk 34.

**Required action:** replace stale claims with source-matched facts and explicitly distinguish implemented, partially implemented, unverified and planned capabilities.

### 4. Android permissions require a least-privilege audit

`app/src/main/AndroidManifest.xml` declares permissions spanning SMS, contacts, calendar, phone, location, overlays, accessibility, notification access, package visibility, settings modification, Bluetooth, microphone, camera, and background services.

**Required action:** map each permission to concrete user-facing functionality and call sites. Remove unused permissions. For sensitive capabilities, document purpose, user initiation, runtime consent, revocation, data handling and platform-policy constraints. Do not remove a permission until dependent features and tests have been identified.

### 5. Release signing continuity is a release blocker until proven

A future upgrade must use the same signing identity as the installed release if Android is expected to accept it as an in-place update. Verify possession of the original private key and certificate fingerprint before preparing an upgrade. If the private key is unavailable, stop and document the migration options; never silently generate a replacement.

## Priority implementation sequence

1. **P0 — Preserve release continuity:** verify release keystore ownership, certificate fingerprint, secret handling and repeatable signing.
2. **P0 — Backend correctness:** implement atomic distributed leases/fencing and multi-worker concurrency tests.
3. **P1 — Web-agent platform catalogue:** distinct search/index for OpenHands and other supported agent platforms; documented adapters; persistent sessions; task submission; event streaming; result/artifact retrieval; reconnect and cancellation.
4. **P1 — Unified capability contract:** canonical capability IDs, schemas, risk levels, permission policy, execution adapters, provenance and audit events. Keep one execution owner per capability.
5. **P1 — Remote execution security:** worker identity, short-lived scoped credentials, device pairing, revocation, replay protection and explicit approval for high-impact actions.
6. **P2 — UI/UX:** redesign every module around shared design tokens, navigation patterns, accessibility, loading/error/empty states and functional controls. Capture screenshots for every major route in light and dark themes.
7. **P2 — Compatibility:** test API 28 and API 36, ARM32 and ARM64, low-memory operation and the actual target handset.

## Acceptance criteria for web-based AI integration

A platform may be labelled **INTEGRATED** only when all applicable items pass:

- Search results are platform/agent entries, not merely LLM model IDs.
- The platform's connection type and limitations are visible.
- Authentication is secure and never stored in plaintext.
- A user can create a persistent task/session through a documented interface.
- The app displays queued/running/waiting/complete/failed/cancelled states.
- Execution events and logs update without manual refresh.
- Results and generated files can be retrieved and opened.
- Reconnection after app process death works.
- Cancellation and timeout behavior are tested.
- External content is treated as untrusted input.
- UI clearly distinguishes API integration, MCP integration, remote worker integration and browser-only automation.

## Verification labels

- **VERIFIED:** reproducible test or device evidence exists for the stated behavior.
- **PARTIAL:** some paths work, but one or more acceptance criteria remain untested or unsupported.
- **UNVERIFIED:** source exists but runtime behavior has not been demonstrated.
- **MISSING:** no implementation was found.
- **REQUIRES IMPROVEMENT:** implementation exists but has a known correctness, security, usability or scalability defect.

Do not mark a capability VERIFIED based only on class names, comments, screenshots, compilation or a successful health endpoint.
