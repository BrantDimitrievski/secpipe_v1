### WO-0.1: Bootstrap the SecPipe repository, skeleton, and plan
Wave: 0   Depends on: none   Blocks: WO-C.1, WO-C.2, WO-C.3, WO-C.4, WO-C.5, WO-P.0, WO-S.1, WO-S.2, WO-S.3, WO-S.4
Milestone: Prototype 2 Dec 5
Traces: NFR-3, NFR-6 · A, I (repo plumbing for all features)
Suggested human owner: Clinton Odinamadu (reviewer: Brant Dimitrievski)
Size: M

Goal (2–3 sentences): Create `<owner>/secpipe` with protection, labels, milestones, collaborators, and a
compiling 25-module Maven skeleton whose CI is green. Commit this plan, split into `docs/PLAN.md`,
`CLAUDE.md`, `docs/work-orders/`, the contract files, and the execution kit. Mirror every work order as a
GitHub issue. Every later WO assumes this exists exactly as specified.

Context to read first (exact paths only): `scripts/bootstrap-repo.sh`, `scripts/split-plan.py`,
`scripts/create-issues.py`, `scripts/check-plan.py` (all from the plan's Part 13 output); `docs/PLAN.md` Part 3 (layout, dependency
list) and §4.4 (contract files).

Files you own (create/modify — nothing else): the entire initial tree. Specifically:
- `pom.xml` and every `secpipe-*/pom.xml`;
- `mvnw`, `mvnw.cmd`, `.mvn/**`;
- `secpipe-*/src/main/java/ca/carleton/secpipe/**/package-info.java`;
- `secpipe-*/src/test/java/ca/carleton/secpipe/**/SkeletonTest.java`;
- `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `.gitignore`, `.editorconfig`;
- all split-plan outputs: `docs/**`, `CLAUDE.md`, `action.yml`, `secpipe-contracts/src/**`,
  `scripts/**`, `.github/**`.

Files you may read but not modify: `capstone-mvp-1` (do not copy its history).

Interfaces consumed: none   Interfaces produced: module skeleton; all Part 4 contracts as committed source.

Implementation notes:
- Run `python3 scripts/split-plan.py secpipe-plan.md --out .` from an empty checkout. It writes every
  `File:` block, `docs/PLAN.md`, `CLAUDE.md`, and `docs/work-orders/WO-*.md`.
- Then run `scripts/bootstrap-repo.sh --owner <o> --name secpipe --visibility private --members
  <5 handles> --phase init`. It is idempotent.
- Commit the split output plus the skeleton directly to `main` (the only direct push in the project), and
  wait for the first green `ci` run. Then run `scripts/bootstrap-repo.sh --owner <o> --name secpipe --phase
  protect`.
- **Parent POM:**
  - `<modules>` lists all 25 modules from Part 3 in dependency order;
  - `maven.compiler.release=21`;
  - the `maven-enforcer-plugin` requires Java 21+ and Maven 3.9+;
  - surefire/failsafe are configured with `excludedGroups=network,sandbox` by default; profiles `e2e`
    and `sandbox` exist (empty for now);
  - `dependencyManagement` holds **every** dependency in the Part 3 table, at the latest Maven Central
    release on bootstrap day (record the chosen versions in `docs/PLAN.md` Appendix "Pinned versions").
- **Module POMs** declare the dependencies their module will need, following the Part 3 table and the
  dependency rule: e.g. `secpipe-core` gets contracts, validators, sarif, javaparser-core, and
  jackson-databind; `secpipe-agents` gets contracts and validators; `secpipe-cli` depends on all modules
  and configures maven-shade (ServicesResourceTransformer, main class `ca.carleton.secpipe.cli.SecPipeCli`,
  final name `secpipe`). `secpipe-graph-langgraph4j` declares langgraph4j-core; if it fails to resolve,
  leave the module out of `<modules>` and note it for WO-S.1.
- Each module gets a `SkeletonTest` asserting `true` about something real (e.g. the module's
  `package-info` class loads). This is the only allowed placeholder test.
- `secpipe-cli` gets a minimal `SecPipeCli` main printing the version, so CI can run `java -jar`.
  WO-K.13 replaces it.
- The contracts must compile as committed (they were checked with `javac`). Do **not** edit them; if they
  don't compile, stop and report.
- `THIRD_PARTY_NOTICES.md` credits Cloudflare `security-audit-skill` (MIT, full licence text) for adapted
  prompts, schemas, and validator rules. The OWASP Benchmark (GPL-2.0) is **downloaded at evaluation time,
  never vendored**.
- Licence: MIT (open question Q1 in Part 12).
- `bootstrap-repo.sh` sets the default workflow permissions to read and enables Actions; it creates no
  secrets.
- After protection is on, run `python3 scripts/check-plan.py` (it must report 0 errors) and
  `python3 scripts/create-issues.py --repo <o>/secpipe`.

Tests to write: `SkeletonTest` per module (25).

Acceptance criteria (all must pass):
- [ ] `gh repo view <owner>/secpipe --json name,visibility` → name `secpipe`, visibility as chosen
- [ ] `gh api repos/<owner>/secpipe/branches/main/protection --jq '.required_status_checks.contexts, .required_pull_request_reviews.required_approving_review_count, .allow_force_pushes.enabled, .required_linear_history.enabled'` → `["ci"]`, `1`, `false`, `true`
- [ ] `gh label list -R <owner>/secpipe --limit 200 | grep -c -E '^(wave:|milestone:|epic:|owner:|contract|spike|stretch|blocked)'` → ≥ 40
- [ ] `gh api repos/<owner>/secpipe/milestones --jq 'length'` → ≥ 10
- [ ] `./mvnw -q -B verify` → exit 0; `java -jar secpipe-cli/target/secpipe.jar --version` prints `0.2.0-SNAPSHOT`
- [ ] `ls docs/work-orders | wc -l` → equals the number of `### WO-` headings in the plan
- [ ] `gh run list -R <owner>/secpipe --workflow ci.yml --branch main --limit 1 --json conclusion --jq '.[0].conclusion'` → `success`
- [ ] `bash scripts/bootstrap-repo.sh --owner <o> --name secpipe --members <…> --phase init --dry-run` run again → reports `exists` for repo, collaborators, labels, and milestones; creates nothing
- [ ] `python3 scripts/check-plan.py` → `errors=0`; running `python3 scripts/create-issues.py --repo <o>/secpipe` a second time → `created=0`
- [ ] `gh issue list -R <owner>/secpipe --limit 300 --label epic:K | wc -l` → 14

Out of scope: any business logic; editing contracts; copying MVP code.

Handoff note for next agent: The module list, pinned versions, and the fact that contracts are committed are
recorded in `docs/PLAN.md` Appendix "Pinned versions". Wave-1 WOs can start once this PR is merged and CI is
green.
