# Kickoff prompt for a fresh Opus 5.5 session (paste everything below; replace <WO-ID>)

You are implementing exactly one work order in the SecPipe repository: **<WO-ID>**.

1. Read `CLAUDE.md` at the repo root completely. Then read `docs/work-orders/<WO-ID>.md` completely. Then read only the
   files listed under "Context to read first". Do not read `docs/PLAN.md` or other work orders unless your work order
   says to.
2. Make sure the read-only reference repos exist (CLAUDE.md §1a). If the work order has a "Session split", do only the part you were told (default: A).
3. Create and work on the branch named in CLAUDE.md §9: `wo-<epic>.<n>-<short-slug>` (lower case), from up-to-date
   `main`. If the branch already exists, continue on it.
4. Touch **only** the paths listed under "Files you own". Never edit `secpipe-contracts/**` or `action.yml` unless
   <WO-ID> starts with `WO-C.` or `WO-CC.`. Do not add dependencies or modules.
5. Write the tests the work order asks for. All LLM behaviour is tested with `FakeLlmClient`. No network, no API keys,
   and no target-project code outside `SandboxRunner`.
6. Run every acceptance command exactly as written until each passes (`[DOCKER]`/`[HUMAN]` per CLAUDE.md §1b). Also run `./mvnw -q -B verify` and
   `bash scripts/ci-fixture-scan.sh`.
7. Commit with messages `<WO-ID>: <imperative summary>`. Add `Ported-from: capstone-mvp-1@2905635:<path>` lines when you
   port MVP code. Push and open a PR using `.github/pull_request_template.md`, with every section filled in and the
   acceptance outputs pasted. Do not merge.
8. **If the work order is wrong, contradicts a contract or CLAUDE.md, needs a file you don't own, needs a new
   dependency, or an acceptance command cannot pass for reasons outside your files: stop.** Open a draft PR titled
   `<WO-ID>: BLOCKED — <reason>` explaining what you found and what change you recommend. Do not improvise around it.
9. Finish with a short summary: what you built, the acceptance results, and the handoff note for the next agent.
