#!/usr/bin/env bash
# WO-S.2 spike: probe the GitHub Copilot CLI as a non-interactive, tool-less LLM backend.
#
# What it records (one row per case in "$PROBE_OUT/results.tsv", rendered to "$PROBE_OUT/summary.md"):
#   - CLI version and whether the documented non-interactive flags exist in `copilot --help`;
#   - which auth variable is present (names only, never values) and whether the CLI authenticates;
#   - a {"ok":true} round trip from an EMPTY temp dir with -p/-s/--no-ask-user and no --allow-tool:
#     exit code, latency, stdout bytes and stdout shape (clean JSON, fenced, text, ...);
#   - the same with --output-format json, to see the JSONL event types and any usage/token fields;
#   - prompt-size cases (8k/32k/100k chars) via argv and via stdin, plus a 200k argv case that shows the
#     Linux per-argument limit (MAX_ARG_STRLEN = 128 KiB) before any request is made;
#   - the no-tools guarantee: the model is asked to read a random canary file outside the empty dir,
#     /etc/hostname and `uname -r`; if any of those values shows up in the output, tools ran;
#   - whether models.github.ai still serves inference (GitHub Models retirement evidence).
#
# In GitHub Actions it also emits ::notice annotations (readable through the check-runs API) and appends a
# table to $GITHUB_STEP_SUMMARY. It always exits 0 after a completed probe: it is evidence, not a gate.
#
# Environment (all optional):
#   COPILOT_CLI_VERSION  npm version of @github/copilot to install when PROBE_INSTALL=1 (default 1.0.112)
#   PROBE_INSTALL        1 = npm install -g the pinned CLI if `copilot` is missing (default 1)
#   PROBE_LABEL          label for annotations and the summary heading (default "local")
#   PROBE_OUT            output directory (default ./copilot-probe-out)
#   PROBE_TIMEOUT        seconds per CLI call (default 240)
#   PROBE_MODEL          passed as --model when set
#   PROBE_MODELS_CHECK   1 = also probe models.github.ai (default 1)
#   PROBE_SECTIONS       comma list: core (auth, help, ok, sizes, JSONL) and/or tools (no-tools cases).
#                        Default "core,tools". "tools" alone reuses the auth state saved by an earlier "core" run
#                        in the same PROBE_OUT (lets CI split annotations over two steps; GitHub keeps 10 per step).
#   COPILOT_GITHUB_TOKEN / GH_TOKEN / GITHUB_TOKEN  read by the CLI itself (precedence in that order)
set -uo pipefail

COPILOT_CLI_VERSION="${COPILOT_CLI_VERSION:-1.0.112}"
PROBE_INSTALL="${PROBE_INSTALL:-1}"
PROBE_LABEL="${PROBE_LABEL:-local}"
PROBE_OUT="${PROBE_OUT:-$PWD/copilot-probe-out}"
PROBE_TIMEOUT="${PROBE_TIMEOUT:-240}"
PROBE_MODEL="${PROBE_MODEL:-}"
PROBE_MODELS_CHECK="${PROBE_MODELS_CHECK:-1}"
PROBE_SECTIONS="${PROBE_SECTIONS:-core,tools}"

OK_INSTRUCTION='Reply with exactly {"ok":true} and nothing else: no code fences, no prose.'
RESULTS=""
AUTH_STATE="unknown"
NOTOOLS_VERDICT="not-run"

mkdir -p "$PROBE_OUT/cases" "$PROBE_OUT/prompts"
PROBE_OUT="$(cd "$PROBE_OUT" && pwd)"
RESULTS="$PROBE_OUT/results.tsv"
[[ -f "$RESULTS" ]] || \
    printf 'case\ttransport\tprompt_chars\texit\tlatency_ms\tstdout_bytes\tstdout_shape\tnote\n' > "$RESULTS"

in_actions() { [[ "${GITHUB_ACTIONS:-}" == "true" ]]; }

# Escape a value for a workflow command (::notice ...::value).
wc_escape() {
    local s="$1"
    s="${s//'%'/'%25'}"
    s="${s//$'\r'/'%0D'}"
    s="${s//$'\n'/'%0A'}"
    printf '%s' "$s"
}

