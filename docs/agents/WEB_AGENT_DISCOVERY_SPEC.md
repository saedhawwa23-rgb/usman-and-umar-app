# Implementation Specification — In-App Web-Based AI Agent Discovery

## Product requirement

Add a first-class **AI Agent Platforms** discovery experience inside USMAN AND UMAR. This is separate from the existing **AI Model Providers** catalogue.

Users must be able to search for agent platforms, inspect what each integration genuinely supports, connect securely, create and monitor tasks, continue sessions, and retrieve results/files without manually copying prompts between apps.

## Architecture boundaries

### Android presentation
- `AgentPlatformsScreen`: search, filters, featured/recent entries, connection state.
- `AgentPlatformDetailScreen`: capabilities, auth requirements, permissions, limitations, connection status.
- `AgentTaskScreen`: prompt/task details, live state, event timeline, logs, artifacts, cancellation and retry.
- ViewModels expose immutable UI state using StateFlow; UI never calls network adapters directly.

### Domain
Define stable domain types:
- `AgentPlatform`
- `AgentPlatformCapability`
- `AgentConnection`
- `AgentSession`
- `AgentTask`
- `AgentTaskEvent`
- `AgentArtifact`
- `AgentAuthState`

Each platform declares its integration mode: `DOCUMENTED_API`, `SDK`, `MCP`, `REMOTE_WORKER`, or `APPROVED_BROWSER_AUTOMATION`. Do not label a plain website link as an integration.

### Adapter contract
Each adapter must support only operations actually provided by its platform:
- `validateConnection()`
- `listCapabilities()`
- `createSession()` where supported
- `submitTask()` where supported
- `getTask()` / `observeTaskEvents()` where supported
- `cancelTask()` where supported
- `listArtifacts()` / `downloadArtifact()` where supported

Unsupported operations return a typed `UnsupportedOperation` result; do not simulate success.

### Backend
Use authenticated, scoped endpoints and persistent task records. Long-running agent jobs must execute in workers, not inside a request lifecycle. Use atomic ownership/lease operations, idempotency keys, retry/backoff, cancellation, event persistence and artifact metadata. Stream updates using WebSockets or SSE with reconnect/resume support.

### Security
- Store tokens in Android Keystore-backed encrypted storage.
- Never place provider or platform secrets in source code, logs, analytics or exported intents.
- Use OAuth PKCE where supported; otherwise use documented token flows.
- Scope tokens to minimum required operations.
- Require explicit approval for shell, filesystem writes, browser actions, remote control and other high-impact operations.
- Treat all external page content, tool output and agent responses as untrusted.
- Maintain auditable task and permission events.

## Initial platform research candidates

Evaluate OpenHands first, then Browser Use and other platforms only where they expose a supportable integration contract. Record repository URL, license, last meaningful maintenance activity, security posture, API/SDK availability, deployment model, dependencies and Android compatibility impact. Do not copy code until license and security review pass.

## Testing
- Adapter contract tests using deterministic mock servers.
- Auth failure, expired token, revoked token and scope tests.
- Submit/poll/stream/reconnect/cancel/retry tests.
- Process-death recovery tests.
- Duplicate submission/idempotency tests.
- Artifact integrity and download tests.
- UI tests for empty/loading/error/connected/running/failed states.
- API 28 and API 36 device/emulator verification.

## Release gate
Do not claim the web-agent feature is complete until at least one real supported platform has passed end-to-end connection, task submission, live monitoring, reconnect, cancellation where supported, and result retrieval.
