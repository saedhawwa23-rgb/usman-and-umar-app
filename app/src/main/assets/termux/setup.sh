#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

LOG_DIR="$HOME/.local/state/usman-umar"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/setup.log"
exec > >(tee -a "$LOG_FILE") 2>&1

fail() {
  printf '\nSETUP ERROR: %s\n' "$1" >&2
  exit 1
}

printf 'USMAN AND UMAR multi-ABI Termux/PRoot agent setup started.\n'
printf 'No model/API credentials will be requested, created, or written.\n'
printf 'Termux log: %s\n' "$LOG_FILE"

command -v pkg >/dev/null 2>&1 || fail 'Run this installer from Termux, not Android shell.'
host_arch="$(dpkg --print-architecture 2>/dev/null || true)"
case "$host_arch" in
  arm)
    runtime_arch=arm32
    distro_arch=arm
    expected_guest_arch=armhf
    ;;
  x86_64)
    runtime_arch=x86_64
    distro_arch=x86_64
    expected_guest_arch=amd64
    ;;
  *)
    fail "No bundled PRoot runtime for Termux architecture '${host_arch:-unknown}'; supported targets are ARM32 and x86_64."
    ;;
esac
runtime_dir="$HOME/.local/lib/usman-umar/proot-$runtime_arch"
[ -x "$HOME/.local/bin/uua-proot" ] || fail 'Install the bundled PRoot runtime and loader in the app first; its runtime probe must pass.'
[ -s "$runtime_dir/architecture" ] && [ "$(cat "$runtime_dir/architecture")" = "$runtime_arch" ] || fail "Installed PRoot runtime does not match this Termux architecture ($runtime_arch)."

available_kib="$(df -Pk "$HOME" | awk 'NR == 2 { print $4 }')"
[ "${available_kib:-0}" -ge 4194304 ] || fail "At least 4 GiB free space is recommended for Debian, source checkouts, wheels, and build dependencies (available: ${available_kib:-unknown} KiB)."

pkg update -y
pkg install -y proot-distro git coreutils

rootfs=""
for candidate in \
  "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" \
  "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
  if [ -x "$candidate/bin/sh" ]; then rootfs="$candidate"; break; fi
done
if [ -z "$rootfs" ]; then
  printf 'Installing Debian %s rootfs through official proot-distro…\n' "$expected_guest_arch"
  proot-distro install debian --architecture "$distro_arch"
  for candidate in \
    "$PREFIX/var/lib/proot-distro/installed-rootfs/debian" \
    "$PREFIX/var/lib/proot-distro/containers/debian/rootfs"; do
    if [ -x "$candidate/bin/sh" ]; then rootfs="$candidate"; break; fi
  done
fi
[ -n "$rootfs" ] || fail 'proot-distro reported completion but the Debian rootfs could not be located.'
actual_guest_arch=$("$HOME/.local/bin/uua-proot" --link2symlink -0 -r "$rootfs" -b /dev -b /proc -b /sys -b /system /usr/bin/dpkg --print-architecture)
[ "$actual_guest_arch" = "$expected_guest_arch" ] || fail "The existing Debian rootfs is $actual_guest_arch, but $expected_guest_arch is required for this $runtime_arch Termux runtime. Remove/reinstall the Debian rootfs from Termux, then retry."

state_dir="$HOME/.local/state/usman-umar"
mkdir -p "$state_dir"
guest_setup="$state_dir/agent-setup-debian.sh"
cat > "$guest_setup" <<'GUEST_SETUP_EOF'
#!/bin/sh
set -u
export DEBIAN_FRONTEND=noninteractive

status=0
if ! apt-get update; then
  printf 'DEBIAN_PACKAGE_INDEX_FAILED\n' >&2
  exit 20
fi
if ! apt-get install -y --no-install-recommends \
  ca-certificates git python3 python3-venv python3-pip nodejs npm \
  build-essential pkg-config libffi-dev libssl-dev rustc cargo; then
  printf 'DEBIAN_AGENT_TOOLCHAIN_INSTALL_FAILED\n' >&2
  exit 21
fi

python3 - <<'PY'
import sys
print('Debian Python:', sys.version.split()[0])
if sys.version_info < (3, 12):
    raise SystemExit('OpenManus/OpenSwarm require Python 3.12 or later.')
PY
python3_status=$?
node --version
node -e 'const m=Number(process.versions.node.split(".")[0]); if(m<20){console.error("OpenSwarm requires Node.js 20 or later."); process.exit(1)}'
node_status=$?
if [ "$python3_status" -ne 0 ] || [ "$node_status" -ne 0 ]; then
  printf 'AGENT_RUNTIME_VERSION_UNSUPPORTED\n' >&2
  exit 22
fi

