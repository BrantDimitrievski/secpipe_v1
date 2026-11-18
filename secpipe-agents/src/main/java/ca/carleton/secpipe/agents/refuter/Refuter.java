package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import com.github.javaparser.ast.CompilationUnit;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic refuter (PLAN Part 5 §A6): rejects a code candidate of CWE-22/78/89 when its sink provably receives
 * no tainted value. It only parses source (never compiles or runs it), makes no LLM calls, never confirms, and
 * returns empty whenever it is unsure. Rules are tried in a fixed order; a candidate whose sink line holds several
 * sink calls is refuted only if the same rule refutes all of them.
 */
public final class Refuter {

    /** CWE families the refuter handles. */
    public static final Set<String> FAMILIES = Set.of("CWE-22", "CWE-78", "CWE-89");

    private final List<RefuterRule> rules = List.of(new ConstantArgumentRule(), new ValueOverwrittenRule(),
            new DeadBranchRule(), new ParameterizedApiRule());

    public Optional<Refutation> check(Candidate candidate, CompilationUnit unit) {
        if (candidate.category() != Finding.Category.CODE || candidate.dependency().isPresent()) {
            return Optional.empty();
        }
        Optional<String> family = family(candidate);
        Optional<Finding.TraceStep> sink = sinkStep(candidate);
        if (family.isEmpty() || sink.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<SinkSite> sites = SinkLocator.locate(unit, sink.get().file(), sink.get().line(), family.get());
            if (sites.isEmpty()) {
                return Optional.empty();
            }
            for (RefuterRule rule : rules) {
                List<Optional<Refutation>> results = sites.stream().map(rule::check).toList();
                if (results.stream().allMatch(Optional::isPresent)) {
                    return results.getFirst();
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty(); // unsure: pass through
        }
        return Optional.empty();
    }

    /** The single handled CWE family of the candidate, if exactly one of its CWEs is handled. */
    static Optional<String> family(Candidate c) {
        List<String> handled = c.cwe().stream().filter(FAMILIES::contains).distinct().toList();
        return handled.size() == 1 ? Optional.of(handled.getFirst()) : Optional.empty();
    }

    /** The last SINK step of the trace. */
    static Optional<Finding.TraceStep> sinkStep(Candidate c) {
        List<Finding.TraceStep> trace = c.trace();
        if (trace.isEmpty() || trace.getLast().kind() != Finding.TraceKind.SINK) {
            return Optional.empty();
        }
        return Optional.of(trace.getLast());
    }
}
