# Implementation Roadmap

This roadmap implements the complete product contract incrementally; stages are sequencing, not scope reduction.

1. **Foundation and audit:** new repository, identity, baseline build, feature inventory, security/testing plans.
2. **App foundation:** package/namespace migration, navigation shell, shared UI, Room/DataStore migrations, project/workspace model.
3. **Tool Registry:** stable contracts, schemas, availability states, permissions, approvals, structured results, execution history.
4. **Task Manager:** persistent state machine, dependency graph, queueing, retries, cancellation, restart recovery, artifacts.
5. **AI Provider Manager:** provider/endpoints/model catalog, secure credentials, connection tests, optional safe tool calling.
6. **Core local tools:** files/data, checksums, archives, HTTP, diagnostics.
7. **GitHub and project development:** authenticated operations, branch/PR workflows, rate limits, approvals, artifact verification.
8. **Termux/Linux:** distinct status and diagnostics; embedded runtime only after integrity and command verification.
9. **Phone/app automation:** intents first, optional accessibility service with explicit privacy/approval controls.
10. **Research and intelligence:** web retrieval, readability, GitHub starred research, content analysis, translation adapters.
11. **Plugin Center:** trusted plugins, provenance, lifecycle, compatibility, registry integration.
12. **Automation Studio:** deterministic workflows, branches, mappings, schedules/events where Android permits, gates, recovery.
13. **Integration/release:** regression, API 28/ARM32 compatibility, CI artifacts, release evidence, device test reporting.

Every stage must distinguish implemented, tested, device-unverified, blocked, and planned work. No feature is marked ready from UI appearance alone.
