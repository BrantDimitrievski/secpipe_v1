### WO-C.5: Implement and freeze the fingerprint algorithm
Wave: 1   Depends on: WO-0.1   Blocks: WO-P.2, WO-P.3, WO-P.8, WO-V.1, WO-K.8
Milestone: Prototype 2 Dec 5
Traces: FR-9 · G
Suggested human owner: Elaine Huang (reviewer: Brant Dimitrievski)
Size: S

Goal (2–3 sentences): Implement `Fingerprints.cweFamily`, `code`, `dependency`, and `normalizeStatement`
exactly per ADR-06. Pin them with golden vectors, so the same root cause gets the same fingerprint across
tools, lines, and runs.

Context to read first (exact paths only): `secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ids/Fingerprints.java`, `docs/PLAN.md` ADR-06.

Files you own (create/modify — nothing else):
`secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ids/Fingerprints.java` (bodies only; the
public signatures and constants are frozen), `secpipe-contracts/src/test/java/ca/carleton/secpipe/contracts/ids/FingerprintsTest.java`.

Files you may read but not modify: other contracts.

Interfaces consumed: none   Interfaces produced: the four static functions.

Implementation notes:
- `normalizeStatement`: strip `//` and `/* */` comments that sit **outside** string literals (write a tiny
  lexer; no regex-only approach); collapse whitespace runs outside literals to one space; trim.
- `code(…)`: canonical JSON with sorted keys; SHA-256; first 24 lowercase hex; prefix
  `code:CWE-<familyNumber>:`.
- `dependency(…)`: choose the first id matching `^CVE-`, else `^GHSA-`, else the first. Percent-encode any
  char outside `[A-Za-z0-9._:/@+-]` as `%HH`, then replace `%` with `+`.

Tests to write:
- golden vectors (≥ 6);
- line-shift invariance (the same sink text and scope gives the same id);
- whitespace/comment invariance;
- string-literal content changes alter the id;
- an occurrence index distinguishes duplicates;
- family collapse (CWE-23 and CWE-22 give the same id);
- every output matches `PATTERN` (property-style loop over 1,000 random inputs).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-contracts test -Dtest=FingerprintsTest` → ≥ 10 tests, 0 failures
- [ ] `git diff origin/main...HEAD -- secpipe-contracts/src/main/java/ca/carleton/secpipe/contracts/ids/Fingerprints.java | grep -E '^[-+]\s*public static' ` → only lines whose signatures are unchanged (bodies only)

Out of scope: choosing *which* statement is the sink (adapters and normalize do that).

Handoff note for next agent: Adapters fill `Candidate.sinkStatement` and `sinkScope`. Normalize calls
`Fingerprints.code`. Never hash lines.
