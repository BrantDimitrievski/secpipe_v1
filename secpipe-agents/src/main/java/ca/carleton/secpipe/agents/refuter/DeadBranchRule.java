package ca.carleton.secpipe.agents.refuter;

import java.util.Optional;

/**
 * {@code dead_branch}: the tainted path is guarded by a condition (if, ternary or switch selector) that folds to a
 * constant, e.g. Benchmark's {@code (7 * 42) - 86 > 200}. Either the sink itself is unreachable, or only constant
 * values reach it once the dead branch is pruned.
 */
final class DeadBranchRule implements RefuterRule {

    @Override
    public String id() {
        return Refutation.DEAD_BRANCH;
    }

    @Override
    public Optional<Refutation> check(SinkSite site) {
        Optional<Flow.Outcome> folded = site.run(site.call(), Flow.Mode.FOLDING);
        if (folded.isEmpty()) {
            return Optional.empty();
        }
        if (!folded.get().reachable()) {
            return Optional.of(new Refutation(id(), site.at(folded.get().deadLine()), "the condition at line "
                    + site.at(folded.get().deadLine()).line() + " folds to a constant, so " + site.callName()
                    + " is unreachable"));
        }
        if (folded.get().prunedLines().isEmpty() || RefuterRule.safeArgs(
                site.run(site.call(), Flow.Mode.STRONG), site.relevantArgs()).isPresent()) {
            return Optional.empty();
        }
        int control = folded.get().prunedLines().getLast();
        return RefuterRule.safeArgs(folded, site.relevantArgs())
                .map(l -> new Refutation(id(), site.at(control), "the branch carrying the untrusted value is dead: "
                        + "the condition at line " + site.at(control).line() + " folds to a constant, so only "
                        + "constants reach " + site.callName()));
    }
}
