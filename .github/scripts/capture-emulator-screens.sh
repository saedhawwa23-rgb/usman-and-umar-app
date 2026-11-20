#!/bin/sh
set -eu

version="$1"
output_dir="$2"
gradle_log="$3"
workdir="$4"
package_name="${PACKAGE_NAME:?PACKAGE_NAME is required}"
screenshot_test="${SCREENSHOT_TEST:?SCREENSHOT_TEST is required}"

# The instrumentation test names each capture "v${BuildConfig.VERSION_NAME}-<screen>.png", so the
# prefix must come from the tree being built rather than a literal. A hardcoded prefix silently
# stopped matching once the app version moved past it, and the pull loop then waited forever for
# files the test had already written under a different name.
expected_version="${EXPECTED_VERSION:-}"
if [ -n "$expected_version" ]; then
  version="$expected_version"
else
  version="v$(sed -n 's/^[[:space:]]*versionName[[:space:]]*"\([^"]*\)".*/\1/p' "$workdir/app/build.gradle" | head -n 1)"
fi
case "$version" in
  v*.*) ;;
  *)
    echo "Could not resolve the app version from $workdir/app/build.gradle" >&2
    exit 1
    ;;
esac
echo "Capturing screenshots named $version-*.png"

mkdir -p "$output_dir" "$(dirname "$gradle_log")"
(
  cd "$workdir"
  ./gradlew :app:connectedDebugAndroidTest \
    -PinstrumentedTestAbi=x86_64 \
    -Pandroid.testInstrumentationRunnerArguments.class="$screenshot_test" \
    --no-configuration-cache --stacktrace
) >"$gradle_log" 2>&1 &
gradle_pid=$!

for _ in $(seq 1 900); do
  for name in task-bar tool-bar ai-providers github termux-linux plugin-platform remote-platform agent-platforms; do
    target="$output_dir/$version-$name.png"
    if [ ! -s "$target" ]; then
      temp="$target.tmp"
      rm -f "$temp"
      if adb shell pm path "$package_name" >/dev/null 2>&1; then
        adb exec-out run-as "$package_name" cat "files/visual-evidence/$version-$name.png" >"$temp" 2>/dev/null || true
        if [ "$(head -c 8 "$temp" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a" ]; then
          mv "$temp" "$target"
        else
          rm -f "$temp"
        fi
      fi
    fi
  done
  if kill -0 "$gradle_pid" 2>/dev/null; then
    sleep 1
  else
    break
  fi
done

set +e
wait "$gradle_pid"
gradle_status=$?
set -e
cat "$gradle_log"
test "$gradle_status" -eq 0

for name in task-bar tool-bar ai-providers github termux-linux plugin-platform remote-platform; do
  test "$(head -c 8 "$output_dir/$version-$name.png" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a"
done

# agent-platforms only exists in trees that have the platform center, so the v3.10.5 baseline
# cannot produce it. Accept either outcome, but reject a zero-byte or non-PNG file if present.
optional="$output_dir/$version-agent-platforms.png"
if [ -s "$optional" ]; then
  test "$(head -c 8 "$optional" | od -An -tx1 | tr -d ' \n')" = "89504e470d0a1a0a"
fi
