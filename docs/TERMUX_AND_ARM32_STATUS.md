# Termux, ARM32 PRoot, and build status

## Delivered in the Android source branch

- `app/src/main/assets/termux/setup.sh` bootstraps Git, Python, Node.js, build dependencies, isolated OpenManus/OpenSwarm environments, and project dependency installation. It uses the official FoundationAgents/OpenManus and VRSEN/OpenSwarm repositories; the OpenSwarm identity is explicit because the name is shared by unrelated projects. The installer does not embed or call a paid model API. Actual agent inference still requires a provider/API key supported by those upstream projects.
- Android-native app launch, URL opening, and the Sharesheet are implemented without AI. Public GitHub clone accepts only canonical GitHub repository URLs and passes them as argv.
- Termux tasks are explicit and allowlisted. Active tasks can receive SIGINT; the bridge checks `/proc/<pid>/stat` start time before signaling to reduce PID-reuse risk.
- The Termux screen now offers installation of the bundled static ARM32 PRoot and loader into Termux home. The installer verifies the binary and creates a `uua-proot` launcher setting `PROOT_LOADER`.

Termux itself must be installed by the user, with `allow-external-apps=true` and Android's Termux `RUN_COMMAND` permission enabled. The app has not been tested on the Samsung Galaxy M10 in this session.

## ARM32 PRoot/loader compile evidence

The source checkout was `oonid/pr` at commit `1b2ea7966b5105e7d58051a394c25da97bf85a7d`. The build used Android NDK r27c's API 28 ARM sysroot and compiler-rt runtime, with a native host Clang 18 shim because this execution host is `aarch64` while the NDK download provides `linux-x86_64` host tools. The output target was ARMv7/ELF32 only; no 64-bit target binary was substituted.

The stock upstream build script needed two local fixes, preserved as [oonid-pr-arm32-build-fixes.patch](oonid-pr-arm32-build-fixes.patch): map the `armv7a-linux-androideabi` compiler triple to the NDK's actual `usr/lib/arm-linux-androideabi` sysroot directory, and parse ELF32 program headers when adjusting the TLS segment alignment. The host Clang shim selected NDK's ARM compiler-rt builtins. These changes were applied to the temporary upstream checkout for this build; they were not committed upstream.

The observed blockers and exact diagnostics were:

1. Initial sysroot setup: `cp: cannot stat '/home/ubuntu/projects/upstream-review/pr/build/ndk/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/armv7a-linux-androideabi': No such file or directory`.
2. The NDK's x86_64 host compiler could not execute on this aarch64 host: `.../armv7a-linux-androideabi28-clang: line 4: .../bin/clang: cannot execute binary file: Exec format error`.
3. The first native-host Clang link attempt lacked GCC's runtime: `ld.lld: error: unable to find library -lgcc`; using the NDK's ARM compiler-rt resolved this.
4. After PRoot linked, the upstream TLS fixer interpreted the ELF32 header as ELF64 and reported `struct.error: unpack_from requires a buffer of at least 360290169215039532 bytes for unpacking 4 bytes at offset 360290169215039528 (actual buffer size is 2145576)`. The ELF32-aware patch resolved it.

Verified outputs from `build/out/arm`:

| Artifact | Size | SHA-256 | Verification |
|---|---:|---|---|
| `proot` | 2,145,576 bytes | `fb87717e9d5add5eb33460180a175cfe6d0ada1401d1a5b7e6034f9bd0a1eb92` | ELF32, ARM EABI5, statically linked, no dynamic section; TLS `p_align=0x40` |
| `loader` | 18,104 bytes | `e631fda2757f4845dddf13e997d88af5216a9a9158c2353b70cdfb7ff1fd03cd` | ELF32, ARM EABI5, statically linked |

The stripped runtime is packaged as a 261,415-byte gzip tar asset at `app/src/main/assets/proot/arm32-runtime.tar.gz`; SHA-256: `87a34b19a2046325de20747cb340773202b3fe5b9594b2d82c7d8ece9b6aea08`. The native binaries were verified with `file`, `readelf`, and the build script, but have not been executed on Android hardware.

**Still not complete:** OpenManus and OpenSwarm are installed by the bootstrap into Termux's normal home environment, not provisioned or run inside a Linux PRoot rootfs. The PRoot/loader installation action is implemented, but full rootfs provisioning, agent execution through it, and device verification remain outstanding.

## Google Drive and local-output limitation

The existing Google Drive browse/search/upload/download/export implementation remains in the app, with the inherited 5 MiB upload cap. Google OAuth/API enablement, consent-screen configuration, and test-user registration remain external prerequisites. The Sharesheet shares text; GitHub clones, Termux outputs, and the bundled PRoot installer do not yet have a direct, integrated “Save to Drive” action. Drive is therefore not yet a complete hub for every local-tool output, and no live Drive operation was verified in this session.

## Android APK and CI status

The work is on `feature/source-migration` with an unmerged [Pull Request #2](https://github.com/umarashebir-cpu/usman-and-umar-app/pull/2).

The local `./gradlew testDebugUnitTest assembleDebug` attempt stopped before compilation with the exact message: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file at '/home/ubuntu/projects/usman-and-umar-app-fresh/local.properties'.`

The PR Android CI run [39631388833](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39631388833) for commit `b78ef8f` failed in all five jobs in about two seconds, with zero steps and no runner assigned. After the PRoot asset commit `b9a5513`, the newest PR run [39634074633](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39634074633) again failed in all five jobs with zero steps. The manual APK workflow [39631495666](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39631495666) for `b78ef8f` failed before its first step on both attempts; the latest APK workflow [39634082027](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39634082027) for `b9a5513` likewise failed with zero steps and has no downloadable artifacts. The available GitHub token could not retrieve check annotations/logs (`403`, insufficient `checks:read`); job metadata had empty runner names. This is not evidence of a Kotlin or Gradle source failure, and the underlying GitHub runner cause could not be determined. No APK was produced for the current branch head. The previously reported APK belongs to older commit `652c87c` and is not represented as a build of this update.

## References

- [FoundationAgents/OpenManus](https://github.com/FoundationAgents/OpenManus)
- [VRSEN/OpenSwarm](https://github.com/VRSEN/OpenSwarm)
- [oonid/pr](https://github.com/oonid/pr)
- [oonid/pr ARM build script](https://github.com/oonid/pr/blob/main/scripts/build.sh)
