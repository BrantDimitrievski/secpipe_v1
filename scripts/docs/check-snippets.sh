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
# Skip flags of other commands: --root (validate), --no-docker (demo.sh).
for flag in $(grep -oE '`--[a-z][a-z-]+' "$GUIDE" | tr -d '`' | sort -u); do
    case "$flag" in
        --version|--help|--root|--no-docker) continue ;;
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

# Fix flags shown in the guide: --enable-fixes false and --max-fix-attempts run; --enable-fixes pr exits 2 with a hint.
rc=0
java -jar "$JAR" scan "$FIXTURE" --no-ai --no-sandbox --enable-fixes false --max-fix-attempts 2 -o "$TMP/nofix" || rc=$?
if [ "$rc" -ne 0 ]; then
    echo "error: scan with --enable-fixes false exited $rc, expected 0" >&2
    exit 1
fi
rc=0
pr_err="$(java -jar "$JAR" scan "$FIXTURE" --no-ai --no-sandbox --enable-fixes pr -o "$TMP/pr" 2>&1 >/dev/null)" || rc=$?
if [ "$rc" -ne 2 ] || ! grep -q '^hint:' <<<"$pr_err"; then
    echo "error: --enable-fixes pr should exit 2 with a hint (exit $rc): $pr_err" >&2
    exit 1
fi
# The guide tells users to run "git apply secpipe-fixes.patch" from the project root: check that form on a copy.
cp -r "$ROOT/fixtures/remediation/sqli-fixable" "$TMP/fixable"
(cd "$TMP/fixable" && git apply --check fix/prepared-statement.patch)
echo "snippets OK"
