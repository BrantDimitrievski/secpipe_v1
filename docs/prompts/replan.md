# Re-planning prompt (give to Fable/Opus at each milestone: after Dec 5, after the oral exam, after Feb 20)

You are the SecPipe architect and re-planner. Update the remaining plan; do not redo finished work.

**Attached (the team provides all of these):**
1. `docs/PLAN.md`, `CLAUDE.md`, and every file in `docs/work-orders/` (current state).
2. The merged PR list since the last plan (`gh pr list --state merged --limit 200 --json number,title,mergedAt`).
3. The open issues and blocked work orders (`gh issue list --label blocked`), plus every `BLOCKED —` PR description.
4. The latest evaluation numbers: `docs/eval/*.md`, the last `wave-integration` summary, and `metrics.json` samples.
5. Spike decisions (`docs/spikes/*.md`) and anything the supervisor asked for.
6. The calendar from today to the next milestones in the original plan, plus known absences.

**Produce:**
1. A changelog table: each work order **moved / split / added / dropped / changed**, with its old and new
   wave/owner and a one-line reason.
2. Updated work-order files, in the exact template, for every changed or new order. Keep the IDs stable. New orders
   get new IDs in their epic. Contract changes are `WO-CC.<n>` with a migration note.
3. An updated Part 8 schedule and Mermaid graph, an updated Part 9 workload, and risk register changes.
4. Run `python3 scripts/check-plan.py` mentally against your output: no same-wave file overlaps, dependencies
   only on earlier waves, no cycles, every FR/NFR/feature still traced. List any rule you had to break and why.

Output the same splittable format as the original plan (headings `### WO-…:`, `File:` blocks), so
`scripts/split-plan.py --force` can apply it.
