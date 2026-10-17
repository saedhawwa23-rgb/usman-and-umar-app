# Testing Plan

## Unit tests

State transitions, dependency resolution, retry/cancellation rules, tool schemas, redaction, archive safety, URL validation, provider parsing, workflow mapping/branching, persistence migrations, and deterministic content fixtures.

## Integration tests

Room/DataStore/WorkManager behavior, provider connection adapters with mock servers, GitHub API error/rate-limit handling, Termux callback parsing, file/artifact workflows, and plugin validation.

## Emulator/CI tests

Lint, unit tests, debug build, unsigned release compile checks, API 28-compatible manifest/resource checks, and instrumented tests where an emulator is available.

## Physical-device tests

Samsung Galaxy M10 or an explicitly named substitute: install/launch, permissions, accessibility enable-state, Termux RUN_COMMAND handshake, storage restrictions, background recovery, ABI behavior, and embedded Linux diagnostic command. Until run, label results **UNVERIFIED — REQUIRES DEVICE**.

## Release evidence

For every artifact record repository URL, branch, commit, Actions run, actual tests, APK size, SHA-256, application ID/version, ABI, signing status, and physical-device status.