install_python_project() {
  name="$1"
  url="$2"
  dest="/opt/usman-and-umar/agents/$name"
  venv="$dest/.venv"
  printf '\n=== %s: source and Python dependencies ===\n' "$name"
  mkdir -p /opt/usman-and-umar/agents
  if [ -d "$dest/.git" ]; then
    printf 'Existing checkout retained (no automatic overwrite): %s\n' "$dest"
  elif [ -e "$dest" ]; then
    printf 'Refusing to overwrite a non-Git path: %s\n' "$dest" >&2
    return 30
  elif ! git clone --depth=1 "$url" "$dest"; then
    printf '%s_SOURCE_CLONE_FAILED\n' "$name" >&2
    return 31
  fi
  if [ ! -f "$dest/requirements.txt" ]; then
    printf '%s_REQUIREMENTS_MISSING\n' "$name" >&2
    return 32
  fi
  if ! python3 -m venv "$venv"; then
    printf '%s_VENV_CREATE_FAILED\n' "$name" >&2
    return 33
  fi
  if ! PIP_NO_CACHE_DIR=1 MAX_JOBS=1 MAKEFLAGS=-j1 CMAKE_BUILD_PARALLEL_LEVEL=1 \
       "$venv/bin/python" -m pip install --disable-pip-version-check --no-cache-dir --upgrade pip setuptools wheel; then
    printf '%s_PIP_BOOTSTRAP_FAILED\n' "$name" >&2
    return 34
  fi
  if ! PIP_NO_CACHE_DIR=1 MAX_JOBS=1 MAKEFLAGS=-j1 CMAKE_BUILD_PARALLEL_LEVEL=1 \
       "$venv/bin/python" -m pip install --disable-pip-version-check --no-cache-dir -r "$dest/requirements.txt"; then
    printf '%s_DEPENDENCY_INSTALL_FAILED (ABI wheel/source-build incompatibility may be the cause)\n' "$name" >&2
    return 35
  fi
  printf '%s_SOURCE_AND_DEPENDENCIES_INSTALLED\n' "$name"
  return 0
}

install_python_project openmanus https://github.com/FoundationAgents/OpenManus.git || status=1
install_python_project openswarm https://github.com/VRSEN/OpenSwarm.git || status=1

if [ -f /opt/usman-and-umar/agents/openswarm/package-lock.json ]; then
  printf '\n=== OpenSwarm: Node.js dependencies ===\n'
  if ! (cd /opt/usman-and-umar/agents/openswarm && npm ci --ignore-scripts --no-audit --no-fund); then
    printf 'OPENSWARM_NODE_DEPENDENCY_INSTALL_FAILED\n' >&2
    status=1
  fi
fi

printf '\n=== %s agent platform compatibility note ===\n' "$expected_guest_arch"
printf 'The VRSEN Python source and requirements are installed separately from its native OpenSwarm CLI.\n'
if [ "$runtime_arch" = 'arm32' ]; then
  printf 'The upstream CLI maps non-arm64 Linux machines to x64; this installer does not fetch or run that unsupported binary on ARM32.\n'
else
  printf 'The x86_64 guest matches the upstream CLI x64 target, but this installer still does not download or run the native CLI.\n'
fi
printf 'A successful dependency install is not proof that every upstream agent tool runs on this architecture.\n'

if [ "$status" -ne 0 ]; then
  printf '\nAGENT_SETUP_INCOMPLETE: one or more project steps failed. Review the error above; successful checkout/venv work is retained for retry.\n' >&2
  exit 40
fi
printf '\nAGENT_SOURCE_SETUP_COMPLETE\n'
printf 'OpenManus: /opt/usman-and-umar/agents/openmanus/.venv/bin/python\n'
printf 'VRSEN OpenSwarm source: /opt/usman-and-umar/agents/openswarm/.venv/bin/python\n'
printf 'Node.js: %s\n' "$(node --version)"
printf 'Python: %s\n' "$(python3 --version)"
printf 'Configure model provider credentials yourself before running any model-backed agent.\n'
GUEST_SETUP_EOF
chmod 700 "$guest_setup"

printf 'Checking patched %s PRoot against the installed rootfs before dependency work…\n' "$runtime_arch"
"$HOME/.local/bin/uua-proot" --link2symlink -0 -r "$rootfs" -b /dev -b /proc -b /sys -b /system /usr/bin/dpkg --print-architecture

dns_source=""
for resolver in "$PREFIX/etc/resolv.conf" "$PREFIX/etc/resolv-conf/dns-cache"; do
  if [ -f "$resolver" ]; then dns_source="$resolver"; break; fi
done
proot_args=(--link2symlink -0 -r "$rootfs" -b /dev -b /proc -b /sys -b /system -b "$guest_setup:/tmp/uua-agent-setup.sh" -w /root)
if [ -n "$dns_source" ]; then proot_args+=(-b "$dns_source:/etc/resolv.conf"); fi

printf 'Starting Debian package/dependency setup inside %s PRoot. This can take a long time; output will return to the app.\n' "$expected_guest_arch"
"$HOME/.local/bin/uua-proot" "${proot_args[@]}" /bin/sh /tmp/uua-agent-setup.sh

printf '\nInstaller finished. Review the returned output and saved log before relying on agent/runtime status.\n'
printf 'Captured Termux output can be reviewed and explicitly saved to Google Drive from the app.\n'