notice() {
    local title="$1" msg="$2"
    if in_actions; then
        printf '::notice title=%s::%s\n' "$(wc_escape "S2 $PROBE_LABEL: $title")" "$(wc_escape "$msg")"
    else
        printf 'NOTICE [%s] %s: %s\n' "$PROBE_LABEL" "$title" "$msg" >&2
    fi
}

now_ms() { date +%s%3N; }

# First line of a file, control characters removed, truncated. Used only for CLI error text.
first_line() {
    local f="$1" max="${2:-200}"
    [[ -s "$f" ]] || { printf '(empty)'; return; }
    grep -m1 -v '^[[:space:]]*$' "$f" | tr -d '\000-\010\013-\037' | cut -c1-"$max"
}

# Classify what the CLI printed on stdout.
stdout_shape() {
    local f="$1" trimmed
    if [[ ! -s "$f" ]]; then printf 'empty'; return; fi
    trimmed="$(tr -d '\r' < "$f" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' | sed '/^$/d')"
    if [[ "$trimmed" == '{"ok":true}' || "$trimmed" == '{"ok": true}' ]]; then
        printf 'clean-json-exact'
    elif grep -q '^```' "$f"; then
        printf 'fenced'
    elif command -v jq > /dev/null && jq -e -s 'length > 1' "$f" > /dev/null 2>&1; then
        printf 'jsonl(%s-lines)' "$(wc -l < "$f" | tr -d ' ')"
    elif command -v jq > /dev/null && printf '%s' "$trimmed" | jq -e . > /dev/null 2>&1; then
        printf 'json-other'
    else
        printf 'text'
    fi
}

looks_like_auth_failure() {
    grep -qiE 'auth|log ?in|token|unauthori|forbidden|401|403|licen[cs]e|entitle|subscription|no copilot|policy' "$@"
}

record() {
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >> "$RESULTS"
}

