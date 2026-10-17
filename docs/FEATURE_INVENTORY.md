# USMAN AND UMAR Workspace — Reference Feature Inventory

Audit basis: `umarashebir-cpu/usman-and-umar` `main` at commit `f10f56c` / tag `v3.0.1`, inspected without modifying the source repository.

Status vocabulary: **verified in source/tests**, **implemented but device-unverified**, **partial**, **broken/risk**, and **missing**. A passing JVM build does not prove physical-device behavior.

| Area | Current evidence | Status | Destination plan |
|---|---|---|---|
| App identity and Android baseline | `applicationId com.usmanandumar.app`, min API 28, target API 34 | verified in source; device-unverified | Preserve; migrate namespace/package identity in a controlled change |
| Chat and cloud AI | Provider factory, OpenRouter/OpenAI-compatible flows, tool-call transcript tests, credential store | implemented; provider/device-unverified | Preserve and move behind `AiProviderManager` |
| Plan | Plan manager, plan screen, validation and approval concepts | implemented; integration-unverified | Preserve and connect to persistent Task Manager |
| Memory | Room entities/repository, memory UI, knowledge graph and secure memory paths | implemented; migration/device-unverified | Preserve; separate ordinary memory from secrets |
| Social | adapters, campaigns, inbox, analytics, comments, settings | partial; external integrations unverified | Audit permissions and retain only verified adapters |
| Tools/actions | broad action set, connector layer, risk policy, action executor | implemented in source; device-unverified | Consolidate into the shared Tool Registry |
| Macros/routines | macro entities, recording/actions, routine engine | implemented; integration-unverified | Convert safe macros into validated workflows |
| Logs/crash diagnostics | crash recorder, redaction tests, logs UI | verified in JVM tests; device-unverified | Preserve and add task/workflow execution records |
| Settings/providers | provider selection, model catalog, protected credentials | implemented; provider-unverified | Extend endpoint/model search and status reporting |
| GitHub | public discovery, metadata/README/root inspection, Termux clone path | implemented; device-unverified | Add authenticated project operations with explicit approvals |
| Termux | RUN_COMMAND integration, result parsing, clone validation tests | implemented in source/tests; physical handshake unverified | Preserve; expose separate status from embedded Linux |
| Accessibility | manifest/config/service and action dispatch | implemented in source; physical behavior unverified | Preserve with enable-state UI and privacy boundaries |
| Embedded Linux | open PR #4 and historical rootfs risks | unverified / risky | Audit independently; do not claim readiness until diagnostic command succeeds |
| Web research/readability | not present as a complete integrated subsystem | missing/partial | Build deterministic retrieval/extraction pipeline |
| Files/data | scattered actions and storage provider support | partial | Build safe file/data tool family and artifact model |
| Plugin Center | no reviewed lifecycle/security model found | missing | Start with trusted built-in plugins |
| Task Manager | task actions exist but no unified persistent scheduler/state machine | partial/missing | Build central persistent task subsystem |
| Automation Studio | macros/actions exist; deterministic workflow engine missing | partial/missing | Build workflow definitions, gates, retries, mapping, recovery |
| CI/release | Android CI and release workflows exist | implemented; artifact/device status must be reported honestly | Rework for new repository and verified artifacts |

## Explicit non-claims

- The reference app has not been physically verified on Samsung Galaxy M10 from this audit.
- Open PR #4 was not merged, modified, or used as evidence that embedded Linux works.
- Provider model listings do not prove credentials, completion, or tool calling.
- A successful Gradle build does not prove Android permissions, accessibility, Termux, or manufacturer behavior.
