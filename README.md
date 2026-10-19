# USMAN AND UMAR

USMAN AND UMAR is an Android automation assistant built on the OpenDroid codebase. This branch moves reasoning to cloud LLM providers, with OpenRouter as the default, while keeping device actions explicit and separately permissioned.

## Runtime architecture

- **Cloud reasoning:** OpenRouter is selected for new installations. Other configured providers are available from Settings. Provider credentials are stored through the app's protected credential store; requests require a configured provider and, where applicable, an API key.
- **Tool execution:** The cloud model can request registered tools through the OpenAI-compatible tool-calling protocol. The app validates and dispatches each tool locally; Android actions remain subject to their existing grants and approval rules.
- **MCP safety:** The loopback MCP interface exposes only deterministic local calculation actions; privileged shell, persistent terminal, endpoint credential configuration, and side-effecting app actions are not available through a path without per-request in-app approval.
- **Accessibility privacy:** Password-like accessibility nodes and descendants are omitted from screen text. Screenshot capture is refused when the active window exposes a password field. Android backups and device transfers are disabled for private app data.
- **GitHub discovery:** Public repository search and inspection use GitHub's REST API. The app can save a repository as supported project context, make it active, inspect metadata/README/root entries, and start a real `git clone` through the Termux `RUN_COMMAND` API.
- **Termux commands:** Commands execute in the installed Termux environment, not in a simulated shell. Termux must allow external app commands, and the app must have Termux's `RUN_COMMAND` permission. Command output and exit status are returned through the callback contract.
- **No bundled local inference:** The app no longer packages a GGUF model, llama.cpp module, or LiteRT local inference engine. Termux remains an optional user-authorized execution backend; it is not an AI reasoning engine.

GitHub and Termux capabilities require connectivity or an installed/configured Termux app as appropriate. GitHub public endpoints can be rate-limited. Tool availability and successful model tool-calling vary by provider/model.

## Android and build requirements

| Setting | Value |
|---|---|
| Minimum Android SDK | 28 (Android 9) |
| Target Android SDK | 34 (Android 14) |
| Current app version | `3.0.0-cloud-ai` (versionCode 23) |
| Packaging | Android application; no model asset or inference JNI module |

Build with JDK 21 and the Android SDK installed:

```bash
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease -PallowUnsignedRelease=true
```

The opt-in unsigned release is for validation only and is not installable as a distributable signed release. A distributable release requires a release keystore and passwords supplied through private Gradle properties or environment variables. Never commit signing material.

## Device setup

After installation, configure an LLM provider and its API key in Settings. To use command execution or repository cloning, install Termux, allow external app commands in Termux, and grant the app the Termux `RUN_COMMAND` permission. Android permission prompts and high-impact plan approvals remain in force.

## Verification status

Build and unit-test results are recorded in the pull request. Physical-device execution of the full GitHub → Termux clone → file inspection workflow is **UNVERIFIED — REQUIRES DEVICE** until exercised on a supported Android device with Termux installed and authorized.

## Project provenance and license

The Android foundation is based on the upstream [OpenDroid project](https://github.com/yashab-cyber/opendroid). Apache License 2.0 notices are retained in [LICENSE](LICENSE). Project notes are in [`docs/`](docs/).

## Workspace evolution

This repository is the independent destination for the USMAN AND UMAR integrated Android workspace. It was seeded from an audited snapshot of the reference application without carrying over Git history. The audit and full-product implementation sequence are documented in [`docs/REFERENCE_AUDIT.md`](docs/REFERENCE_AUDIT.md), [`docs/FEATURE_INVENTORY.md`](docs/FEATURE_INVENTORY.md), and [`docs/IMPLEMENTATION_ROADMAP.md`](docs/IMPLEMENTATION_ROADMAP.md).

The current baseline preserves the reference app's verified-in-source capabilities while the shared Tool Registry, persistent Task Manager, deterministic Workflow Engine, research pipeline, plugin lifecycle, and additional specialized tools are being integrated incrementally. Physical-device behavior, Termux handshake, accessibility behavior, and embedded Linux remain unverified until exercised on the intended device.
