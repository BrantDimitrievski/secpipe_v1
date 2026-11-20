#!/usr/bin/env bash
# Runs the full pipeline with --enable-fixes suggest (sandbox on) on both MVP sample apps and renders the
# remediation scorecard docs/eval/remediation.md (WO-E.6). Needs Docker and an LLM API key in the environment.
#
# usage: bash scripts/eval/remediation.sh [--provider P] [--model ID] [--out DIR] [--dry-run]
#        bash scripts/eval/remediation.sh --from <findings.json>... [--patch-base PREFIX]
#   --provider P   --llm-provider value (default: anthropic); the API key is read from the environment, never a flag
#   --model ID     --model value (use a frontier model for the real run)
#   --out DIR      per-app scan output (default: ./secpipe-eval-out/remediation/<app>/)
#   --from FILES   skip the scans and re-render the scorecard from existing findings.json files; each file's
#                  parent directory name is its app name
#   --patch-base P prefix for patch links in the scorecard (default: path from docs/eval to the scan output)
#   --dry-run      print the plan and change nothing
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APPS=(vulnerable-java-app shop-samples)
SCORECARD="$REPO_ROOT/docs/eval/remediation.md"

die() { echo "error: $1" >&2; echo "hint: $2" >&2; exit 2; }

PROVIDER="anthropic"
MODEL=""
OUT="$REPO_ROOT/secpipe-eval-out/remediation"
PATCH_BASE=""
DRY=0
FROM=()
while [ $# -gt 0 ]; do
  case "$1" in
    --provider)
      [ $# -ge 2 ] || die "--provider needs a value" "bash scripts/eval/remediation.sh --provider anthropic"
      PROVIDER="$2"; shift 2 ;;
    --model)
      [ $# -ge 2 ] || die "--model needs a value" "bash scripts/eval/remediation.sh --model ID"
      MODEL="$2"; shift 2 ;;
    --out)
      [ $# -ge 2 ] || die "--out needs a value" "bash scripts/eval/remediation.sh --out DIR"
      OUT="$2"; shift 2 ;;
    --patch-base)
      [ $# -ge 2 ] || die "--patch-base needs a value" "bash scripts/eval/remediation.sh --from F... --patch-base PREFIX"
      PATCH_BASE="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    --from)
      shift
      while [ $# -gt 0 ] && [[ "$1" != --* ]]; do FROM+=("$1"); shift; done
      [ ${#FROM[@]} -gt 0 ] || die "--from needs at least one findings.json" "bash scripts/eval/remediation.sh --from out/app/findings.json" ;;
    -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1" "usage: bash scripts/eval/remediation.sh [--provider P] [--model ID] [--out DIR] [--dry-run] | --from FILE..." ;;
  esac
done

if [ -z "$PATCH_BASE" ]; then
  if [ ${#FROM[@]} -gt 0 ]; then PATCH_BASE="$(dirname "$(dirname "${FROM[0]}")")"; else PATCH_BASE="$OUT"; fi
  PATCH_BASE="$(realpath -m --relative-to="$REPO_ROOT/docs/eval" "$PATCH_BASE")"
fi

RENDER_CMD=(java -cp "$REPO_ROOT/secpipe-eval/target/secpipe-eval.jar" ca.carleton.secpipe.eval.remediation.RemediationScorecard
  --patch-base "$PATCH_BASE" --out "$SCORECARD")

build_jars() {
  command -v java >/dev/null || die "java is not installed" "install a JDK 21 (Temurin)"
  if [ ! -f "$REPO_ROOT/secpipe-cli/target/secpipe.jar" ] || [ ! -f "$REPO_ROOT/secpipe-eval/target/secpipe-eval.jar" ]; then
    (cd "$REPO_ROOT" && ./mvnw -q -B -pl secpipe-cli,secpipe-eval -am package -DskipTests)
  fi
}

if [ ${#FROM[@]} -gt 0 ]; then
  for f in "${FROM[@]}"; do [ -f "$f" ] || die "$f not found" "pass findings.json files written by secpipe scan"; done
  [ "$DRY" = 1 ] && { echo "dry run: would render $SCORECARD from ${FROM[*]}"; exit 0; }
  build_jars
  "${RENDER_CMD[@]}" "${FROM[@]}"
  echo "scorecard: $SCORECARD"
  exit 0
fi

SCAN_FLAGS=(--llm-provider "$PROVIDER" --enable-fixes suggest --fail-on none --format "json,sarif,markdown")
[ -z "$MODEL" ] || SCAN_FLAGS+=(--model "$MODEL")   # sandbox stays on: --no-sandbox is never passed

if [ "$DRY" = 1 ]; then
  echo "dry run (nothing is built, scanned or written)"
  for app in "${APPS[@]}"; do
    echo "scan:   java -jar secpipe-cli/target/secpipe.jar scan fixtures/mvp-samples/$app ${SCAN_FLAGS[*]} -o $OUT/$app"
  done
  echo "render: ${RENDER_CMD[*]} $OUT/*/findings.json"
  exit 0
fi

command -v docker >/dev/null && docker info >/dev/null 2>&1 \
  || die "Docker is not available; the sandbox is required for remediation" "start Docker, then run: secpipe doctor"
if [ -z "${SECPIPE_API_KEY:-}${ANTHROPIC_API_KEY:-}${OPENAI_API_KEY:-}${SECPIPE_LLM_KEY:-}" ]; then
  die "no LLM API key in the environment" "export SECPIPE_API_KEY (or ANTHROPIC_API_KEY / OPENAI_API_KEY); never pass it as a flag"
fi
build_jars

OUTS=()
for app in "${APPS[@]}"; do
  mkdir -p "$OUT/$app"
  java -jar "$REPO_ROOT/secpipe-cli/target/secpipe.jar" scan "$REPO_ROOT/fixtures/mvp-samples/$app" "${SCAN_FLAGS[@]}" -o "$OUT/$app"
  OUTS+=("$OUT/$app/findings.json")
done
"${RENDER_CMD[@]}" "${OUTS[@]}"
echo "scorecard: $SCORECARD"
