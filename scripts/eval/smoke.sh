#!/usr/bin/env bash
# Cheap real-provider check before a long run (E.6, AI-on Benchmark): scans fixtures/remediation/sqli-fixable with
# the real LLM provider (quick profile, no sandbox, no fixes) and passes only if the model was really called.
#
# usage: bash scripts/eval/smoke.sh [--provider P] [--model ID] [--llm-base-url URL] [--check-env]
#   --provider P   --llm-provider value (default: anthropic); the API key is read from the environment, never a flag
#   --model ID     --model value (default: the provider's default model)
#   --llm-base-url U  --llm-base-url value (required for openai-compatible, optional for openai)
#   --check-env    run only the argument and API-key checks for the chosen provider, then exit 0 (ok) or 2; needs no
#                  jar or network. Keys: anthropic = SECPIPE_API_KEY | ANTHROPIC_API_KEY | SECPIPE_LLM_KEY;
#                  openai, openai-compatible = SECPIPE_API_KEY | OPENAI_API_KEY | SECPIPE_LLM_KEY; none needs no
#                  key; ollama is not built (use openai-compatible); fake needs SECPIPE_ALLOW_FAKE_LLM=1 and
#                  SECPIPE_FAKE_LLM_DIR
# Exit codes: 0 the provider answered (run-metadata budget.used >= 1, no "LLM unavailable"); 1 it did not; 2 usage.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$REPO_ROOT/fixtures/remediation/sqli-fixable"

die() { echo "error: $1" >&2; echo "hint: $2" >&2; exit 2; }

PROVIDER="anthropic"
MODEL=""
BASE_URL=""
CHECK_ENV=0
while [ $# -gt 0 ]; do
  case "$1" in
    --provider)
      [ $# -ge 2 ] || die "--provider needs a value" "bash scripts/eval/smoke.sh --provider anthropic"
      PROVIDER="$2"; shift 2 ;;
    --model)
      [ $# -ge 2 ] || die "--model needs a value" "bash scripts/eval/smoke.sh --model ID"
      MODEL="$2"; shift 2 ;;
    --llm-base-url)
      [ $# -ge 2 ] || die "--llm-base-url needs a value" "bash scripts/eval/smoke.sh --provider openai-compatible --llm-base-url URL"
      BASE_URL="$2"; shift 2 ;;
    --check-env) CHECK_ENV=1; shift ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1" "usage: bash scripts/eval/smoke.sh [--provider P] [--model ID] [--llm-base-url URL] [--check-env]" ;;
  esac
done

# Checks that the chosen provider can run; prints variable names only, never a value.
check_env() {
  case "$PROVIDER" in
    anthropic)
      [ -n "${SECPIPE_API_KEY:-}${ANTHROPIC_API_KEY:-}${SECPIPE_LLM_KEY:-}" ] \
        || die "no API key for provider anthropic in the environment" \
               "export SECPIPE_API_KEY, ANTHROPIC_API_KEY or SECPIPE_LLM_KEY; never pass it as a flag" ;;
    openai|openai-compatible)
      [ -n "${SECPIPE_API_KEY:-}${OPENAI_API_KEY:-}${SECPIPE_LLM_KEY:-}" ] \
        || die "no API key for provider $PROVIDER in the environment" \
               "export SECPIPE_API_KEY, OPENAI_API_KEY or SECPIPE_LLM_KEY; never pass it as a flag"
      if [ "$PROVIDER" = "openai-compatible" ] && [ -z "$BASE_URL" ]; then
        die "provider openai-compatible needs a base URL" "pass --llm-base-url URL"
      fi ;;
    ollama)
      die "provider ollama is not built" \
          "use --provider openai-compatible --llm-base-url http://localhost:11434/v1 --model <name>" ;;
    none) die "provider none makes no LLM call, so there is nothing to smoke-test" \
              "use --provider anthropic, openai or openai-compatible" ;;
    fake)
      [ "${SECPIPE_ALLOW_FAKE_LLM:-}" = "1" ] && [ -n "${SECPIPE_FAKE_LLM_DIR:-}" ] \
        || die "provider fake needs SECPIPE_ALLOW_FAKE_LLM=1 and SECPIPE_FAKE_LLM_DIR" \
               "export SECPIPE_ALLOW_FAKE_LLM=1 and SECPIPE_FAKE_LLM_DIR=<llm-fixtures dir>" ;;
    *) die "unknown provider $PROVIDER" "use one of: anthropic, openai, openai-compatible, fake" ;;
  esac
}

check_env
if [ "$CHECK_ENV" = 1 ]; then
  echo "check-env: ok (provider $PROVIDER)"
  exit 0
fi

command -v java >/dev/null || die "java is not installed" "install a JDK 21 (Temurin)"
JAR="$REPO_ROOT/secpipe-cli/target/secpipe.jar"
if [ ! -f "$JAR" ]; then
  (cd "$REPO_ROOT" && ./mvnw -q -B -pl secpipe-cli -am package -DskipTests)
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
OUT="$TMP/out"
LOG="$TMP/scan.log"

SCAN_FLAGS=(--llm-provider "$PROVIDER" --profile quick --no-sandbox --enable-fixes false --fail-on none -v)
[ -z "$BASE_URL" ] || SCAN_FLAGS+=(--llm-base-url "$BASE_URL")
[ -z "$MODEL" ] || SCAN_FLAGS+=(--model "$MODEL")

rc=0
java -jar "$JAR" scan "$FIXTURE" "${SCAN_FLAGS[@]}" -o "$OUT" >"$LOG" 2>&1 || rc=$?

fail() { echo "smoke: FAILED ($1)" >&2; echo "hint: $2" >&2; exit 1; }

echo "provider:  $PROVIDER"
echo "model:     ${MODEL:-provider default}"
[ "$rc" -eq 0 ] || fail "scan exited $rc" "see the last lines of the scan log below; check the key, model id and endpoint
$(tail -n 15 "$LOG")"
[ -s "$OUT/run-metadata.json" ] || fail "run-metadata.json was not written" "re-run with the same flags and read the scan log"

# Parse with python3 (isolated mode) or jq; whichever exists.
if command -v python3 >/dev/null; then
  USED="$(python3 -I -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["budget"]["used"])' "$OUT/run-metadata.json")"
  VERDICTS="$(python3 -I -B -c 'import json,sys
for f in json.load(open(sys.argv[1])):
    print("  " + str(f.get("verdict")) + "  " + str(f.get("title")))' "$OUT/findings.json")"
elif command -v jq >/dev/null; then
  USED="$(jq -r '.budget.used' "$OUT/run-metadata.json")"
  VERDICTS="$(jq -r '.[] | "  \(.verdict)  \(.title)"' "$OUT/findings.json")"
else
  die "neither python3 nor jq is installed" "install one of them to read run-metadata.json"
fi

echo "budget.used: $USED"
echo "findings:"
if [ -n "$VERDICTS" ]; then echo "$VERDICTS"; else echo "  (none)"; fi

if grep -rqF "LLM unavailable" "$LOG" "$OUT/findings.json" "$OUT/run-metadata.json" "$OUT/report.md" 2>/dev/null; then
  fail "the text 'LLM unavailable' appears in the scan output" \
       "the provider did not answer; check the key, model id (--model) and --llm-base-url, then re-run"
fi
case "$USED" in ''|*[!0-9]*) fail "budget.used is not a number" "re-run and read the scan log" ;; esac
[ "$USED" -ge 1 ] || fail "budget.used is 0: no agent call was made" \
  "the scan ran without calling the model; check --llm-provider and that the key is set"
echo "smoke: OK"
