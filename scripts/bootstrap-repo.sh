#!/usr/bin/env bash
# Create and configure the SecPipe GitHub repository (WO-0.1). Safe to re-run: existing things are reported, not duplicated.
#
#   scripts/bootstrap-repo.sh --owner ORG_OR_USER [--name secpipe] [--visibility private|public]
#       --members "handle1,handle2,..." [--phase init|protect|all] [--dry-run]
#
# Phases:
#   init     create repo (README/licence come from the split plan commit), invite collaborators, labels, milestones,
#            Actions enabled with read-only default GITHUB_TOKEN, no secrets.
#   protect  protect main (PRs, 1 approval, required check "ci", linear history, no force-push). Run AFTER the first
#            push of .github/workflows/ci.yml to main and one green CI run (the check name must exist).
#   all      init then protect.
# Needs: gh (authenticated with repo + admin:org if ORG), jq.
set -euo pipefail

OWNER="" NAME="secpipe" VIS="private" MEMBERS="" PHASE="all" DRY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --owner) OWNER="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --visibility) VIS="$2"; shift 2 ;;
    --members) MEMBERS="$2"; shift 2 ;;
    --phase) PHASE="$2"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$OWNER" ]] || { echo "error: --owner is required" >&2; echo "hint: --owner secpipe-carleton" >&2; exit 2; }
[[ "$VIS" == private || "$VIS" == public ]] || { echo "error: --visibility must be private or public" >&2; exit 2; }
command -v gh >/dev/null || { echo "error: gh not found" >&2; echo "hint: https://cli.github.com" >&2; exit 2; }
command -v jq >/dev/null || { echo "error: jq not found" >&2; exit 2; }
gh auth status >/dev/null 2>&1 || { echo "error: gh is not authenticated" >&2; echo "hint: gh auth login -s repo,admin:org" >&2; exit 2; }

REPO="$OWNER/$NAME"
run() { if [[ $DRY -eq 1 ]]; then echo "[dry-run] $*"; else "$@"; fi; }
say() { printf '%-10s %s\n' "$1" "$2"; }

LABELS=()
for w in $(seq 0 14); do LABELS+=("wave:$w|c5def5|Execution wave $w"); done
for m in p2 oral final stretch; do LABELS+=("milestone:$m|fbca04|Plan milestone $m"); done
for e in 0 S C P V K L G X R M A H Y E D; do LABELS+=("epic:$e|0e8a16|Epic $e"); done
for o in abdul brant clinton elaine elodie; do LABELS+=("owner:$o|bfdadc|Suggested human owner"); done
LABELS+=("contract|b60205|Touches frozen contracts" "spike|d93f0b|Time-boxed spike" "stretch|cccccc|Stretch backlog" "blocked|000000|Work order blocked; needs re-plan")

MILESTONES=(
  "Prototype 1 integration (Nov 16, 2026)|2026-11-16"
  "Prototype 2 (Dec 5, 2026)|2026-12-05"
  "Progress report (Dec 11, 2026)|2026-12-11"
  "Prototype 2 integration (Dec 13, 2026)|2026-12-13"
  "Oral presentations (Jan 25-29, 2027)|2027-01-25"
  "Integration & deployment (Jan 27, 2027)|2027-01-27"
  "Final testing (Feb 20, 2027)|2027-02-20"
  "Final report draft (Mar 8-12, 2027)|2027-03-08"
  "Poster fair (Mar 19, 2027)|2027-03-19"
  "Final report + video (Apr 9, 2027)|2027-04-09"
)

phase_init() {
  if gh repo view "$REPO" >/dev/null 2>&1; then say exists "repo $REPO"
  else run gh repo create "$REPO" "--$VIS" --description "SecPipe: multi-agent defensive security analysis pipeline for Java/Maven (SYSC 4907)" --disable-wiki; say created "repo $REPO"; fi

  # Actions on, default token read-only, cannot approve PRs. No secrets are created.
  run gh api -X PUT "repos/$REPO/actions/permissions" -F enabled=true -f allowed_actions=all >/dev/null
  run gh api -X PUT "repos/$REPO/actions/permissions/workflow" -f default_workflow_permissions=read -F can_approve_pull_request_reviews=false >/dev/null
  say set "actions enabled; default GITHUB_TOKEN permissions = read"

  IFS=',' read -r -a HANDLES <<< "$MEMBERS"
  for h in "${HANDLES[@]}"; do
    h="${h// /}"; [[ -z "$h" ]] && continue
    if gh api "repos/$REPO/collaborators/$h" >/dev/null 2>&1; then say exists "collaborator $h"
    else run gh api -X PUT "repos/$REPO/collaborators/$h" -f permission=push >/dev/null; say invited "$h"; fi
  done

  existing_labels="$(gh label list -R "$REPO" --limit 500 --json name --jq '.[].name' 2>/dev/null || true)"
  for spec in "${LABELS[@]}"; do
    IFS='|' read -r n c d <<< "$spec"
    if grep -qxF "$n" <<< "$existing_labels"; then say exists "label $n"
    else run gh label create "$n" -R "$REPO" --color "$c" --description "$d" >/dev/null; say created "label $n"; fi
  done

  existing_ms="$(gh api "repos/$REPO/milestones?state=all&per_page=100" --jq '.[].title' 2>/dev/null || true)"
  for spec in "${MILESTONES[@]}"; do
    IFS='|' read -r t due <<< "$spec"
    if grep -qxF "$t" <<< "$existing_ms"; then say exists "milestone $t"
    else run gh api -X POST "repos/$REPO/milestones" -f title="$t" -f due_on="${due}T23:59:59Z" >/dev/null; say created "milestone $t"; fi
  done
}

phase_protect() {
  local body
  body='{"required_status_checks":{"strict":true,"contexts":["ci"]},"enforce_admins":false,
         "required_pull_request_reviews":{"required_approving_review_count":1,"dismiss_stale_reviews":true},
         "restrictions":null,"required_linear_history":true,"allow_force_pushes":false,"allow_deletions":false}'
  if ! gh api "repos/$REPO/branches/main" >/dev/null 2>&1; then
    echo "error: branch main does not exist yet" >&2
    echo "hint: run split-plan.py, commit the skeleton, push to main, wait for a green 'ci' run, then --phase protect" >&2
    exit 2
  fi
  if [[ $DRY -eq 1 ]]; then echo "[dry-run] PUT repos/$REPO/branches/main/protection $body"; return; fi
  if ! out="$(echo "$body" | gh api -X PUT "repos/$REPO/branches/main/protection" --input - 2>&1)"; then
    echo "error: could not protect main: $out" >&2
    echo "hint: private repos need GitHub Pro/Team for branch protection (free via the GitHub Student Developer Pack), or make the repo public" >&2
    exit 1
  fi
  say set "main protected (PR + 1 approval + check 'ci' + linear history + no force-push)"
}

case "$PHASE" in
  init) phase_init ;;
  protect) phase_protect ;;
  all) phase_init; phase_protect ;;
  *) echo "error: --phase must be init|protect|all" >&2; exit 2 ;;
esac
echo "done: $REPO"
