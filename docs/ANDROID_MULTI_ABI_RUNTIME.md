# Android multi-ABI PRoot runtime

## Implemented

The Android app now declares `armeabi-v7a` and `x86_64` ABI filters. `TermuxProotRuntime` selects the matching bundled archive from `Build.SUPPORTED_ABIS`, checks the complete archive SHA-256 before passing it to Termux, and the installer independently checks the extracted PRoot and loader hashes. Runtime installation stages into an architecture-specific directory, probes the candidate before switching the launcher, and retains rollback behavior. The generated launcher and an `architecture` marker refer to the selected runtime.

The Termux bootstrap maps a 32-bit ARM Termux host (`dpkg --print-architecture=arm`) to a Debian `armhf` guest, and an x86_64 Termux host to a Debian `amd64` guest. It rejects mismatched or unsupported runtime/rootfs architectures rather than trying to execute a binary for another ABI. This preserves the Galaxy M10 Android 9/ARM32 path; that device continues to receive the 32-bit ARM runtime, not an x86_64 substitute.

## Checked-in runtime artifacts

| Target | Archive | Archive size | Archive SHA-256 | PRoot SHA-256 | Loader SHA-256 |
|---|---|---:|---|---|---|
| ARM32 / `armeabi-v7a` | `app/src/main/assets/proot/arm32-runtime.bundle` | 261,362 bytes | `4b3ed9fc191431f193570f2a10f2a944db2fed4400d0195e114476979bd914b7` | `203e237e56f1894977674b2d0e96851035e98d9fb4b2a27b1c1797a014f7e288` | `19e9a2dd9bca570bfd4c92cdfca3eecd792e91aa0ae21067cc06bf719fcf152c` |
| x86_64 | `app/src/main/assets/proot/x86_64-runtime.bundle` | 319,960 bytes | `8b424b4723124fae45bdd3120d55e5b4fb7a2450a52623cdf9371eacc42cd205` | `6ea2add4dd8886ea7e9ef279f1fbf0a45a941796a2c24a67ccecc5fe5e63d4ae` | `2e386f6a2e0288bcfa5bba5dbc93dad288e8e22e435c4af9014ce16f23c241ba` |

Both archives contain only `proot` and `loader`. `file` and `readelf` identify the first pair as statically linked ELF32/ARM EABI5 and the second pair as statically linked ELF64/x86-64; the x86_64 PRoot has no dynamic section or ELF interpreter. The binaries are stripped. The x86_64 binaries were cross-compiled from the patched `oonid/pr` source using the Android NDK r27c sysroot for API 28 and the x86_64 Android target. The x86_64 archive and member hashes above were recalculated from the checked-in files.

## Verification boundary

The archives, ELF class/machine types, member hashes, Android ABI selection logic, and shell bootstrap syntax can be checked on the build host. A local attempt to execute the Android-target x86_64 static PRoot under QEMU user emulation returned exactly: `Error while loading /tmp/uua-runtime-inspect/x86_64-proot: Exec format error`. That is not evidence of an Android-device failure, but it means this host-side QEMU run did **not** validate its `--version` behavior. No x86_64 Android/Termux device has been used to run the installer or rootfs probe, and the Galaxy M10 has not been used to rerun the ARM32 installer in this session. Device-level execution remains **UNVERIFIED — REQUIRES DEVICE**.

The OpenSwarm setup still provisions source and declared dependencies only; it does not install or execute OpenSwarm's native CLI. Its upstream target selector chooses x64 on the x86_64 Linux guest, but that CLI path and actual agent execution remain unverified. On the ARM32 guest, the upstream x64 native CLI is not used. Successful PRoot installation or Python dependency installation must not be reported as successful autonomous-agent execution.