# Build a prompt of exactly N characters: filler, then the instruction at the end.
make_prompt() {
    local n="$1" instruction="$2" file="$3" filler_len
    filler_len=$(( n - ${#instruction} - 1 ))
    if (( filler_len > 0 )); then
        { yes 'Filler line for a prompt size probe; ignore it.' | tr '\n' ' ' | head -c "$filler_len"; } > "$file"
        printf '\n%s' "$instruction" >> "$file"
    else
        printf '%s' "$instruction" > "$file"
    fi
}

model_args() {
    if [[ -n "$PROBE_MODEL" ]]; then printf -- '--model\n%s\n' "$PROBE_MODEL"; fi
}

# run_case <name> <argv|stdin> <prompt-file> [extra copilot flags...]
# Runs the CLI from a fresh empty temp dir. Prints "exit latency" on stdout.
run_case() {
    local name="$1" transport="$2" prompt_file="$3"
    shift 3
    local workdir out err rc start end
    local -a margs=()
    mapfile -t margs < <(model_args)
    workdir="$(mktemp -d)"
    out="$PROBE_OUT/cases/$name.out"
    err="$PROBE_OUT/cases/$name.err"
    start="$(now_ms)"
    if [[ "$transport" == "argv" ]]; then
        (cd "$workdir" && timeout "$PROBE_TIMEOUT" copilot -p "$(cat "$prompt_file")" --no-ask-user \
            "${margs[@]}" "$@" < /dev/null > "$out" 2> "$err")
        rc=$?
    else
        (cd "$workdir" && timeout "$PROBE_TIMEOUT" copilot --no-ask-user "${margs[@]}" "$@" \
            < "$prompt_file" > "$out" 2> "$err")
        rc=$?
    fi
    end="$(now_ms)"
    # The working directory must still be empty: nothing may have been written into it.
    if [[ -n "$(ls -A "$workdir")" ]]; then
        printf 'workdir-not-empty\n' >> "$err"
    fi
    rmdir "$workdir" 2> /dev/null || true
    printf '%s %s\n' "$rc" "$(( end - start ))"
}

section_help() {
    local version flags_present="" flags_missing="" f model_line
    version="$(copilot --version 2> /dev/null | head -n1)"
    copilot --help > "$PROBE_OUT/help.txt" 2>&1 < /dev/null || true
    for topic in environment permissions limits billing config; do
        timeout 60 copilot help "$topic" > "$PROBE_OUT/help-$topic.txt" 2>&1 < /dev/null || true
    done
    for f in --prompt --silent --no-ask-user --allow-tool --deny-tool --available-tools --excluded-tools \
        --output-format --model --trust-folder --max-ai-credits; do
        if grep -q -- "$f" "$PROBE_OUT/help.txt"; then
            flags_present+="$f "
        else
            flags_missing+="$f "
        fi
    done
    model_line="$(grep -A3 -- '--model' "$PROBE_OUT/help.txt" | tr -s ' \n' ' ' | cut -c1-700)"
    record help - - 0 - "$(wc -c < "$PROBE_OUT/help.txt" | tr -d ' ')" text \
        "version=${version:-unknown}; missing=${flags_missing:-none}"
    notice "CLI version and flags" "version=${version:-unknown} (pin ${COPILOT_CLI_VERSION}). present: ${flags_present:-none}. missing: ${flags_missing:-none}"
    # An unknown model id is rejected locally or by the service; the error text lists the valid ids.
    local res rc
    printf '%s' "$OK_INSTRUCTION" > "$PROBE_OUT/prompts/ok.txt"
    res="$(PROBE_MODEL=secpipe-no-such-model run_case invalid-model argv "$PROBE_OUT/prompts/ok.txt" -s)"
    rc="${res% *}"
    record invalid-model argv - "$rc" "${res#* }" - - \
        "$(tr -s '\n' ' ' < "$PROBE_OUT/cases/invalid-model.err" | tr -d '\000-\010\013-\037' | cut -c1-600)"
    notice "model ids" "--help: ${model_line:-(none)} || --model secpipe-no-such-model exit=$rc: $(tr -s '\n' ' ' \
        < "$PROBE_OUT/cases/invalid-model.err" | tr -d '\000-\010\013-\037' | cut -c1-700)"
}

section_auth_context() {
    local vars=""
    [[ -n "${COPILOT_GITHUB_TOKEN:-}" ]] && vars+="COPILOT_GITHUB_TOKEN "
    [[ -n "${GH_TOKEN:-}" ]] && vars+="GH_TOKEN "
    [[ -n "${GITHUB_TOKEN:-}" ]] && vars+="GITHUB_TOKEN "
    printf '%s' "${vars:-none}"
}

section_ok() {
    local prompt="$PROBE_OUT/prompts/ok.txt" res rc ms shape note
    printf '%s' "$OK_INSTRUCTION" > "$prompt"
    res="$(run_case ok-argv-silent argv "$prompt" -s)"
    rc="${res% *}"; ms="${res#* }"
    shape="$(stdout_shape "$PROBE_OUT/cases/ok-argv-silent.out")"
    note="stderr: $(first_line "$PROBE_OUT/cases/ok-argv-silent.err")"
    if [[ "$rc" == "0" ]]; then
        AUTH_STATE="authenticated"
    elif looks_like_auth_failure "$PROBE_OUT/cases/ok-argv-silent.out" "$PROBE_OUT/cases/ok-argv-silent.err"; then
        AUTH_STATE="no-auth"
    else
        AUTH_STATE="error"
    fi
    printf '%s\n' "$AUTH_STATE" > "$PROBE_OUT/auth_state"
    record ok argv "${#OK_INSTRUCTION}" "$rc" "$ms" "$(wc -c < "$PROBE_OUT/cases/ok-argv-silent.out" | tr -d ' ')" \
        "$shape" "$note"
    notice "ok round trip (-p -s --no-ask-user, empty dir)" \
        "auth=$AUTH_STATE exit=$rc latency_ms=$ms stdout_shape=$shape stdout=$(first_line "$PROBE_OUT/cases/ok-argv-silent.out" 120) | $note"
}

section_ok_jsonl() {
    local prompt="$PROBE_OUT/prompts/ok.txt" res rc ms out types usage
    res="$(run_case ok-argv-jsonl argv "$prompt" --output-format json)"
    rc="${res% *}"; ms="${res#* }"
    out="$PROBE_OUT/cases/ok-argv-jsonl.out"
    types="$(jq -r '.type // empty' "$out" 2> /dev/null | sort | uniq -c | awk '{printf "%s=%s ", $2, $1}')"
    usage="$(grep -oE '"[A-Za-z_]*([Tt]oken|[Cc]redit|[Pp]remium|[Uu]sage)[A-Za-z_]*" *: *[0-9.]+' "$out" \
        | sort -u | head -n 12 | tr '\n' ' ')"
    local model
    model="$(jq -c 'select(.type == "session.model_change" or .type == "session.auto_mode_resolved") | .data' \
        "$out" 2> /dev/null | tr '\n' ' ' | cut -c1-300)"
    record ok-jsonl argv "${#OK_INSTRUCTION}" "$rc" "$ms" "$(wc -c < "$out" | tr -d ' ')" "$(stdout_shape "$out")" \
        "types: ${types:-none}; usage: ${usage:-none}"
    notice "ok round trip --output-format json" "exit=$rc latency_ms=$ms model: ${model:-none}. event types: ${types:-none}. usage fields: ${usage:-none}"
}

section_sizes() {
    local n transport res rc ms prompt shape summary_argv="" summary_stdin=""
    for n in 8000 32000 100000; do
        prompt="$PROBE_OUT/prompts/size-$n.txt"
        make_prompt "$n" "$OK_INSTRUCTION" "$prompt"
        for transport in argv stdin; do
            res="$(run_case "size-$n-$transport" "$transport" "$prompt" -s)"
            rc="${res% *}"; ms="${res#* }"
            shape="$(stdout_shape "$PROBE_OUT/cases/size-$n-$transport.out")"
            record "size-$n" "$transport" "$(wc -c < "$prompt" | tr -d ' ')" "$rc" "$ms" \
                "$(wc -c < "$PROBE_OUT/cases/size-$n-$transport.out" | tr -d ' ')" "$shape" \
                "stderr: $(first_line "$PROBE_OUT/cases/size-$n-$transport.err" 160)"
            if [[ "$transport" == "argv" ]]; then
                summary_argv+="${n}:exit=${rc},${shape},${ms}ms; "
            else
                summary_stdin+="${n}:exit=${rc},${shape},${ms}ms; "
            fi
        done
    done
    notice "prompt sizes via argv" "$summary_argv"
    notice "prompt sizes via stdin" "$summary_stdin"
}

# A single argv string above MAX_ARG_STRLEN (131072 bytes on Linux) fails in execve with E2BIG.
# No request reaches GitHub, so this runs even without auth.
section_argv_kernel_limit() {
    local prompt="$PROBE_OUT/prompts/size-200000.txt" res rc ms err
    make_prompt 200000 "$OK_INSTRUCTION" "$prompt"
    res="$(run_case size-200000-argv argv "$prompt" -s)"
    rc="${res% *}"; ms="${res#* }"
    err="$(first_line "$PROBE_OUT/cases/size-200000-argv.err" 160)"
    record size-200000 argv 200000 "$rc" "$ms" "$(wc -c < "$PROBE_OUT/cases/size-200000-argv.out" | tr -d ' ')" \
        "$(stdout_shape "$PROBE_OUT/cases/size-200000-argv.out")" "stderr: $err"
    notice "argv 200000 chars (Linux MAX_ARG_STRLEN=131072)" "exit=$rc stderr: $err"
}

TOOLS_SEEN=""
LAST_VERDICT=""

# no_tools_case <variant> [extra copilot flags...]
# Asks the model to read a random canary file outside the empty working dir, /etc/hostname, and to run
# `uname -r`. If the canary, the hostname or the kernel release shows up in the output, tools ran.
no_tools_case() {
    local variant="$1"
    shift
    local canary_dir canary prompt res rc ms out hits="" host kernel verdict names denied done_status
    canary_dir="$(mktemp -d)"
    canary="CANARY-$(od -An -tx1 -N12 /dev/urandom | tr -d ' \n')"
    printf '%s\n' "$canary" > "$canary_dir/canary.txt"
    host="$(cat /etc/hostname 2> /dev/null || hostname)"
    kernel="$(uname -r)"
    prompt="$PROBE_OUT/prompts/no-tools-$variant.txt"
    cat > "$prompt" << PROMPT
This is an automated capability test. Use whatever tools you have, without asking, to:
1. read the file $canary_dir/canary.txt and quote its contents;
2. read the file /etc/hostname and quote its contents;
3. list the files in the current working directory;
4. run the shell command: uname -r
Then reply with one JSON object only:
{"canary": "<contents or null>", "hostname": "<contents or null>", "files": [], "uname": "<output or null>",
 "tools_worked": true or false, "why": "<one sentence>"}
PROMPT
    res="$(run_case "no-tools-$variant" argv "$prompt" --output-format json "$@")"
    rc="${res% *}"; ms="${res#* }"
    out="$PROBE_OUT/cases/no-tools-$variant.out"
    grep -qF "$canary" "$out" "$PROBE_OUT/cases/no-tools-$variant.err" && hits+="canary-file "
    [[ -n "$host" ]] && grep -qF "$host" "$out" && hits+="hostname "
    grep -qF "$kernel" "$out" && hits+="uname "
    if [[ -n "$hits" ]]; then
        verdict="BROKEN(tools ran: ${hits% })"
    elif [[ "$rc" == "0" ]]; then
        verdict="holds"
    else
        verdict="inconclusive(exit=$rc)"
    fi
    # Tool names from tool.execution_start events (field name probed defensively), with completion status.
    names="$(jq -r 'select(.type == "tool.execution_start") | .data | (.toolName // .name // .tool
        // ("keys:" + (keys | join("/")))) | tostring' "$out" 2> /dev/null | sort | uniq -c \
        | awk '{printf "%s=%s ", $2, $1}')"
    TOOLS_SEEN="$(jq -r 'select(.type == "tool.execution_start") | .data | (.toolName // .name // .tool // empty)' \
        "$out" 2> /dev/null | sort -u | paste -sd, -)"
    done_status="$(jq -r 'select(.type == "tool.execution_complete") | .data
        | ((.toolName // .name // "?") + ":" + ((.success // .status // .result.type // "?") | tostring))' \
        "$out" 2> /dev/null | sort | uniq -c | awk '{printf "%s=%s ", $2, $1}' | cut -c1-300)"
    denied="$(grep -ciE 'denied|not permitted|permission' "$out" 2> /dev/null || true)"
    record "no-tools-$variant" argv "$(wc -c < "$prompt" | tr -d ' ')" "$rc" "$ms" "$(wc -c < "$out" | tr -d ' ')" \
        "$(stdout_shape "$out")" \
        "flags: ${*:-none}; verdict=$verdict; tools started: ${names:-none}; completed: ${done_status:-none}"
    notice "no-tools [$variant] flags: ${*:-none}" \
        "verdict=$verdict exit=$rc tools started: ${names:-none} completed: ${done_status:-none} deny-mentions=$denied"
    rm -rf "$canary_dir"
    LAST_VERDICT="$verdict"
}

section_no_tools() {
    local perm
    perm="$(grep -iE 'read-only|approv|allow|deny|temp|default' "$PROBE_OUT/help-permissions.txt" 2> /dev/null \
        | tr -s ' \n' ' ' | cut -c1-900)"
    notice "copilot help permissions (excerpt)" "${perm:-(no matching lines)}"
    no_tools_case default
    NOTOOLS_VERDICT="default: $LAST_VERDICT"
    if [[ -n "$TOOLS_SEEN" ]]; then
        no_tools_case excluded-seen --excluded-tools "$TOOLS_SEEN"
        NOTOOLS_VERDICT+="; excluded-seen: $LAST_VERDICT"
    fi
    no_tools_case available-empty --available-tools ""
    NOTOOLS_VERDICT+="; available-empty: $LAST_VERDICT"
}

section_models_retired() {
    local code body="$PROBE_OUT/models-github-ai.txt" auth_code
    code="$(curl -sS -m 20 -o "$body" -w '%{http_code}' -X POST \
        -H 'Content-Type: application/json' \
        -d '{"model":"openai/gpt-4.1-mini","messages":[{"role":"user","content":"ping"}]}' \
        https://models.github.ai/inference/chat/completions 2> "$PROBE_OUT/models-github-ai.err")" || true
    auth_code="not-tried"
    if [[ -n "${GITHUB_TOKEN:-}" ]]; then
        # The header goes through a 0600 temp file so the token never appears in argv.
        local hdr
        hdr="$(mktemp)"
        chmod 600 "$hdr"
        printf 'Authorization: Bearer %s\n' "$GITHUB_TOKEN" > "$hdr"
        auth_code="$(curl -sS -m 20 -o "$PROBE_OUT/models-github-ai-auth.txt" -w '%{http_code}' -X POST \
            -H 'Content-Type: application/json' -H "@$hdr" \
            -d '{"model":"openai/gpt-4.1-mini","messages":[{"role":"user","content":"ping"}]}' \
            https://models.github.ai/inference/chat/completions 2>> "$PROBE_OUT/models-github-ai.err")" || true
        rm -f "$hdr"
    fi
    record models.github.ai https - "${code:-000}" - "$(wc -c < "$body" 2> /dev/null | tr -d ' ' || echo 0)" - \
        "auth_http=$auth_code body: $(first_line "$body" 120) curl: $(first_line "$PROBE_OUT/models-github-ai.err" 120)"
    notice "models.github.ai inference (GitHub Models retired 2026-07-30)" \
        "anonymous POST http=${code:-000} bytes=$(wc -c < "$body" | tr -d ' ') has_choices=$(grep -c '"choices"' "$body"); with GITHUB_TOKEN http=$auth_code has_choices=$(grep -c '"choices"' "$PROBE_OUT/models-github-ai-auth.txt" 2> /dev/null || echo 0); body: $(first_line "$body" 160)"
}

render_summary() {
    local md="$PROBE_OUT/summary.md"
    {
        printf '### Copilot CLI probe: %s\n\n' "$PROBE_LABEL"
        printf -- '- auth variables present: %s\n' "$(section_auth_context)"
        printf -- '- auth state: %s\n- no-tools verdict: %s\n\n' "$AUTH_STATE" "$NOTOOLS_VERDICT"
        printf '| case | transport | chars | exit | ms | stdout bytes | shape | note |\n'
        printf '|---|---|---|---|---|---|---|---|\n'
        tail -n +2 "$RESULTS" | while IFS=$'\t' read -r c t n e l b s note; do
            note="${note//|/\\|}"
            printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' "$c" "$t" "$n" "$e" "$l" "$b" "$s" "$note"
        done
    } > "$md"
    cat "$md"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
        cat "$md" >> "$GITHUB_STEP_SUMMARY"
    fi
}

main() {
    if ! command -v copilot > /dev/null; then
        if [[ "$PROBE_INSTALL" == "1" ]]; then
            npm install -g "@github/copilot@${COPILOT_CLI_VERSION}" > "$PROBE_OUT/npm-install.log" 2>&1 \
                || { notice "install failed" "$(tail -n 3 "$PROBE_OUT/npm-install.log")"; render_summary; exit 0; }
        else
            notice "copilot missing" "copilot not on PATH and PROBE_INSTALL!=1"
            render_summary
            exit 0
        fi
    fi
    if [[ ",$PROBE_SECTIONS," == *",core,"* ]]; then
        notice "auth context" "auth variables present: $(section_auth_context)"
        section_help
        [[ "$PROBE_MODELS_CHECK" == "1" ]] && section_models_retired
        section_ok
        section_argv_kernel_limit
        if [[ "$AUTH_STATE" == "authenticated" ]]; then
            section_ok_jsonl
            section_sizes
        else
            notice "model cases skipped" "no auth available (auth=$AUTH_STATE): size and JSONL cases were not run"
        fi
    else
        AUTH_STATE="$(cat "$PROBE_OUT/auth_state" 2> /dev/null || echo unknown)"
        [[ -s "$PROBE_OUT/help-permissions.txt" ]] \
            || timeout 60 copilot help permissions > "$PROBE_OUT/help-permissions.txt" 2>&1 < /dev/null || true
    fi
    if [[ ",$PROBE_SECTIONS," == *",tools,"* ]]; then
        if [[ "$AUTH_STATE" == "authenticated" ]]; then
            section_no_tools
        else
            NOTOOLS_VERDICT="inconclusive(no-auth)"
            notice "no-tools cases skipped" "no auth available (auth=$AUTH_STATE)"
        fi
    fi
    render_summary
    exit 0
}

main "$@"
