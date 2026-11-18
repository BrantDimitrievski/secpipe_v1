#!/usr/bin/env bash
# Runs the no-AI commands from docs/user/guide.md on the sqli-min fixture and checks that every
# --flag the guide mentions exists in `secpipe scan --help`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${SECPIPE_JAR:-$ROOT/secpipe-cli/target/secpipe.jar}"
FIXTURE="$ROOT/fixtures/e2e/sqli-min"
GUIDE="$ROOT/docs/user/guide.md"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if [ ! -f "$JAR" ]; then
    (cd "$ROOT" && ./mvnw -q -B -DskipTests package)
fi

java -jar "$JAR" --version
java -jar "$JAR" --help >/dev/null

help_text="$(java -jar "$JAR" scan --help)"
# Documented as accepted-but-unused or as exit-2 in the Limitations section; not required in the options table.
for flag in $(grep -oE '`--[a-z][a-z-]+' "$GUIDE" | tr -d '`' | sort -u); do
    case "$flag" in
        --version|--help|--root) continue ;;
    esac
    if ! grep -q -e "$flag" <<<"$help_text"; then
        echo "error: guide mentions $flag but 'scan --help' does not list it" >&2
        exit 1
    fi
done

rc=0
java -jar "$JAR" scan "$FIXTURE" --no-ai --no-sandbox -o "$TMP/out" || rc=$?
if [ "$rc" -ne 0 ]; then
    echo "error: no-AI scan exited $rc, expected 0" >&2
    exit 1
fi
for f in findings.json coverage-ledger.json run-metadata.json secpipe.sarif report.md; do
    test -s "$TMP/out/$f" || { echo "error: $f was not written" >&2; exit 1; }
done
java -jar "$JAR" validate findings "$TMP/out/findings.json" --root "$FIXTURE"
echo "snippets OK"
