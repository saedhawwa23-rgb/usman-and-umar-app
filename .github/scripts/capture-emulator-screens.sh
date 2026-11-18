#!/bin/sh
set -eu

version="$1"
output_dir="$2"
gradle_log="$3"
workdir="$4"
package_name="${PACKAGE_NAME:?PACKAGE_NAME is required}"
screenshot_test="${SCREENSHOT_TEST:?SCREENSHOT_TEST is required}"

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
  for name in task-bar tool-bar ai-providers github termux-linux plugin-platform remote-platform; do
    target="$output_dir/$version-$name.png"
    if [ ! -s "$target" ]; then
      adb exec-out run-as "$package_name" cat "files/visual-evidence/$version-$name.png" >"$target" 2>/dev/null || rm -f "$target"
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
  test -s "$output_dir/$version-$name.png"
done
