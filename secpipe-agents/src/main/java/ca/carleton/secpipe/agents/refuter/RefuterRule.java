package ca.carleton.secpipe.agents.refuter;

import com.github.javaparser.ast.expr.Expression;

import java.util.List;
import java.util.Optional;

/** One deterministic refutation pattern. Rules are independent; each returns empty when unsure. */
sealed interface RefuterRule permits ConstantArgumentRule, ValueOverwrittenRule, DeadBranchRule, ParameterizedApiRule {

    String id();

    Optional<Refutation> check(SinkSite site);

    /** The highest definition line among {@code args} if every one of them is untainted at the sink. */
    static Optional<Integer> safeArgs(Optional<Flow.Outcome> outcome, List<Expression> args) {
        if (outcome.isEmpty() || !outcome.get().reachable() || args.isEmpty()) {
            return Optional.empty();
        }
        int line = 0;
        for (Expression a : args) {
            Val v = outcome.get().eval(a);
            if (!v.safe()) {
                return Optional.empty();
            }
            line = Math.max(line, v.line());
        }
        return Optional.of(line);
    }
}
