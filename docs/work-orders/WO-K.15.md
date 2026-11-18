### WO-K.15: Wire the rescanner and the fix modes into the CLI
Wave: 8   Depends on: WO-K.13, WO-K.6   Blocks: WO-M.1
Milestone: Oral Jan 25
Traces: FR-14, FR-1 · Q
Suggested human owner: Abdul Babatunde (reviewer: Clinton Odinamadu)
Size: S

Goal (2–3 sentences): The CLI still assembles `DefaultRunContext` with the built-in `NoRescanner`. `DetectRescanner` implements the contract `RunContext.Rescanner` by running detect + normalize on a working copy with the run's adapters, and `PipelineAssembly` wires it in. `--enable-fixes pr` exits 2 with a hint; `false` and `suggest` keep their frozen meaning (default `suggest`).

Context to read first (exact paths only): contracts `pipeline/RunContext.java` (`Rescanner`), `config/SecPipeConfig.java` (`FixMode`); `docs/PLAN.md` §4.3; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/PipelineAssembly.java`; `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/detect/DetectNode.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/normalize/NormalizeNode.java`; `secpipe-core/src/main/java/ca/carleton/secpipe/core/engine/DefaultRunContext.java`

Files you own (create/modify — nothing else): `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/PipelineAssembly.java`, `secpipe-cli/src/main/java/ca/carleton/secpipe/cli/ScanCommand.java`, `secpipe-cli/src/test/java/ca/carleton/secpipe/cli/{PipelineAssemblyRescanner,FixMode}*Test.java`, `secpipe-core/src/main/java/ca/carleton/secpipe/core/detect/DetectRescanner.java`, `secpipe-core/src/test/java/ca/carleton/secpipe/core/detect/DetectRescanner*Test.java`.

Files you may read but not modify: core detect/normalize/engine, adapters, contracts.

Interfaces consumed: `AdapterRunner`, `AdapterSelector`, `CandidateMerger`   Interfaces produced: `DetectRescanner implements RunContext.Rescanner` (`List<Candidate> rescan(Path workingCopy, Set<String> scopeFiles)`), wired in `PipelineAssembly`.

Implementation notes:
- The helper lives in `core/detect` (owned file `DetectRescanner.java`); it reuses the same adapter selection as the run and the same normalize/fingerprint code, so fingerprints compare with the original run. Results are sorted by fingerprint; `scopeFiles` filters candidates to those files.
- The rescan reads only the scratch copy (analyzers parse source; nothing in the target runs on the host). It never writes to the user's tree.
- `--enable-fixes pr` → exit 2, `error: --enable-fixes pr needs the GitHub Action (not built)` + `hint: use suggest or false`. Flag names, values, default `suggest` and exit codes stay frozen.

Tests to write: rescan of a copy of `fixtures/e2e/sqli-min` finds the original fingerprint; after a fake fix the fingerprint is gone; scope filter; assembly test shows the context's rescanner is `DetectRescanner`; `pr` → exit 2 with hint, `false`/`suggest` accepted.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='DetectRescanner*Test'` → ≥ 4 tests, 0 failures
- [ ] `./mvnw -q -B -pl secpipe-cli -am test -Dtest='PipelineAssemblyRescanner*Test,FixMode*Test'` → ≥ 3 tests, 0 failures
- [ ] `java -jar secpipe-cli/target/secpipe.jar scan fixtures/e2e/sqli-min --enable-fixes pr; echo $?` → prints the hint and `2`
- [ ] `./mvnw -q -B verify` → exit 0 (CI `ci` check green)

Out of scope: the remediation loop (M.1); the Action PR flow; `secpipe diff` (WO-K.12, stretch).

Handoff note for next agent: M.1 calls `ctx.rescanner().rescan(scratchCopy, touchedFiles)` and compares fingerprints with the record's.
