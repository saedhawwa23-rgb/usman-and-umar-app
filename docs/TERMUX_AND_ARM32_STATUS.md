# Termux agent setup and ARM32 status

## Agent sources and runtime requirements

The bundled setup script selects the official [FoundationAgents/OpenManus](https://github.com/FoundationAgents/OpenManus) repository and [VRSEN/OpenSwarm](https://github.com/VRSEN/OpenSwarm). The OpenManus README currently documents Python 3.12 and `requirements.txt`; its configuration describes an LLM provider/API key for agent execution. VRSEN/OpenSwarm documents Node.js 20+, Python 3.12+, and one of its supported model-provider keys. “OpenSwarm” is not a unique project name; this bootstrap intentionally identifies VRSEN's repository rather than silently mixing it with the separate openswarm-os project.

`app/src/main/assets/termux/setup.sh` installs Termux packages (`python`, `nodejs`, `git`, `clang`, `make`, `cmake`, `pkg-config`, `libffi`, and `openssl`), checks the documented Python/Node baselines, clones the two public repositories if the expected target path is absent, creates a separate Python virtual environment per source tree, installs each repository's `requirements.txt`, and runs `npm ci` for VRSEN/OpenSwarm when a lockfile is present. Existing checkouts are reused rather than updated or overwritten. Installation output is logged inside Termux at `~/.local/state/usman-umar/setup.log`.

The setup does not request, embed, or call a model-provider API key. Local app launching, URL opening, Sharesheet text sharing, GitHub clone commands, and setup orchestration do not need an AI API. OpenManus/OpenSwarm agent execution is distinct: the upstream projects need a configured model provider to perform AI tasks. Dependencies may also require internet access and compatible Termux/ARM packages, so an APK build cannot prove that installation succeeds on the Samsung device.

Termux must already be installed separately. The user must enable Termux's `allow-external-apps` setting and grant Android's Termux `RUN_COMMAND` permission before the app can invoke the installer.

## Patched PRoot and ARM32

The selected `oonid/pr` source contains a C build script with an explicit `--arch=arm` target, an Android NDK r27c download path, Android API level 28, and the `armv7a-linux-androideabi` toolchain triple. Its documented README examples show arm64, not ARM32. The script copies its built `proot` and optional `loader` outputs under `build/out/arm`; this does not by itself install those outputs in Termux or integrate them into this app.

The ARM32 build attempt is running from the source checkout at `projects/upstream-review/pr`. It first failed to fetch a submodule with a TLS/RPC disconnect. No ARM32 compiler diagnostic or successful ARM32 binary has been verified yet. The log and final result must be checked before reporting compiler status. No pre-built 64-bit binary is used as a substitute.

Even if PRoot compiles, full OpenManus/OpenSwarm execution inside patched PRoot remains a separate integration task. This document does not claim the generated PRoot/loader has been integrated into the APK or tested on Android 9/ARM32.

## Google Drive and local outputs

The app already has the Google Drive browse/search/upload/download/export implementation, but OAuth/API enablement and consent configuration remain external prerequisites. The native Sharesheet currently shares text, not arbitrary generated files. GitHub clones and Termux setup outputs do not yet have a direct “Save to Drive” action, and the inherited Drive upload implementation is capped at 5 MiB. Therefore Drive is not yet a complete hub for every local-tool output.

## References

- [OpenManus README](https://github.com/FoundationAgents/OpenManus)
- [VRSEN/OpenSwarm README](https://github.com/VRSEN/OpenSwarm)
- [oonid/pr README](https://github.com/oonid/pr)
- [oonid/pr ARM build script](https://github.com/oonid/pr/blob/main/scripts/build.sh)
