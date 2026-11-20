# USMAN AND UMAR 3.11.0 — Unified Integration & Audit Report

Branch `feature/hybrid-task-orchestrator`. Commit `299d1e3` (local; see *Delivery status*).
Audit date 2026-11-19. Every claim below cites the file, command, or build output that supports it.

Status vocabulary: **verified** (proved by a command I ran), **implemented** (code exists, tests
pass, device behaviour untested), **partial**, **missing**, **needs improvement**. A passing JVM
build does not prove device behaviour.

---

## 1. Version integration report

The premise of the task was that v3.10.4 and v3.10.6 were separate lines needing reconciliation.
That is not what the repository contains.

| Check | Command | Result |
|---|---|---|
| Is v3.10.4 contained in v3.10.6? | `git merge-base --is-ancestor v3.10.4 v3.10.6` | **YES** |
| Is v3.10.5 (`21e5076`) contained in v3.10.6? | `git merge-base --is-ancestor 21e5076 v3.10.6` | **YES** |
| Commits on v3.10.4 missing from HEAD | `git log --oneline v3.10.4 ^HEAD` | **empty** |
| v3.10.6 tag == branch tip | `git rev-parse HEAD v3.10.6` | both `0562962` |

So v3.10.6 was a single linear history that already contained v3.10.4 and v3.10.5. There was no
competing implementation to merge and **no feature was at risk of being lost**. The
`feature/hybrid-task-orchestrator` branch had also been force-updated on the remote to `0562962`;
the local clone was fast-forwarded to match.

Version ladder confirmed from `app/build.gradle` in each tree: v3.10.4 = code 34, v3.10.5 = code
35, v3.10.6 = code 36.

### Correction to a claim made by v3.10.6

The v3.10.6 CHANGELOG said *"Local Android compilation is unavailable in the current sandbox
because no Android SDK is installed; CI verification is required."* I installed JDK 21 and
Android SDK 36 in this sandbox and compiled it. It builds and its whole test suite passes — see
§5. That caveat is now closed.

---

## 2. Complete feature inventory

Evidence: 955 `@Test` methods across `app/src/test`, 26 in `:remote-protocol`, 23 in
`:desktop-agent`. Test-file counts per module come from `find app/src/test -path "*core/<module>*"`.

| Module | Source | Tests | Status | Notes |
|---|---|---|---|---|
| AI providers & models | `core/llm` (27 files) | 12 files | **implemented** | Provider factory, credential store, tool-call transcripts. Live provider calls device-unverified. |
| Agents & tool registry | `core/agent` (21 files) | 12 files | **implemented** | `AgentToolRegistry` + `AndroidAgentToolEnvironment`. |
| Plugins / MCP / OpenAPI | `core/plugin` (11 files) | 10 files | **implemented** | `PluginRegistry`, persistence, `PluginWorkflowBridge`. |
| Remote control | `core/remote` (14 files) | 13 files | **implemented** | Paired-device keystore, `:remote-protocol` module (26 tests). |
| Task Manager | `core/task` + `ui/screens/taskbar` | `TaskStateMachine`, `TaskQuickAddParser` tests | **implemented** | Persistent state machine; not a scheduler. |
| Tool Bar | `ui/screens/toolbar` | `ToolBarModelTest` | **verified** (model) | Sections/entries as data with resolved availability. |
| Terminal / Linux | `core/termux` (1 file) + `TermuxScreen` | 1 file | **partial** | Termux RUN_COMMAND integration. Physical handshake unverified. |
| Browser automation | `core/browser` (5 files) | 4 files | **partial** | Needs a configured cloud server; no on-device engine. |
| Files & storage | `core/storage`, `data/repository` | — | **partial** | Storage-provider support, no unified artifact model. |
| GitHub | `ui/screens/GitHub*`, discovery flow | covered via UI tests | **partial** | Public search/inspect implemented; **private clone and OAuth are broken** (device report). |
| Cloud storage | — | — | **missing** | No provider-backed cloud storage module found. |
| Memory | `ui/screens/Memory*` + Room | — | **implemented** | Room entities/repository. Migration device-unverified. |
| OpenSwarm | `core/connector` (14 files) + `backend-ci.yml` | connector tests | **implemented** | Separate backend CI job is green. |
| Security | `core/security` (9 files) | 3 files | **verified** (unit) | Keystore AEAD cipher, secret-bytes store, redaction tests. |
| Monitoring / crash | `core/crash` (9 files) | crash recorder tests | **verified** (unit) | Redaction covered by tests. |

