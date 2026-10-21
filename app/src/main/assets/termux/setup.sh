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

printf 'USMAN AND UMAR local runtime setup started. No AI API keys are requested or written.\n'
printf 'Log: %s\n' "$LOG_FILE"

command -v pkg >/dev/null 2>&1 || fail 'Termux pkg command is unavailable; run this only inside Termux.'
pkg update -y
pkg install -y python nodejs git clang make cmake pkg-config libffi openssl

python --version
node --version
python - <<'PY'
import sys
if sys.version_info < (3, 12):
    raise SystemExit("OpenManus requires Python 3.12 or later; update Termux packages and retry.")
PY
node - <<'JS'
const major = Number(process.versions.node.split('.')[0]);
if (major < 20) {
  console.error("OpenSwarm requires Node.js 20 or later; update Termux packages and retry.");
  process.exit(1);
}
JS

mkdir -p "$HOME/agents"

clone_if_missing() {
  local url="$1"
  local directory="$2"
  if [ -d "$directory/.git" ]; then
    printf 'Using existing checkout: %s\n' "$directory"
  elif [ -e "$directory" ]; then
    fail "Refusing to overwrite a non-Git path: $directory"
  else
    git clone --depth=1 "$url" "$directory"
  fi
}

install_python_project() {
  local directory="$1"
  local environment="$2"
  [ -f "$directory/requirements.txt" ] || fail "Missing requirements.txt in $directory"
  python -m venv "$environment"
  "$environment/bin/python" -m pip install --upgrade pip
  "$environment/bin/python" -m pip install -r "$directory/requirements.txt"
}

clone_if_missing 'https://github.com/FoundationAgents/OpenManus.git' "$HOME/FoundationAgents-OpenManus"
install_python_project "$HOME/FoundationAgents-OpenManus" "$HOME/FoundationAgents-OpenManus/.venv"

clone_if_missing 'https://github.com/VRSEN/OpenSwarm.git' "$HOME/VRSEN-OpenSwarm"
install_python_project "$HOME/VRSEN-OpenSwarm" "$HOME/VRSEN-OpenSwarm/.venv"
if [ -f "$HOME/VRSEN-OpenSwarm/package-lock.json" ]; then
  (cd "$HOME/VRSEN-OpenSwarm" && npm ci)
fi

printf '\nSetup completed.\n'
printf 'OpenManus checkout: %s/FoundationAgents-OpenManus\n' "$HOME"
printf 'OpenManus Python: %s/FoundationAgents-OpenManus/.venv/bin/python\n' "$HOME"
printf 'OpenSwarm checkout: %s/VRSEN-OpenSwarm\n' "$HOME"
printf 'OpenSwarm Python: %s/VRSEN-OpenSwarm/.venv/bin/python\n' "$HOME"
printf 'OpenSwarm Node packages: %s/VRSEN-OpenSwarm/node_modules (when package-lock.json is present)\n' "$HOME"
printf 'Both projects need a user-configured model/API provider to run AI tasks; setup does not add keys or call paid APIs.\n'
