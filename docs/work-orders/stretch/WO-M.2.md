### WO-M.2: Implement test adequacy (FR-15) with JaCoCo on changed lines
Wave: 9   Depends on: WO-M.1, WO-X.4, WO-G.6   Blocks: WO-E.6
Milestone: Oral Jan 25
Traces: FR-15, FR-6 · B, Q
Suggested human owner: Elaine Huang (reviewer: Elodie Abdo)
Size: M

Goal (2–3 sentences): After a candidate fix, run the regression test with the JaCoCo agent (from the
runner image) and compute coverage of the patch's changed lines. Record `adequacy`. If not adequate, make
one extra testgen call targeting the uncovered lines and re-measure. Otherwise report the limitation in
the record and the HTML/PR comment.

Context to read first (exact paths only): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`;
`sandbox-images/runner/Dockerfile`; contracts `finding/Finding.java` (`Adequacy`).

Files you own (create/modify — nothing else): `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/AdequacyCheck.java`,
`secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/JacocoXml.java`, `secpipe-agents/src/main/java/ca/carleton/secpipe/agents/remediation/RemediationLoopNode.java`
(insert the adequacy call), `secpipe-agents/src/test/java/ca/carleton/secpipe/agents/remediation/{AdequacyCheck,JacocoXml}*Test.java`,
`secpipe-agents/src/test/resources/ca/carleton/secpipe/agents/remediation/jacoco/**`.

Files you may read but not modify: testgen, sandbox.

Interfaces consumed: `SandboxRunner`, `TestGenAgent`, `UnifiedDiff`   Interfaces produced: `AdequacyCheck.measure(...) → Adequacy`.

Implementation notes:
- Run the test with `java -javaagent:/opt/jacoco/jacocoagent.jar=destfile=/scratch/jacoco.exec …`, then
  `java -jar /opt/jacoco/jacococli.jar report … --xml /scratch/jacoco.xml` inside the sandbox, and promote
  the XML.
- **Changed lines** = the added/modified lines on the new side of the diff, for production files.
- **Adequate** = ≥ 1 changed line covered **and** every changed line inside the sink method covered.

Tests to write: JaCoCo XML parse; line mapping; adequate / inadequate decisions; extra-testgen path (fake).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-agents -am test -Dtest='AdequacyCheck*Test,JacocoXml*Test'` → ≥ 8 tests, 0 failures
- [ ] [DOCKER] `./mvnw -q -B -Psandbox -pl secpipe-agents -am verify -Dtest='RemediationLoopSandboxIT'` → the fixed record has `adequacy.adequate=true` (paste the log)

Out of scope: mutation testing.

Handoff note for next agent: Reports show "adequacy limited" when `adequate=false`; E.6 tabulates it.