### Capability ownership after this change

`core/capability/CapabilityRegistry.kt` now names exactly one owning module for every capability
and records the real navigation route. `CapabilityRegistry.duplicateIds()` returns empty and is
asserted in `CapabilityRegistryTest`.

---

## 3. No-duplicate-functionality findings

This was the one genuine architectural defect found.

**Two competing availability vocabularies existed:**

1. `com.opendroid.ai.ui.screens.toolbar.ToolAvailability` — `READY, READ_ONLY, NEEDS_TERMUX,
   NEEDS_SETUP` (`ui/screens/toolbar/ToolBarModel.kt:39`), consumed by `ToolBarModel` and
   `CapabilitiesScreen.kt:190`.
2. `com.usmanandumar.workspace.core.tool.ToolAvailability` — `AVAILABLE, NEEDS_PERMISSION,
   NEEDS_CONFIGURATION, DEPENDENCY_MISSING, UNAVAILABLE, RUNNING, FAILED`
   (`com/usmanandumar/workspace/core/tool/ToolContracts.kt:6`), consumed by the workflow engine.

Two enums describing "can this run" with no mapping between them. There was also **no central
capability registry** — `grep -rn "CapabilityRegistry" app/src/main` returned nothing before this
change.

**Fix:** `CapabilityRegistry` is now the single catalog. `fromToolBar()` maps the UI vocabulary in
with an exhaustive `when`, so adding a `ToolAvailability` case becomes a compile error rather than
a silent fallthrough. The parity test
`every tool bar entry has a registry counterpart` immediately caught three real mismatches
(`ai.integrations`, `github.projects`, `linux.ai-assisted`) — the registry now adopts the Tool Bar
ids as the single source of truth.

**Not changed:** the two package trees (`com.opendroid.ai` and `com.usmanandumar.workspace`) still
coexist. Collapsing them is a rename across the whole app and is out of scope for a release
candidate; it is recorded as a follow-up, not silently half-done.

---

## 4. GitHub technology research register

Research is limited to what the dependency graph already proves, so this register states
compatibility facts rather than proposing unvetted replacements.

| Capability gap | Candidate class | Licence consideration | API 28 | ARM32 | Decision |
|---|---|---|---|---|---|
| HTML readability / extraction | Jsoup-class pure-Java DOM parser | MIT/BSD (permissive) | OK — pure Java | OK — no native code | **Recommended**, not added: adds a dependency for a capability currently marked partial. |
| Browser automation on-device | WebView + JS injection | Android platform | OK | OK | Preferred over embedding Chromium: no new native ABI surface. |
| Cloud storage providers | OkHttp + provider REST | per provider | OK | OK | Existing OkHttp 5.4.0 covers transport; no new library needed. |
| Markdown rendering | Compose-native renderer | Apache-2.0 | OK | OK | Not added; current text rendering is adequate. |

Existing stack that already satisfies the hard constraints (`app/build.gradle:226-286`):
Compose BOM 2026.06.01, Room 2.8.4, Hilt 2.60.1, OkHttp 5.4.0, Retrofit 3.0.0, kotlinx-serialization
1.8.1, Shizuku 13.1.5, BouncyCastle 1.86, pdfbox-android 2.0.27.0.

**ARM32 relevance:** none of the app's own native code is ABI-restricted. The only `.so` files in
the APK come from three AndroidX libraries, all of which ship `armeabi-v7a` builds (verified in §6).
No new native dependency is proposed, so ARM32 support cannot regress.

---

## 5. Verification evidence

All commands run in this sandbox on 2026-11-19 with JDK 21.0.13 and Android SDK 36.

