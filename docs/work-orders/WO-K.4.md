### WO-K.4: Implement recon (entry surfaces, sinks, boundaries) and seed the coverage ledger
Wave: 3   Depends on: WO-V.2, WO-V.3, WO-C.2, WO-P.1   Blocks: WO-G.4, WO-G.5, WO-0.2, WO-G.2, WO-G.9, WO-A.4
Milestone: Prototype 2 Dec 5
Traces: FR-3, FR-15 · B, C, N
Suggested human owner: Brant Dimitrievski (reviewer: Clinton Odinamadu)
Size: L

Goal (2–3 sentences): `ReconNode` parses sources once (JavaParser) and inventories entry surfaces, sinks
per CWE family, and trust-boundary controls. It exposes them as `ReconFacts` plus a `SourceIndex`, and
seeds deterministic ledger units, one per surface × boundary × subsystem × attack class/CWE, at the
granularity the profile sets. This redesigns the MVP's `CodeMapBuilder`.

Context to read first (exact paths only): MVP `src/main/java/ca/carleton/secpipe/agents/CodeMapBuilder.java`,
`core/CodeMap.java`; contracts `ledger/LedgerUnit.java`, `ids/CoverageIds.java`, `pipeline/*`;
`secpipe-validators/src/main/java/ca/carleton/secpipe/validators/GroundingChecker.java` (the `SourceIndex` interface); `docs/PLAN.md` ADR-04 and §4.2.

Files you own (create/modify — nothing else): `secpipe-core/src/main/java/ca/carleton/secpipe/core/recon/**`,
`secpipe-core/src/main/resources/recon/**` (sink catalogue JSON), `secpipe-core/src/test/java/ca/carleton/secpipe/core/recon/**`,
`secpipe-core/src/test/resources/ca/carleton/secpipe/core/recon/**`.

Files you may read but not modify: contracts, validators, `core/env/**`.

Interfaces consumed: `ProjectProfile`, `CoverageIds`, `SourceIndex`   Interfaces produced: `ReconNode`;
`ReconFacts` (entry surfaces with `Location` + route + auth hint; sinks by family; boundary controls),
persisted as `<work>/recon-facts.json`; `FileSourceIndex implements SourceIndex`;
`ReconFacts.load(workDir)`.

Implementation notes:
- **Entry surfaces:** `HttpServlet` subclasses' `doGet/doPost/doPut/doDelete/service`; methods annotated
  `@RequestMapping/@GetMapping/@PostMapping/…`; JAX-RS `@GET/@POST` with `@Path`; `public static void
  main`.
- **Boundaries:** Spring Security `HttpSecurity` configs, `@PreAuthorize/@Secured/@RolesAllowed`,
  `Filter` implementations. Record "none detected" as boundary ref `<file>#<method>` for the handler
  itself.
- **Sink catalogue (JSON resource):** JDBC `execute*`/`prepareStatement` with a non-constant string, JPA
  `createQuery/createNativeQuery`; `Runtime.exec`, `ProcessBuilder`; `new File/FileInputStream/Paths.get/Files.*`;
  `MessageDigest.getInstance`; `Cipher.getInstance`; `ObjectInputStream.readObject`.
- **Units:**
  - for each surface and each CWE family in `{22, 78, 89, 327, 328}` (plus 502/798 if present in the
    catalogue) that is **reachable intra-file** (the method or its file contains a sink of that family),
    one unit with `attack_class = ATTACK-CLASSES.md#Injection/CWE-89` etc.;
  - `standard`/`deep` add one `Access control` and one `Business logic` unit per surface (LLM-only
    classes);
  - `quick` uses `CoverageIds.QUICK_SUBSYSTEM` and only CWE units for surfaces in the diff scope (or all
    surfaces if the scope is full).
- `selected_companion_blocks`: `WEB-PROTOCOL-AND-AUTH.md#Core discipline` when the surface is HTTP;
  `SUPPLY-CHAIN-AND-RELEASE.md#Core discipline` for the pom manifest unit (one per project, CWE `null`,
  attack class `Supply chain`).
- `excluded_blocks` lists the Part 5 exclusions with reasons.
- Seed status `planned`, prior_status `new` (WO-K.12 adjusts it later).

Tests to write: on `fixtures/mvp-samples/vulnerable-java-app`, the surfaces include `LoginServlet#doPost`
and the sinks include its `executeQuery`; the unit count is deterministic across two runs (identical
JSON); `quick` vs `standard` unit counts differ as specified; the seeded ledger passes `LedgerValidator`;
`FileSourceIndex` rejects `..` paths.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-core -am test -Dtest='ReconNode*Test,FileSourceIndex*Test'` → ≥ 10 tests, 0 failures
- [ ] A test runs recon twice on the same fixture and asserts byte-identical ledger JSON (via `LedgerCodec`)
- [ ] A test asserts every seeded unit validates with `LedgerValidator.validate` and `coverage_id == CoverageIds.of(refs)`

Session split (L-size; same branch, one PR): **Session A** — Entry-surface, boundary and sink inventory: `ReconFacts`, sink catalogue JSON, `FileSourceIndex`, and their tests. Commit and push; stop. **Session B** (fresh session, same kickoff + "continue <WO-ID> part B") — Ledger seeding per profile, `ReconNode`, the determinism and validator tests. Then run all acceptance commands and open the PR.

Out of scope: Joern CPG (WO-A.2 may add slices later); architecture summary (WO-G.9).

Handoff note for next agent: Hunters and the disprover get slices only through `SourceIndex`. Detect's
settlement matches units by (file in `starting_paths`, CWE family).
