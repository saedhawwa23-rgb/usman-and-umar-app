# AGENTS.md

Guidance for Claude Code and other agents working in this repository.

## Agent skills

### Issue tracker

GitHub Issues on this fork, `JMAN730/opendroid`, via the `gh` CLI. Never file or read
issues on the `yashab-cyber/opendroid` upstream. External PRs are not a triage surface.
See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical defaults — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`,
`wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the root, ADRs in `docs/adr/`. See `docs/agents/domain.md`.

## Workspace facts (verified)

Two separate apps live side by side in `/workspace/project`. Do not confuse them.

- **This repo (`uu-app`)** — `saedhawwa23-rgb/usman-and-umar-app`, package
  `com.usmanandumar.app`, branch `feature/hybrid-task-orchestrator`, version 3.10.4
  (versionCode 34) on the working tree and commit `b87b561`. Plugin and Remote are
  both redesigned as working dashboards (`core/plugin/*`, `ui/screens/PluginPlatformScreen.kt`,
  `ui/screens/RemoteControlScreen.kt`). Last published release: `v3.10.2`
  (`USMAN-AND-UMAR-v3.10.2.apk`, debug-signed, ABIs arm64-v8a, armeabi-v7a, x86, x86_64).
  3.10.4 is committed locally but not pushed (see the push note below).
- **`../umar-work` (Mobile-Harness)** — package `com.jarves.mh`, version 1.0.15, remote
  `saedhawwa23-rgb/UMAR-and-Usman-ASHEBIR.git`. This is the app with the PRoot / DeepSeek
  Harness / Claude Code / Antigravity behaviors. It is a separate workstream; never copy
  commits or APKs between the two.

Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/tmp/tools/sdk \
ANDROID_SDK_ROOT=/tmp/tools/sdk ./gradlew :app:assembleDebug` (and `:app:testDebugUnitTest`).
Build tools 36.0.0. Unit suite: 956 tests (907 `:app`, 26 `:remote-protocol`, 23
`:desktop-agent`), all green. Requirement 5 is implemented: `McpToolDiscovery`
(`tools/list` discovery -> `McpConnector` execution) and `OpenApiToolRegistrar`
(imported operations -> generic `HttpConnector` execution).

Push requires a user PAT with `repo`+`workflow`. The ambient `GITHUB_TOKEN` is a
GitHub App installation token whose writes return "Resource not accessible by
integration", so it cannot push. When a user PAT is supplied, push it transiently
via `git -c http.extraheader="Authorization: Basic $(printf 'x-access-token:%s' "$TOK" | base64 -w0)"`
so the token never lands in `.git/config`.

The `AGENTS.md` content above this section (issue tracker `JMAN730/opendroid`) is inherited
from an upstream fork and does not describe this repository.
