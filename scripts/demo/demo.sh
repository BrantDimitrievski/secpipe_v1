#!/usr/bin/env bash
# Scripted demo on fixtures/e2e/sqli-min: no-AI, fake LLM, then Docker-confirmed evidence.
# Usage: bash scripts/demo/demo.sh [--no-docker] [--out DIR] [--jar FILE]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$ROOT/fixtures/e2e/sqli-min"
JAR="$ROOT/secpipe-cli/target/secpipe.jar"
OUT="$ROOT/secpipe-demo"
DOCKER=1

while [ $# -gt 0 ]; do
    case "$1" in
        --no-docker) DOCKER=0 ;;
        --out) OUT="$2"; shift ;;
        --jar) JAR="$2"; shift ;;
        *) echo "error: unknown option $1" >&2; echo "hint: usage: demo.sh [--no-docker] [--out DIR] [--jar FILE]" >&2; exit 2 ;;
    esac
    shift
done

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

echo "== 1/3 no AI (deterministic analyzers only)"
run_scan java -jar "$JAR" scan "$FIXTURE" --no-ai --no-sandbox -o "$OUT/noai"
test -s "$OUT/noai/report.md" || { echo "error: $OUT/noai/report.md missing" >&2; exit 1; }
grep -q "^### 1\." "$OUT/noai/report.md" || { echo "error: report.md has no finding" >&2; exit 1; }
java -jar "$JAR" validate findings "$OUT/noai/findings.json" --root "$FIXTURE"

echo "== 2/3 fake LLM (offline, canned responses)"
SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR="$FIXTURE/llm" \
    run_scan java -jar "$JAR" scan "$FIXTURE" --llm-provider fake --no-sandbox -o "$OUT/fake"

echo "== 3/3 sandbox evidence"
if [ "$DOCKER" -eq 0 ]; then
    echo "skipped: --no-docker"
elif ! docker info >/dev/null 2>&1; then
    echo "skipped: Docker is not available (run 'docker info' to check)"
else
    SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR="$FIXTURE/llm" \
        run_scan java -jar "$JAR" scan "$FIXTURE" --llm-provider fake -o "$OUT/sandbox"
fi

echo "DEMO OK: reports under $OUT/*/report.md"
