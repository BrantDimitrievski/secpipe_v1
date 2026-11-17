#!/usr/bin/env bash
# End-to-end fixture scan with the fake LLM (CLAUDE.md §2). Required once ci/fixture-scan.enabled exists (WO-0.2).
set -euo pipefail
if [[ ! -f ci/fixture-scan.enabled ]]; then
  echo "fixture-scan: skipped: not enabled (created by WO-0.2)"; exit 0
fi
JAR=secpipe-cli/target/secpipe.jar
FIX=fixtures/e2e/sqli-min
OUT=secpipe-out-e2e
[[ -f "$JAR" ]] || ./mvnw -q -B -DskipTests package
rm -rf "$OUT"
set +e
SECPIPE_ALLOW_FAKE_LLM=1 SECPIPE_FAKE_LLM_DIR="$FIX/llm" \
  java -jar "$JAR" scan "$FIX" --profile quick --llm-provider fake --no-sandbox -o "$OUT" -q
rc=$?
set -e
[[ $rc -eq 0 || $rc -eq 1 ]] || { echo "fixture-scan: FAIL (scan exit $rc)"; exit 1; }
java -jar "$JAR" validate findings "$OUT/findings.json" --root "$FIX"
java -jar "$JAR" validate ledger "$OUT/coverage-ledger.json"
java -jar "$JAR" validate run "$OUT/run-metadata.json"
c=$(jq '[.[]|select(.verdict=="confirmed")]|length' "$OUT/findings.json")
n=$(jq '[.[]|select(.verdict=="needs_validation")]|length' "$OUT/findings.json")
r=$(jq '[.[]|select(.verdict=="rejected")]|length' "$OUT/findings.json")
t=$(jq -r '.terminal_state' "$OUT/run-metadata.json")
want=$(jq -c '.fake_no_sandbox' "$FIX/expected.json")
got=$(jq -nc --argjson c "$c" --argjson n "$n" --argjson r "$r" --arg t "$t" '{confirmed:$c,needs_validation:$n,rejected:$r,terminal_state:$t}')
if [[ "$(jq -S . <<< "$want")" != "$(jq -S . <<< "$got")" ]]; then
  echo "fixture-scan: FAIL expected $want got $got"; exit 1
fi
echo "fixture-scan: PASS (confirmed=$c needs_validation=$n rejected=$r $t)"
