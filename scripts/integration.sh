#!/usr/bin/env bash
# Runs the plugin jar against real Halo 2.17 and 2.27 in Docker, with the local mock of MomoBird's
# connect API (dev/mock-connect.js). Nothing here talks to MomoBird.
#
#   scripts/integration.sh                       # both versions, in parallel
#   HALO_VERSIONS="2.27" scripts/integration.sh  # only some versions
#   SEQUENTIAL=1 scripts/integration.sh          # one version after the other
#   SKIP_BUILD=1 JAR=path/to.jar scripts/integration.sh
#   KEEP_WORK=1 scripts/integration.sh           # keep the temp directory (mock logs, results)
#   SKIP_BROWSER=1 scripts/integration.sh        # no console checks (they need Playwright and Chromium)
#
# Needs Docker, Node 18 or newer (fetch) and, for the console checks, Playwright: set PLAYWRIGHT_DIR
# to a node_modules/playwright directory if it is not found. Each version takes about six minutes.
# The script runs for a long time: start it in the background and wait for it.
#
# Every check prints PASS or FAIL with its evidence; one FAIL makes the script exit 1. On exit and on
# Ctrl-C (INT, TERM, HUP) every container, volume and temp directory it created is removed.

set -euo pipefail

# Gradle needs JDK 17 or newer, and the default java may be older: on macOS look one up when JAVA_HOME is not set.
java_major() {
  "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\)\.\{0,1\}\([0-9]*\).*/\1 \2/p' | head -1 | { read -r a b; [ "$a" = 1 ] && echo "$b" || echo "$a"; }
}
if [ -x /usr/libexec/java_home ] && { [ -z "${JAVA_HOME:-}" ] || [ "$(java_major "$JAVA_HOME" 2> /dev/null || echo 0)" -lt 17 ]; }; then
  JDK17="$(/usr/libexec/java_home -v 17 2> /dev/null || true)"
  if [ -n "$JDK17" ]; then export JAVA_HOME="$JDK17"; fi
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HALO_VERSIONS="${HALO_VERSIONS:-2.17 2.27}"
RUN_ID="$(date +%s)-$$"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/halo-momobird-it.XXXXXX")"
PIDS=""

# The mock must be reachable from the container as host.docker.internal. Docker Desktop and OrbStack
# forward that to the host's loopback; on Linux it is the docker0 address, so listen everywhere there.
if [ -z "${MOCK_HOST:-}" ]; then
  if [ "$(uname -s)" = Linux ]; then MOCK_HOST=0.0.0.0; else MOCK_HOST=127.0.0.1; fi
fi

cleanup() {
  trap - EXIT
  local pid
  for pid in $PIDS; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  for pid in $PIDS; do
    wait "$pid" 2>/dev/null || true
  done
  # Belt and braces: whatever a killed run left behind.
  local leftovers
  leftovers="$(docker ps -aq --filter "label=momobird-halo-it=$RUN_ID" 2>/dev/null || true)"
  if [ -n "$leftovers" ]; then
    # shellcheck disable=SC2086
    docker rm -f -v $leftovers > /dev/null 2>&1 || true
  fi
  if [ "${KEEP_WORK:-}" = 1 ]; then
    echo "kept $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

die() {
  echo "integration: $*" >&2
  exit 1
}

command -v docker > /dev/null || die "docker is not installed"
docker info > /dev/null 2>&1 || die "the Docker daemon is not running"
command -v node > /dev/null || die "node is not installed"
[ "$(node -p 'parseInt(process.versions.node, 10)')" -ge 18 ] || die "Node 18 or newer is needed"

for v in $HALO_VERSIONS; do
  docker image inspect "halohub/halo:$v" > /dev/null 2>&1 || docker pull "halohub/halo:$v" > /dev/null || die "cannot pull halohub/halo:$v"
done

JAR="${JAR:-}"
if [ -z "$JAR" ]; then
  if [ "${SKIP_BUILD:-}" != 1 ]; then
    echo "building the plugin (./gradlew build -x test)"
    (cd "$ROOT" && ./gradlew build -x test -q > "$WORK/build.log" 2>&1) || { cat "$WORK/build.log" >&2; die "the build failed"; }
  fi
  JAR="$(ls "$ROOT"/build/libs/halo-momobird-ai-*.jar 2> /dev/null | grep -v -- '-plain' | head -1 || true)"
fi
[ -f "$JAR" ] || die "no plugin jar (build it with ./gradlew build)"
echo "plugin: $JAR ($(wc -c < "$JAR" | tr -d ' ') bytes)"
echo "work directory: $WORK"

# start_version V   starts one run in the background; $! is the node process itself, so a signal reaches it.
start_version() {
  node "$ROOT/scripts/integration.js" --version "$1" --jar "$JAR" --work "$WORK/halo-$1" --run-id "$RUN_ID" --mock-host "$MOCK_HOST" \
    > "$WORK/out-$1.log" 2>&1 &
  LAST_PID=$!
  PIDS="$PIDS $LAST_PID"
}

STATUS=0
if [ "${SEQUENTIAL:-}" = 1 ]; then
  for v in $HALO_VERSIONS; do
    start_version "$v"
    pid=$LAST_PID
    wait "$pid" || STATUS=1
    PIDS="${PIDS/ $pid/}"
    cat "$WORK/out-$v.log"
  done
else
  for v in $HALO_VERSIONS; do
    start_version "$v"
  done
  # Wait one by one so a signal is handled at once.
  for pid in $PIDS; do
    wait "$pid" || STATUS=1
  done
  PIDS=""
  for v in $HALO_VERSIONS; do
    cat "$WORK/out-$v.log"
  done
fi

echo
echo "=== summary ==="
grep -h '^RESULT' "$WORK"/out-*.log || true
TOTAL_PASS="$(cat "$WORK"/out-*.log | grep -c '^PASS' || true)"
TOTAL_FAIL="$(cat "$WORK"/out-*.log | grep -c '^FAIL' || true)"
TOTAL_SKIP="$(cat "$WORK"/out-*.log | grep -c '^SKIPPED' || true)"
grep -h '^SKIPPED' "$WORK"/out-*.log || true
echo "total: $TOTAL_PASS passed, $TOTAL_FAIL failed, $TOTAL_SKIP skipped"
if [ "$TOTAL_FAIL" -ne 0 ] || [ "$TOTAL_PASS" -eq 0 ]; then
  STATUS=1
fi
exit "$STATUS"
