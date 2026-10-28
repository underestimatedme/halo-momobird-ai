#!/usr/bin/env bash
# Builds dist/halo-momobird-ai-<version>.jar (./gradlew build, with the console UI) and checks what
# went into it: the plugin manifest, the three extension files, the console bundle, the logo and the
# plugin's own classes, nothing else; and that no connection key, not even the fake key of the test
# mock, is inside.
#
#   scripts/package.sh            (needs JDK 17: set JAVA_HOME, the default java may be older)

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
cd "$ROOT"

VERSION="$(sed -n 's/^  version: *//p' src/main/resources/plugin.yaml | head -1 | tr -d '"' | tr -d "'")"
[ -n "$VERSION" ] || { echo "package: no version in plugin.yaml" >&2; exit 1; }
NAME="halo-momobird-ai-$VERSION.jar"
OUT="dist/$NAME"

echo "package: ./gradlew build (version $VERSION)"
./gradlew build -q > /dev/null

BUILT="build/libs/$NAME"
[ -f "$BUILT" ] || { echo "package: $BUILT was not built" >&2; exit 1; }
mkdir -p dist
rm -f dist/*.jar
cp "$BUILT" "$OUT"

LISTING="$(unzip -Z1 "$OUT" | grep -v '/$' | sort)"

# Every file must be one of the allowed ones ...
UNEXPECTED="$(echo "$LISTING" | grep -v -E '^(META-INF/MANIFEST\.MF|META-INF/plugin-components\.idx|plugin\.yaml|logo\.png|console/(main\.js|style\.css)|extensions/[a-z-]+\.yaml|ai/momobird/halo/[A-Za-z0-9_$/]+\.class)$' || true)"
if [ -n "$UNEXPECTED" ]; then
  echo "package: unexpected files in $OUT:" >&2
  echo "$UNEXPECTED" >&2
  exit 1
fi

# ... and the ones the plugin cannot work without must be there.
for required in plugin.yaml logo.png console/main.js console/style.css META-INF/plugin-components.idx \
  extensions/settings.yaml extensions/extension-definitions.yaml extensions/role-templates.yaml \
  ai/momobird/halo/MomoBirdPlugin.class ai/momobird/halo/halo/SyncService.class ai/momobird/halo/halo/AdminEndpoint.class; do
  echo "$LISTING" | grep -qx "$required" || { echo "package: $required is missing from $OUT" >&2; exit 1; }
done

# The fake key of the test mock and anything shaped like a real one must not ship. The jar is
# unpacked and searched as files (piping `unzip -p` into `grep -q` is not reliable under pipefail:
# grep leaves at the first match, unzip dies of SIGPIPE).
KEY_PATTERN='HALOLOCALTESTKEY|mbk_[A-Za-z0-9_-]{16,}'

# key_files JAR   prints the files inside it that hold a key-like string.
key_files() {
  local dir hits
  dir="$(mktemp -d)"
  unzip -q "$1" -d "$dir"
  hits="$(cd "$dir" && grep -rlE "$KEY_PATTERN" . | sort || true)"
  rm -rf "$dir"
  echo "$hits"
}

FOUND="$(key_files "$OUT")"
if [ -n "$FOUND" ]; then
  echo "package: a key-like string is inside $OUT:" >&2
  echo "$FOUND" >&2
  exit 1
fi

# Self-test of the guard, on copies in a scratch directory (the jar in dist/ is never touched): the
# same files with a fake key planted in one place at a time must all be caught.
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
mkdir "$SCRATCH/src"
unzip -q "$OUT" -d "$SCRATCH/src"
PLANTED=0
for target in plugin.yaml extensions/settings.yaml console/main.js logo.png ai/momobird/halo/MomoBirdPlugin.class; do
  rm -rf "$SCRATCH/planted" "$SCRATCH/planted.jar"
  cp -R "$SCRATCH/src" "$SCRATCH/planted"
  printf '\n// mbk_PLANTEDFAKEKEYPLANTEDFAKEKEY0123456789\n' >> "$SCRATCH/planted/$target"
  (cd "$SCRATCH/planted" && zip -q -r ../planted.jar .)
  CAUGHT="$(key_files "$SCRATCH/planted.jar")"
  if [ "$CAUGHT" != "./$target" ]; then
    echo "package: the key guard did not catch a key planted in $target (it reported: ${CAUGHT:-nothing})" >&2
    exit 1
  fi
  PLANTED=$((PLANTED + 1))
done
# The test mock's own fake key must be caught as well.
rm -rf "$SCRATCH/planted" "$SCRATCH/planted.jar"
cp -R "$SCRATCH/src" "$SCRATCH/planted"
printf 'mbk_HALOLOCALTESTKEYHALOLOCALTESTKEYHALOLOCALTE\n' >> "$SCRATCH/planted/plugin.yaml"
(cd "$SCRATCH/planted" && zip -q -r ../planted.jar .)
[ "$(key_files "$SCRATCH/planted.jar")" = "./plugin.yaml" ] || { echo "package: the key guard missed the mock's fake key" >&2; exit 1; }
PLANTED=$((PLANTED + 1))
echo "package: key guard self-test passed (a planted key was caught in $PLANTED of $PLANTED places); $OUT is clean"
echo

echo "$LISTING" | sed -E 's#^(ai/momobird/halo/[a-z]+/).*#\1*.class#; s#^(ai/momobird/halo/)[A-Za-z0-9_$]+\.class$#\1*.class#' | sort | uniq -c
echo
echo "package: $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes, $(echo "$LISTING" | wc -l | tr -d ' ') files)"
