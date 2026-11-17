### WO-C.3: Freeze agent output contracts — codecs and example corpus
Wave: 1   Depends on: WO-0.1   Blocks: WO-L.1, WO-V.3, WO-G.1
Milestone: Prototype 2 Dec 5
Traces: FR-1 · A, C, E, F, I, L
Suggested human owner: Clinton Odinamadu (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): Make every `AgentOutputs` record round-trip with the six agent schemas. Build a
corpus of valid and invalid examples per schema (≥ 3 valid, ≥ 3 invalid each), which the fake-LLM fixtures
and the validators reuse.

Context to read first (exact paths only): `secpipe-contracts/src/main/resources/schemas/agent/*.json`,
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/agent/AgentOutputs.java`, `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/agent/AgentRole.java`.

Files you own (create/modify — nothing else):
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/json/AgentOutputCodec.java`,
`secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/json/AgentOutputCodecTest.java`,
`secpipe-contracts/src/test/resources/examples/agent/**`.

Files you may read but not modify: other contracts.

Interfaces consumed: `AgentOutputs`   Interfaces produced: `AgentOutputCodec.read(String json, Class<T>)`
(strict: fails on unknown fields), the example corpus.

Implementation notes:
- Invalid examples must cover: a fenced JSON string (`"```json {…}```"`, kept as raw text in a `.txt`
  file); prose plus JSON; two objects; missing required fields; branch violations (disprover `rejected`
  with severity; hunter `covered` with candidates).
- `RecordVerifierResult.replacement` deserializes through `FindingCodec`.

Tests to write: per-schema valid/invalid against both the schema and the codec.

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-contracts test -Dtest=AgentOutputCodecTest` → 0 failures
- [ ] `find secpipe-contracts/src/test/resources/examples/agent -type f | wc -l` → ≥ 36

Out of scope: grounding (WO-V.3).

Handoff note for next agent: The example corpus path is stable. Fake-LLM fixtures may copy from it.
