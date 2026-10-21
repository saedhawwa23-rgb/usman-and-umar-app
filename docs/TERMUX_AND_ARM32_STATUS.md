# Termux, ARM32 PRoot, and build status

## Delivered in the Android source branch

- `app/src/main/assets/termux/setup.sh` now installs the Termux `proot-distro` tooling, installs a Debian `arm` (ARM32) rootfs, verifies the patched PRoot runtime, and runs dependency setup inside that guest. It clones the official FoundationAgents/OpenManus and VRSEN/OpenSwarm sources, creates isolated Python venvs, installs their declared requirements, and installs OpenSwarm's locked Node dependencies when a lockfile exists. It checks free storage and minimum Python/Node versions, reports failures, and does not embed or call a paid model API. Actual inference still requires a user-configured supported provider.
- Android-native app launch, URL opening, and the Sharesheet are implemented without AI. Public GitHub clone accepts only canonical GitHub repository URLs and passes them as argv.
- Termux tasks are explicit and allowlisted. Active tasks can receive SIGINT; the bridge checks `/proc/<pid>/stat` start time before signaling to reduce PID-reuse risk.
- The Termux screen offers installation of the bundled static ARM32 PRoot and loader. Installation validates archive/member hashes, probes the binary and loader, stages the launcher, and rolls back the prior runtime if the final probe fails. The Android app also exposes review-gated Drive upload of captured task output and entered local-tool text.

Termux itself must be installed by the user, with `allow-external-apps=true` and Android's Termux `RUN_COMMAND` permission enabled. The app has not been tested on the Samsung Galaxy M10 in this session.

## ARM32 PRoot/loader compile evidence

The source checkout was `oonid/pr` at commit `1b2ea7966b5105e7d58051a394c25da97bf85a7d`. An initial build used native host Clang 18 shims; to improve provenance, the current independent rebuild restored Google's official Android NDK r27c host tools and invokes its API 28 ARMv7 compiler through installed QEMU x86_64 emulation on this aarch64 Sandbox. The target is ARMv7/ELF32 only; no 64-bit target binary is substituted. The official-NDK rebuild is in progress and replaces the earlier compiler-shim artifacts once verified.

The stock upstream build script needed two local fixes, preserved as [oonid-pr-arm32-build-fixes.patch](oonid-pr-arm32-build-fixes.patch): map the `armv7a-linux-androideabi` compiler triple to the NDK's actual `usr/lib/arm-linux-androideabi` sysroot directory, and parse ELF32 program headers when adjusting TLS segment alignment. These changes are local to the temporary upstream checkout; they were not committed upstream.

The observed blockers and exact diagnostics were:

1. Initial sysroot setup: `cp: cannot stat '/home/ubuntu/projects/upstream-review/pr/build/ndk/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/armv7a-linux-androideabi': No such file or directory`.
2. The NDK's x86_64 host compiler could not execute on this aarch64 host: `.../armv7a-linux-androideabi28-clang: line 4: .../bin/clang: cannot execute binary file: Exec format error`.
3. The first native-host Clang link attempt lacked GCC's runtime: `ld.lld: error: unable to find library -lgcc`; using the NDK's ARM compiler-rt resolved this.
4. After PRoot linked, the upstream TLS fixer interpreted the ELF32 header as ELF64 and reported `struct.error: unpack_from requires a buffer of at least 360290169215039532 bytes for unpacking 4 bytes at offset 360290169215039528 (actual buffer size is 2145576)`. The ELF32-aware patch resolved it for the earlier host-shim build.

Verified outputs from `build/out/arm`:

| Artifact | Size | SHA-256 | Verification |
|---|---:|---|---|
| Official NDK r27c build `proot` | 2,145,576 bytes before strip | `042aa8824234cb23f3737be4412d2a723b4fcfa645557c15d2fc5b7f1f7c4edc` | ELF32 ARM EABI5, static, no dynamic section; TLS `p_align=0x40`; `qemu-arm proot --version` prints `5.4.0-pr` |
| Packaged stripped `proot` | 491,512 bytes | `203e237e56f1894977674b2d0e96851035e98d9fb4b2a27b1c1797a014f7e288` | Same ELF32/ARM/static/TLS properties; version smoke test passes under QEMU |
| Official NDK r27c build `loader` | 18,216 bytes before strip | `794b2352f299d03c237670eb6cfcd8fa706ffa66ab9b0c1c025f275097f88236` | ELF32 ARM EABI5, static |
| Packaged stripped `loader` | 17,720 bytes | `19e9a2dd9bca570bfd4c92cdfca3eecd792e91aa0ae21067cc06bf719fcf152c` | ELF32 ARM EABI5, static |