| Check | Command | Result |
|---|---|---|
| Compile | `./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL** (1m 35s) |
| Unit tests | `./gradlew :app:testDebugUnitTest` | **955 tests, 0 failures, 0 errors, 2 skipped** |
| Protocol module | `./gradlew :remote-protocol:test` | **26 tests, 0 failures** |
| Desktop agent | `./gradlew :desktop-agent:test` | **23 tests, 0 failures** |
| Lint | `./gradlew :app:lintDebug` | **0 errors, 0 warnings, 1 hint** |
| Assemble | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL** |
| Full chain | all of the above in one run | **BUILD SUCCESSFUL in 4m 16s** |

Lint detail: the report line is `0 errors, 0 warnings, 1 hint (and 9 errors filtered by baseline
lint-baseline.xml)`. Those 9 suppressions are **not** security issues — they are
`IconLauncherShape` (×5), `IconLocation`, `NonObservableLocale`, `AppCompatCustomView`, and
`ModifierParameter`. They are pre-existing and were not added by this change.

CI on the v3.10.6 base (`0562962`): Android CI, OpenSwarm backend CI and the signed release-candidate
verification all completed **success** (runs `44526775521`, `44526775652`, `44526765588`).

### Security checks

| Check | Result |
|---|---|
| Keystores / signing files tracked? | **None.** `git ls-files` matches only source files whose names contain "keystore". |
| `local.properties` tracked? | **No** (`git ls-files --error-unmatch` fails). |
| `.gitignore` covers secrets? | **Yes** — `local.properties`, `*.keystore`, `*.jks`, `cloud-backend/.env*` (`.gitignore:4,16,24,25,28-30`). |
| Token left in git remote? | **No** — remote URL restored to the plain HTTPS form; `.git/config` has 0 token matches. |
| Release workflow publishes without approval? | **No** — `release-candidate.yml` has no `gh release`/`action-gh-release`/`contents: write`; it signs with an ephemeral key and stops. `android-release.yml` is `workflow_dispatch` only. |

---

## 6. ARM32 / ABI validation

`aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk`:

```
package: name='com.usmanandumar.app' versionCode='37' versionName='3.11.0'
minSdkVersion:'28'  targetSdkVersion:'34'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

The APK contains **both** `armeabi-v7a` and `arm64-v8a`. The three `armeabi-v7a` libraries were
extracted and checked with `readelf -h`:

```
libandroidx.graphics.path.so      Class: ELF32   Machine: ARM
libdatastore_shared_counter.so    Class: ELF32   Machine: ARM
libopenjpeg.so                    Class: ELF32   Machine: ARM
```

`ELF32` + `Machine: ARM` is a genuine 32-bit ARM EABI binary, so a 32-bit device will load these
rather than being handed an arm64 library.

**Limit of this evidence:** this proves the *packaging* is ARM32-correct. It does **not** prove the
app runs on a 32-bit device. No physical 32-bit device was available (see §9).

---

## 7. UI/UX evidence — real emulator renders

v3.10.6 added a real screenshot harness
(`app/src/androidTest/.../VisualEvidenceInstrumentationTest.kt`) that launches `MainActivity` per
route and captures the actual window with `UiDevice.takeScreenshot` — not Robolectric bitmap
capture. Workflow `compose-visual-evidence.yml` ran it on an x86_64 emulator.

Artifact `usman-and-umar-v3.10.6-emulator-visual-evidence-22` (755,768 B) from run `44523110643`
contains 14 PNGs — 7 screens × before/after:

```
before/v3.10.5-*.png   after/v3.10.6-*.png
task-bar, tool-bar, ai-providers, github, termux-linux, plugin-platform, remote-platform
```

Verified as genuine renders, not placeholders:

- Dimensions `320x640`, distinct-colour counts **227–757** per image (a blank or error screen would
  collapse to a handful of colours).
- Average luminance 15–42 (dark navy surfaces), consistent with the brand theme.
- **All 14 files have unique MD5 hashes**, and every before/after pair differs — so the redesign
  demonstrably changed each screen.
- The after-set is consistently richer than the before-set (e.g. `tool-bar` 757 vs 495 distinct
  colours, `termux-linux` 719 vs 227).

Component-level upgrades confirmed in source
(`ui/components/PlatformComponents.kt`): `PlatformCard` now uses an 8 dp shadow, a three-stop
gradient border (`electricBlue → border → emerald`), a gradient body, and a rounded icon chip;
`BrandedBackHeader` was added with a navy→electric-blue gradient.

