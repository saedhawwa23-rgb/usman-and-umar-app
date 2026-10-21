# Release delivery and remaining verification

## GitHub APK release

A private GitHub prerelease was created at [v3.0.0-cloud-ai-debug](https://github.com/umarashebir-cpu/usman-and-umar-app/releases/tag/v3.0.0-cloud-ai-debug). Its asset is [app-debug.apk](https://github.com/umarashebir-cpu/usman-and-umar-app/releases/download/v3.0.0-cloud-ai-debug/app-debug.apk). Because the repository is private, downloading requires a GitHub account with repository access.

The APK is 83,366,308 bytes and has SHA-256 `f8d1d7762556eec20bc97e896aa823ba51dd3e89f058b988dcc411b03c330fb0`. It is package `com.usmanandumar.app`, version `3.0.0-cloud-ai` (version code 23), signed with the sandbox debug key, not a production signing key. The release tag points to source commit `c576ae19d0e9b45cc9b83df02642e989ffa603d0`, which matches the source used for this APK; later branch commits contain documentation only.

## GitHub Actions diagnosis

For repository `umarashebir-cpu/usman-and-umar-app`, GitHub reports Actions enabled and permits all actions. The Android CI and APK workflows are listed as active. The workflow files specify `runs-on: ubuntu-latest`, contain build/test steps, pass a basic YAML parse, and GitHub created the named jobs from them.

The latest checked PR run, [39644953027](https://github.com/umarashebir-cpu/usman-and-umar-app/actions/runs/39644953027) on `cff1f52d72deeca85b18f608aa0d5e20702cf75a`, failed all five jobs before their first step. Each job has an empty step list and blank runner name/group. This is direct evidence of failure before runner assignment, not a Kotlin/Gradle compilation failure. The available GitHub integration cannot read check-run output or the account billing endpoint (`403 Resource not accessible by integration`); the repository Actions-usage endpoint returned `404`. Therefore Actions being disabled and workflow YAML syntax are ruled out by the observed settings and job creation, but the available evidence does **not** distinguish quota/billing restrictions from a GitHub-hosted runner provisioning or account policy issue. No exact billing diagnosis can responsibly be claimed from these permissions.

## OpenSwarm ARM32 finding

The installer does not download or run an x64 binary in the ARM32 Debian guest. I did not change upstream's architecture selector to return an ARM32 package name because that package does not exist and would leave OpenSwarm without its native CLI.

The inspected upstream `run_utils.py` returns `arm64` only for `arm64`/`aarch64` and otherwise returns `x64` [1]. OpenSwarm's package metadata lists Linux ARM64 and x64 CLI packages, not ARM32 [2]. Queries to the npm registry for Linux `arm`, `arm32`, `armhf`, `armv7`, and `armv7l` packages returned `404`. The official platform-binary build/package workflow likewise lists Linux ARM64 and x64 assets, but no ARM32 target [3]. The underlying [VRSEN AgentSwarm CLI release](https://github.com/VRSEN/agentswarm-cli/releases/tag/v1.4.43) publishes ARM64 and x64 Linux builds, not ARM32 [4]. Pointing the selector at `arm32` would therefore not create a runnable binary; substituting x64 would violate the device ABI requirement. Genuine ARM32 support requires an upstream ARM32-compatible CLI/runtime build or a separately supported source implementation. Until then, OpenSwarm source/dependency provisioning is not equivalent to a working OpenSwarm CLI on this device.

## Galaxy M10 Google Drive upload test

The app has upload controls in its UI. On the Google Drive screen, **UPLOAD TO MY DRIVE ROOT** opens Android's document picker; after you select a file, the app uploads it and verifies the returned Drive metadata. The upload limit is 5 MiB and the destination is My Drive's root. On the Termux screen, **SAVE TEXT TO DRIVE** uploads entered text after you tap it. Each captured Termux result also has **SAVE CAPTURED OUTPUT TO DRIVE**, which first displays a review dialog and requires **UPLOAD FULL OUTPUT**. These UI paths and the focused repository tests exist, but no live Google OAuth/Drive transfer or physical Galaxy M10 run has been performed, so the feature is not yet device-verified.

Before testing, check the intended Google Cloud project:

1. Confirm the Android OAuth client ID `314587321554-car3jm7lgi39qeq8309g679415lk4q.apps.googleusercontent.com` is an Android client for package `com.usmanandumar.app`, with the debug certificate SHA-1 `F4:70:B8:D9:CA:1C:A5:A7:62:40:6E:87:16:86:5D:D2:1E:C9:AA:8E`.
2. Confirm the Web OAuth client ID `314587321554-7nmt1gk0cotier4dbcrctl1qcv1950u3.apps.googleusercontent.com` is a Web application client in that same project. The app uses the Web client ID as Credential Manager's server client ID; it must not be replaced with the Android client ID.
3. Enable Google Drive API, configure the OAuth consent screen, and add the Google account you will test with as a test user if the consent screen is in testing mode. The app requests `drive.readonly` and `drive.file`; Drive may impose additional OAuth verification requirements for production use.

Then install and exercise the APK:

1. Open the [release page](https://github.com/umarashebir-cpu/usman-and-umar-app/releases/tag/v3.0.0-cloud-ai-debug) on the phone while signed into an account that can access the private repository. Download `app-debug.apk`. If Android blocks the installation, allow Chrome to install unknown apps for this one installation, install the APK, and then disable that permission again if desired.
2. Open USMAN AND UMAR, navigate to **Settings → Google Drive**, and tap **SIGN IN WITH GOOGLE**. Choose the test account.
3. Tap **AUTHORIZE AND VERIFY DRIVE ACCESS** and grant the requested permissions. The screen should show **Drive verified** with the matching account email. If authorization fails, first check the project/client IDs, package name and registered SHA-1, API enablement, consent-screen test user, and device network/Google Play services.
4. Create a harmless file such as `drive-smoke-test.txt` containing a timestamp, under 5 MiB, on the phone. Tap **UPLOAD TO MY DRIVE ROOT**, choose that file in Android's picker, and wait for **Upload verified** plus the Drive file ID. Refresh the list, then confirm the same file appears in My Drive using the Drive app or browser.
5. Optionally navigate to **Termux Integration → Native Phone Tools**, enter a harmless test string, and tap **SAVE TEXT TO DRIVE**. Confirm the upload message and verify the resulting text file in My Drive. To test captured output, select **SAVE CAPTURED OUTPUT TO DRIVE**, review the full-upload warning, and tap **UPLOAD FULL OUTPUT** only if the log contains no secrets or private data.

A successful UI message plus the file visible in the same Drive account is the device-level smoke test. Do not use passwords, OAuth tokens, private logs, or other sensitive data as test content.

## Branch and PR

The requested changes and this evidence report are pushed to `feature/source-migration`; [PR #2](https://github.com/umarashebir-cpu/usman-and-umar-app/pull/2) remains open and unmerged.

## References

[1]: https://github.com/VRSEN/OpenSwarm/blob/main/run_utils.py "VRSEN OpenSwarm architecture selector"
[2]: https://github.com/VRSEN/OpenSwarm/blob/main/package.json "VRSEN OpenSwarm optional platform dependencies"
[3]: https://github.com/VRSEN/OpenSwarm/blob/main/scripts/platform-assets.mjs "VRSEN OpenSwarm native platform asset matrix"
[4]: https://github.com/VRSEN/agentswarm-cli/releases/tag/v1.4.43 "VRSEN AgentSwarm CLI v1.4.43 release assets"
