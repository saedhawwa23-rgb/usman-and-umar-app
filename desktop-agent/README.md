# Desktop companion agent

Runs on Windows 10/11, Linux and macOS. It is a plain JVM application, so the same code runs on all
three; the platform differences (screen capture, input, application discovery) are handled by the
JDK's AWT stack and per-platform discovery.

## Build

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :desktop-agent:fatJar
```

The runnable jar is written to `desktop-agent/build/libs/opendroid-desktop-agent-all.jar`.

## Run

```bash
java -jar opendroid-desktop-agent-all.jar \
  --port 8777 \
  --store ~/.opendroid/agent-clients.json \
  --root ~/Shared \
  --tls-keystore ~/.opendroid/agent.p12 --tls-password <password>
```

On start it prints a pairing code, valid for five minutes. Enter that code on the phone to pair.
Every later connection authenticates with a stored secret, so the code is not needed again.

| Flag | Meaning |
|---|---|
| `--port <n>` | Port to listen on. Default `8777`. |
| `--store <path>` | Where paired clients are kept. Default `./agent-clients.json`. |
| `--root <path>` | The only folder file transfer may touch. Default `./shared`. |
| `--name <text>` | The name shown on the phone. |
| `--tls-keystore <p12>` | PKCS#12 keystore. Supplying it turns on TLS. |
| `--tls-password <pw>` | Keystore password. |
| `--no-input` | Do not accept mouse or keyboard input. |
| `--no-files` | Do not allow file transfer. |
| `--help` | Show usage. |

## TLS

Generate a self-signed keystore for a first test:

```bash
keytool -genkeypair -alias opendroid -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=opendroid-agent" -storetype PKCS12 \
  -keystore ~/.opendroid/agent.p12 -storepass <password>
```

The agent accepts only TLS 1.2 and 1.3. Without a keystore it listens on plain TCP and prints a
warning; use that only on a trusted local network.

## What it can do

- Stream the screen as JPEG frames, scaled to a width the phone chooses.
- Inject mouse movement, buttons, the wheel, key presses and typed text.
- List installed applications and launch one by id.
- List, download and upload files inside `--root` only. A path that resolves outside the root is
  refused.
- Answer heartbeats and close a session on request.
- Revoke a client, so a later connection from it is refused.

## Security notes

- Pairing derives a shared secret with HKDF-SHA256 from the one-time code and both handshake nonces.
  The code itself is never sent again.
- Every later connection is proved with an HMAC over a fresh challenge, so a replayed handshake
  fails.
- The client store holds only each client's secret, base64-encoded, with owner-only file
  permissions where the platform supports them.
- Input and file transfer can each be disabled for a deployment.
- The agent runs with the privileges of the user who starts it. It does not try to raise them.

## Platform notes

- **Windows:** application discovery reads the Start Menu shortcuts. Screen capture and input work
  on the interactive desktop; a session started as a service has no desktop and will not capture.
- **Linux:** under Wayland, `java.awt.Robot` may capture only the agent's own windows unless the
  desktop portal grants screen access. X11 works normally.
- **macOS:** the agent needs Screen Recording and Accessibility permission in System Settings →
  Privacy & Security, or capture and input are refused by the OS.