**Not verified:** I cannot judge whether these renders match a specific design reference. I have no
image input, so no visual comparison to any external screenshot is claimed.

---

## 8. Changed files

| File | Reason |
|---|---|
| `app/src/main/java/com/opendroid/ai/core/capability/CapabilityRegistry.kt` | **New.** Single capability catalog: one owning module per capability, requirement gating, real navigation routes, `fromToolBar()` bridge, `duplicateIds()` guard. Closes the duplication found in §3. |
| `app/src/test/java/com/opendroid/ai/core/capability/CapabilityRegistryTest.kt` | **New.** 11 tests incl. no-false-READY, Termux/cloud/token gating, offline downgrade, label parity, Tool Bar parity. |
| `app/build.gradle` | Version 3.10.6/36 → **3.11.0/37**. |
| `CHANGELOG.md` | 3.11.0 entry with the verification results and an explicit "still unverified" section. |
| `.github/workflows/release-candidate.yml` | Version assertions updated to 3.11.0/37 so the signed candidate can verify. Still non-publishing. |

Nothing else was touched. In particular no PRoot, Termux, provider, GitHub or navigation logic was
modified, and the UI style was not altered.

---

## 9. What could not be verified (needs a physical 32-bit device)

Everything here is **unverified**. None of it is claimed as working.

1. **ARM32 execution.** The APK carries correct 32-bit ARM libraries, but no 32-bit hardware or
   emulator was available to run them.
2. **Termux handshake.** `RUN_COMMAND` permission grant and result parsing need the Termux app on
   a real device.
3. **GitHub OAuth sign-in and private-repo clone.** The device report describes a PRoot failure and
   `could not read Username for 'https://github.com'`. I did not modify that code path, so the
   reported behaviour is expected to persist.
4. **Live AI provider calls.** No credentials were used. The DeepSeek, NVIDIA NIM and Hugging Face
   responses in the device report are untouched.
5. **Agent pairing correctness** (e.g. Claude Code on an OpenAI-compatible endpoint) — a reported
   defect I did not address in this change.
6. **Accessibility service behaviour**, Shizuku privileged operations, and Room migrations on
   upgrade from an installed 3.10.x build.
7. **The v3.10.6 emulator screenshots** prove rendering on **x86_64 API 36**, not on ARM32.

### Delivery status — action required

The commit exists locally as **`299d1e3`** on `feature/hybrid-task-orchestrator`, but **it is not
pushed**. Both credentials available to this session are read-only:

- `GITHUB_TOKEN` authenticates as `saedhawwa23-rgb` and the API reports `admin: true` on the repo,
  yet `git push` returns **403** and the refs API returns
  `403 Resource not accessible by integration` — it is a read-only installation token.
- `OH_GIT_CREDENTIAL_ACCESS_TOKEN_GITHUB` returns `Bad credentials`.

Consequently there is **no CI run and no downloadable artifact** for 3.11.0 yet. The locally built
APK is `app/build/outputs/apk/debug/app-debug.apk`:

- 94,172,098 bytes (89.8 MB), **debug** build type, **debug-signed**
  (`CN=Android Debug`, SHA-256 `26104609…07072`)
- `com.usmanandumar.app`, versionCode 37, versionName 3.11.0
- SHA-256 `cc67550bc4b8389dc3bd05086dae27b96032e29ab1d455836721ccb777cdceab`
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

Release signing secrets are not present in the repository, so per the build instruction the
produced artifact is **debug**. It is **not installable over an existing release-signed install**
(signature mismatch) — uninstall first.

To finish delivery, a credential with `contents: write` on
`saedhawwa23-rgb/usman-and-umar-app` is needed; then
`git push origin feature/hybrid-task-orchestrator` publishes `299d1e3`, CI runs, and the
`android-ci` artifact becomes downloadable.

---

## 10. Release candidate

**3.11.0 (versionCode 37)** is prepared and locally verified. Per the constraint *"do not publish
without explicit approval"* it is **not** merged into `main`, **not** tagged, and **not** published
as a GitHub Release. Approval of the version number is still pending from the user.
