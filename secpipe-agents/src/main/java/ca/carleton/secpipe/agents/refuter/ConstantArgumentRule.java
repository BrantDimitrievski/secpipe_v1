package ca.carleton.secpipe.agents.refuter;

import java.util.Optional;

/**
 * {@code constant_value}: every injectable argument of the sink folds to a constant even when every assignment is
 * treated as "may happen" (flow-insensitive), so no tainted value is ever involved.
 */
final class ConstantArgumentRule implements RefuterRule {

    @Override
    public String id() {
        return Refutation.CONSTANT_VALUE;
    }

    @Override
    public Optional<Refutation> check(SinkSite site) {
        return RefuterRule.safeArgs(Flow.run(site.method(), site.call(), Flow.Mode.WEAK), site.relevantArgs())
                .map(l -> new Refutation(id(), site.at(site.line()), "every injectable argument of "
                        + site.callName() + " folds to a constant; no untrusted value can reach the sink"));
    }
}
