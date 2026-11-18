#!/usr/bin/env bash
# Scores secpipe on the OWASP Benchmark v1.2 (CWEs 22, 78, 89, 327, 328) and writes scorecard.{json,md} + per-case.csv.
# The Benchmark is GPL-2.0: it is downloaded here at evaluation time and never committed to this repository.
#
# usage: bash scripts/eval/benchmark.sh [--no-ai] [--provider P] [--out DIR] [--dry-run]
#   --no-ai       scan with --no-ai (no LLM calls)
#   --provider P  scan with --llm-provider P (needs the API key in the environment, never a flag)
#   --out DIR     output directory (default: ./secpipe-eval-out/benchmark)
#   --dry-run     print the plan (pinned commit, scan command, deny-path) and change nothing
set -euo pipefail

BENCHMARK_REPO="https://github.com/OWASP-Benchmark/BenchmarkJava"
BENCHMARK_COMMIT="a452b3aafd232245d0753e54eb243d6651f8ef96"   # master at pin time; the answer key is expectedresults-1.2.csv
EVAL_HOME="$HOME/.secpipe/eval"
BENCH_DIR="$EVAL_HOME/benchmark"
EXPECTED_DIR="$EVAL_HOME/expected"                            # outside the scanned tree
EXPECTED_FILE="expectedresults-1.2.csv"
DENY_GLOB="**/expectedresults*.csv"

die() { echo "error: $1" >&2; echo "hint: $2" >&2; exit 2; }

AI_FLAGS=()
MODE="ai-default"
OUT="./secpipe-eval-out/benchmark"
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --no-ai) AI_FLAGS=(--no-ai); MODE="no-ai"; shift ;;
    --provider)
      [ $# -ge 2 ] || die "--provider needs a value" "bash scripts/eval/benchmark.sh --provider openai-compatible"
      AI_FLAGS=(--llm-provider "$2"); MODE="provider:$2"; shift 2 ;;
    --out)
      [ $# -ge 2 ] || die "--out needs a value" "bash scripts/eval/benchmark.sh --out DIR"
      OUT="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option $1" "usage: bash scripts/eval/benchmark.sh [--no-ai] [--provider P] [--out DIR] [--dry-run]" ;;
  esac
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCAN_CMD=(java -jar "$REPO_ROOT/secpipe-cli/target/secpipe.jar" scan "$BENCH_DIR"
  "${AI_FLAGS[@]}" --fail-on none --deny-path "$DENY_GLOB" --format json,sarif -o "$OUT/scan")
SCORE_CMD=(java -cp "$REPO_ROOT/secpipe-eval/target/secpipe-eval.jar" ca.carleton.secpipe.eval.benchmark.BenchmarkScorecard
  --expected "$EXPECTED_DIR/$EXPECTED_FILE" --findings "$OUT/scan/findings.json" --sarif "$OUT/scan/secpipe.sarif" --out "$OUT")

if [ "$DRY" = 1 ]; then
  echo "dry run (nothing is downloaded or changed)"
  echo "benchmark repo:   $BENCHMARK_REPO"
  echo "pinned commit:    $BENCHMARK_COMMIT"
  echo "checkout dir:     $BENCH_DIR"
  echo "mode:             $MODE"
  echo "deny-path:        $EXPECTED_DIR/$EXPECTED_FILE (moved out of the scanned tree; scan also gets --deny-path '$DENY_GLOB')"
  echo "scan command:     ${SCAN_CMD[*]}"
  echo "scorecard command: ${SCORE_CMD[*]}"
  exit 0
fi

command -v git >/dev/null || die "git is not installed" "install git"
command -v java >/dev/null || die "java is not installed" "install a JDK 21 (Temurin)"

# 1. fetch the Benchmark at the pinned commit (shallow, never vendored)
if [ ! -d "$BENCH_DIR/.git" ] || [ "$(git -C "$BENCH_DIR" rev-parse HEAD 2>/dev/null || true)" != "$BENCHMARK_COMMIT" ]; then
  rm -rf "$BENCH_DIR"
  mkdir -p "$BENCH_DIR"
  git -C "$BENCH_DIR" init -q
  git -C "$BENCH_DIR" remote add origin "$BENCHMARK_REPO"
  git -C "$BENCH_DIR" fetch -q --depth 1 origin "$BENCHMARK_COMMIT"
  git -C "$BENCH_DIR" checkout -q FETCH_HEAD
fi

# 2. move the answer key out of the scanned tree
mkdir -p "$EXPECTED_DIR"
for f in "$BENCH_DIR"/expectedresults*.csv; do
  [ -e "$f" ] || continue
  mv "$f" "$EXPECTED_DIR/$(basename "$f")"
done
[ -f "$EXPECTED_DIR/$EXPECTED_FILE" ] || die "$EXPECTED_FILE not found in the Benchmark checkout or $EXPECTED_DIR" \
  "check that BENCHMARK_COMMIT in this script still carries $EXPECTED_FILE"

# 3. build the jars if needed
if [ ! -f "$REPO_ROOT/secpipe-cli/target/secpipe.jar" ] || [ ! -f "$REPO_ROOT/secpipe-eval/target/secpipe-eval.jar" ]; then
  (cd "$REPO_ROOT" && ./mvnw -q -B -pl secpipe-cli,secpipe-eval -am package -DskipTests)
fi

# 4. scan, then score
mkdir -p "$OUT"
"${SCAN_CMD[@]}"
"${SCORE_CMD[@]}"
echo "scorecard: $OUT/scorecard.md"
