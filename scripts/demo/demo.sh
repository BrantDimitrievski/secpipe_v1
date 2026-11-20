#!/usr/bin/env bash
# Scripted demo: no-AI, fake LLM, Docker-confirmed evidence (fixtures/e2e/sqli-min), then a verified fix
# (fixtures/remediation/sqli-fixable).
# Usage: bash scripts/demo/demo.sh [--no-docker] [--out DIR] [--jar FILE]
#        bash scripts/demo/demo.sh --check-fixed OUT_DIR   (only the step 4 assertions on an existing run)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$ROOT/fixtures/e2e/sqli-min"
FIXABLE="$ROOT/fixtures/remediation/sqli-fixable"
JAR="$ROOT/secpipe-cli/target/secpipe.jar"
OUT="$ROOT/secpipe-demo"
DOCKER=1
CHECK_FIXED=""

# Step 4 assertions: the report's Remediation section has a "fixed" outcome and the verified patch is non-empty.
check_fixed() {
    local dir="$1"
    if ! test -s "$dir/report.md" || ! sed -n '/^## Remediation/,/^## Coverage/p' "$dir/report.md" \
            | grep -q '^- Outcome: fixed$'; then
        echo "error: $dir/report.md has no Remediation entry with outcome 'fixed'" >&2
        return 1
    fi
    if ! test -s "$dir/secpipe-fixes.patch"; then
        echo "error: $dir/secpipe-fixes.patch is missing or empty" >&2
        return 1
    fi
    echo "fixed: $dir/report.md (Remediation outcome fixed), $dir/secpipe-fixes.patch"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --no-docker) DOCKER=0 ;;
        --check-fixed|--out|--jar)
            if [ $# -lt 2 ]; then
                echo "error: $1 needs a value" >&2
                echo "hint: usage: demo.sh [--no-docker] [--out DIR] [--jar FILE] [--check-fixed DIR]" >&2
                exit 2
            fi
            case "$1" in
                --out) OUT="$2" ;;
                --jar) JAR="$2" ;;
                *) CHECK_FIXED="$2" ;;
            esac
            shift ;;
        *) echo "error: unknown option $1" >&2; echo "hint: usage: demo.sh [--no-docker] [--out DIR] [--jar FILE] [--check-fixed DIR]" >&2; exit 2 ;;
    esac
    shift
done

if [ -n "$CHECK_FIXED" ]; then
    check_fixed "$CHECK_FIXED"
    exit $?
fi

if [ ! -f "$JAR" ]; then
    echo "== building secpipe.jar"
    (cd "$ROOT" && ./mvnw -q -B -DskipTests package)
fi

# Exit codes 0 and 1 both mean the scan ran; anything else is a failure.
run_scan() {
    local rc=0
    "$@" || rc=$?
    if [ "$rc" -gt 1 ]; then
        echo "error: scan failed with exit code $rc" >&2
        exit "$rc"
    fi
}

echo "== 1/4 no AI (deterministic analyzers only)"
run_scan java -jar "$JAR" scan "$FIXTURE" --no-ai --no-sandbox -o "$OUT/noai"
test -s "$OUT/noai/report.md" || { echo "error: $OUT/noai/report.md missing" >&2; exit 1; }
grep -q "^### 1\." "$OUT/noai/report.md" || { echo "error: report.md has no finding" >&2; exit 1; }
java -jar "$JAR" validate findings "$OUT/noai/findings.json" --root "$FIXTURE"

echo "== 2/4 fake LLM (offline, canned responses)"
SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR="$FIXTURE/llm" \
    run_scan java -jar "$JAR" scan "$FIXTURE" --llm-provider fake --no-sandbox -o "$OUT/fake"

echo "== 3/4 sandbox evidence"
if [ "$DOCKER" -eq 0 ]; then
    echo "skipped: --no-docker"
elif ! docker info >/dev/null 2>&1; then
    echo "skipped: Docker is not available (run 'docker info' to check)"
else
    SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR="$FIXTURE/llm" \
        run_scan java -jar "$JAR" scan "$FIXTURE" --llm-provider fake -o "$OUT/sandbox"
fi

echo "== 4/4 verified fix (fixtures/remediation/sqli-fixable)"
if [ "$DOCKER" -eq 0 ]; then
    echo "skipped: --no-docker"
elif ! docker info >/dev/null 2>&1; then
    echo "skipped: Docker is not available (run 'docker info' to check)"
else
    # SECPIPE_RUNNER_IMAGE, if set (CI), is read from the environment by the CLI itself.
    SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR="$FIXABLE/llm" \
        run_scan java -jar "$JAR" scan "$FIXABLE" --llm-provider fake -o "$OUT/fix"
    check_fixed "$OUT/fix"
fi

echo "DEMO OK: reports under $OUT/*/report.md"
