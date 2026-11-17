### WO-X.3: Implement safe artifact promotion
Wave: 4   Depends on: WO-X.1   Blocks: WO-G.7, WO-A.1
Milestone: Prototype 2 Dec 5
Traces: FR-12, NFR-5 · K
Suggested human owner: Abdul Babatunde (reviewer: Elaine Huang)
Size: S

Goal (2–3 sentences): `ArtifactPromoter` copies only pre-declared, regular, single-link, size-capped files
from scratch to host destinations after the container exits. It uses NIO `NOFOLLOW_LINKS`, rejects
symlinks, devices, FIFOs, `..`, and absolute paths, re-checks size after the copy, and discards everything
else.

Context to read first (exact paths only): contracts `spi/SandboxRunner.java` (`Promotion`); `docs/PLAN.md` ADR-03 and §2.2-K.

Files you own (create/modify — nothing else): `secpipe-sandbox/src/main/java/ca/carleton/secpipe/sandbox/ArtifactPromoter.java`,
`secpipe-sandbox/src/test/java/ca/carleton/secpipe/sandbox/ArtifactPromoterTest.java`.

Files you may read but not modify: runner.

Interfaces consumed: `Promotion`   Interfaces produced: `ArtifactPromoter.promote(Path scratch, List<Promotion>, Path destDir)
→ List<Path>`; throws `PromotionRejectedException` with the reason per file (others continue).

Implementation notes:
- `Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW_LINKS)`: must be `isRegularFile()`;
  `unix:nlink == 1` where supported (skip the check on Windows, and note it); size ≤ max.
- Open with `NOFOLLOW_LINKS`, copy into a temp file in dest, `fsync`, re-check size, atomic move.
- Normalize the relative path, then reject if it isn't under scratch after `toRealPath(NOFOLLOW_LINKS)`
  of the parent.

Tests to write: a regular file is ok; symlink rejected; a hardlink (nlink 2) rejected (POSIX only,
assumption-guarded); `../escape` rejected; absolute rejected; oversized rejected; a directory rejected; a
file growing during copy rejected (simulate via a re-check hook).

Acceptance criteria (all must pass):
- [ ] `./mvnw -q -B -pl secpipe-sandbox -am test -Dtest=ArtifactPromoterTest` → ≥ 9 tests, 0 failures

Out of scope: in-container cleanup.

Handoff note for next agent: All sandbox consumers promote through this. Raw scratch reads by host code are
forbidden (`CLAUDE.md` §7).
