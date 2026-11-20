package ca.carleton.secpipe.agents.refuter;

import java.util.Optional;

/**
 * {@code value_overwritten}: a possibly tainted local is reassigned from constants on every path before the sink.
 * Applies only when the flow-insensitive view still sees a tainted definition (otherwise it is a constant value).
 */
final class ValueOverwrittenRule implements RefuterRule {

    @Override
    public String id() {
        return Refutation.VALUE_OVERWRITTEN;
    }

    @Override
    public Optional<Refutation> check(SinkSite site) {
        if (RefuterRule.safeArgs(site.run(site.call(), Flow.Mode.WEAK), site.relevantArgs())
                .isPresent()) {
            return Optional.empty();
        }
        return RefuterRule.safeArgs(site.run(site.call(), Flow.Mode.STRONG), site.relevantArgs())
                .map(l -> new Refutation(id(), site.at(l), "the untrusted value is overwritten with a constant (line "
                        + site.at(l).line() + ") on every path before " + site.callName()
                        + "; no tainted data reaches the sink"));
    }
}
