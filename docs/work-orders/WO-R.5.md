### WO-R.5: Make the report node use finalize's frozen run metadata
Wave: 5   Depends on: WO-R.1, WO-K.9   Blocks: WO-K.13
Milestone: Prototype 2 Dec 5
Traces: FR-15, NFR-6 · M, P
Suggested human owner: Abdul Babatunde (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): `FinalizeNode` (WO-K.9) freezes `run-metadata.json` with validator results and incomplete causes, but `ReportNode` (WO-R.1) rewrites it from `RunMetadataAssembler`, hard-coding validators PASS/PASS and dropping finalize's causes. Make `ReportNode` read the frozen file and only amend the terminal state when the report itself fails.

Context to read first (exact paths only): `secpipe-report/src/main/java/ca/carleton/secpipe/report/ReportNode.java`; `secpipe-report/src/main/java/ca/carleton/secpipe/report/RunMetadataAssembler.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/finalize/FinalizeNode.java`; `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/RunMetadataCodec.java`.

Files you own (create/modify — nothing else): `secpipe-report/src/**`; `docs/work-orders/WO-R.5.md` (this file, verbatim).

Interfaces consumed: `RunMetadataCodec`, `FinalizeNode` output   Interfaces produced: `ReportNode` default `MetadataSource` reading `<outDir>/run-metadata.json`.

Implementation notes:
- Default `MetadataSource`: read `<outDir>/run-metadata.json` with `RunMetadataCodec`; fall back to `RunMetadataAssembler` only when the file is missing.
- If the report fails its own validation, rewrite only `terminal_state=incomplete` and append its reason to `incomplete_reason`; keep everything else from finalize.
- Never weaken validator statuses that finalize recorded.

Tests to write: frozen file is preserved byte-for-byte on success; report failure appends its reason and sets incomplete; fallback when the file is missing.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-report -am test -Dtest='ReportNode*Test'` → 0 failures, ≥ 3 new tests
- [ ] `python3 -B scripts/check-plan.py` → `errors=0`
- [ ] `./mvnw -q -B verify` → exit 0 (CI green)

Out of scope: finalize logic; CLI assembly.

Handoff note for next agent: K.13 relies on finalize's `run-metadata.json` surviving the report step.