The official-NDK stripped runtime now packaged in the Android source is a 261,362-byte gzip tar at `app/src/main/assets/proot/arm32-runtime.tar.gz`; SHA-256: `4b3ed9fc191431f193570f2a10f2a944db2fed4400d0195e114476979bd914b7`. Archive size encodes to 348,484 base64 characters, within the app's 400,000-character argv limit. Members were verified with `file`/`readelf`; `proot --version` succeeds under `qemu-arm`. It has not been executed on Android hardware. The installer-side PRoot rootfs probe still requires execution on the actual Termux/Android environment.

**Still not complete:** The new automatic bootstrap has not been run on the Galaxy M10. Debian ARM32 Python/Node versions and all package wheels may not meet upstream minimums; the script fails with explicit status if they do not. OpenSwarm's current upstream native-binary selector maps Linux machines other than aarch64/arm64 to x64; this installer intentionally does not fetch or run that unsupported x86_64 executable. Source/dependency provisioning is not proof that either agent actually starts or completes model tasks. Agent launch, abort propagation to descendant processes, agent output retrieval, and on-device testing remain outstanding. The update check validates that both clones and venvs exist, but it does not certify runtime compatibility.

## Google Drive and local-output limitation

The existing Google Drive browse/search/upload/download/export implementation remains in the app, with the inherited 5 MiB upload cap. Session state is now atomic and generation-guarded to reject stale OAuth callbacks and stale failures. Captured Termux output and user-entered local-tool text can be previewed and explicitly uploaded to Drive; captured logs show a bounded preview but upload the full output only after confirmation. This is not an archive export of a cloned Git repository or packaged APK, and the bundled runtime itself is not directly saved. Google OAuth/API enablement, consent-screen configuration, and test-user registration remain external prerequisites. No live Drive operation was verified in this session.

## Android APK and CI status

The work is on `feature/source-migration` with an unmerged [Pull Request #2](https://github.com/umarashebir-cpu/usman-and-umar-app/pull/2).

The local build used JDK 21 and Android SDK API 36. Against source commit `c576ae19d0e9b45cc9b83df02642e989ffa603d0`, `:app:assembleDebug` and focused unit tests passed. The focused run selected `TermuxGitHubCloneTest` (6 tests) and `GoogleDriveSessionTest` (3 tests), with 9 total, 0 failures, 0 errors, 0 skipped. The APK is `app/build/outputs/apk/debug/app-debug.apk`, 83,366,308 bytes, SHA-256 `f8d1d7762556eec20bc97e896aa823ba51dd3e89f058b988dcc411b03c330fb0`. It is package `com.usmanandumar.app`, versionName `3.0.0-cloud-ai`, versionCode 23; debug signature verifies with APK Signature Scheme v2. Certificate SHA-1: `f470b8d9ca1ca5a762406e8716865dd21ec9aa8e`. This is debug-signed, not a production/release-signed APK. A full unfiltered unit-test run was interrupted after the Robolectric runner stalled while downloading Android SDK artifacts over HTTPS; therefore only the focused test result is claimed.

PR #2 remains open and unmerged at [Pull Request #2](https://github.com/umarashebir-cpu/usman-and-umar-app/pull/2). The latest PR CI run for `c576ae19` ([run 39644694652](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39644694652)) failed all five jobs with empty step lists. The build-only artifact run ([run 39644723617](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39644723617)) likewise failed before its first step, with no runner/steps and no hosted artifact. This is a GitHub runner/infrastructure-level failure, not a reported source compilation failure; no GitHub-provided APK download link exists. The APK above is the locally built and locally verified artifact.

The PR Android CI run [39631388833](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39631388833) for commit `b78ef8f` failed in all five jobs in about two seconds, with zero steps and no runner assigned. After the PRoot asset commit `b9a5513`, the newest PR run [39634074633](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39634074633) again failed in all five jobs with zero steps. The manual APK workflow [39631495666](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39631495666) for `b78ef8f` failed before its first step on both attempts; the latest APK workflow [39634082027](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39634082027) for `b9a5513` likewise failed with zero steps and has no downloadable artifacts. The available GitHub token could not retrieve check annotations/logs (`403`, insufficient `checks:read`); job metadata had empty runner names. This is not evidence of a Kotlin or Gradle source failure, and the underlying GitHub runner cause could not be determined. No APK was produced for the current branch head. The previously reported APK belongs to older commit `652c87c` and is not represented as a build of this update.

## References

- [FoundationAgents/OpenManus](https://github.com/FoundationAgents/OpenManus)
- [VRSEN/OpenSwarm](https://github.com/VRSEN/OpenSwarm)
- [oonid/pr](https://github.com/oonid/pr)
- [oonid/pr ARM build script](https://github.com/oonid/pr/blob/main/scripts/build.sh)
