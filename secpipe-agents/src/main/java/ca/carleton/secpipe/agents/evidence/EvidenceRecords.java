package ca.carleton.secpipe.agents.evidence;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.Citation;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.ProposedSeverity;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Builds the records {@link TestEvidenceNode} writes. Every record it returns passes FindingsValidator F1–F9. */
final class EvidenceRecords {

    static final String PLAN_LOCAL = "Run SecPipe with a usable Docker sandbox and an AI provider so a regression test "
            + "can be generated and run, or review the cited trace by hand.";

    private EvidenceRecords() {
    }

    /** Whether a {@code needs_validation} record can be built from the candidate (non-empty trace ending at a sink). */
    static boolean recordable(Candidate c) {
        return c.fingerprint().isPresent() && !c.trace().isEmpty()
                && c.trace().get(c.trace().size() - 1).kind() == Finding.TraceKind.SINK;
    }

    /**
     * A {@code needs_validation} record for {@code c}. It keeps the verification of {@code prior} (the disprover's
     * record) when there is one and may carry {@code tool_severity_hint} (F5) but never a severity.
     */
    static Finding.NeedsValidation needsValidation(Candidate c, Optional<Finding> prior, String blocker,
                                                   List<TestEvidence> evidence) {
        Finding.SecPipeExt ext = ext(c, prior, c.toolSeverityHint(), evidence);
        return new Finding.NeedsValidation(c.fingerprint().orElseThrow(), title(c), description(c), rootCause(c),
                c.trace(), evidence(c), List.of(blocker),
                new Finding.ValidationPlan(Optional.of(PLAN_LOCAL), Optional.empty()), ext);
    }

    /**
     * A {@code confirmed} record backed by the failing pre-fix test {@code failed}, or empty when no trace starting at
     * an entrypoint and ending at a sink is available (F2).
     */
    static Optional<Finding.Confirmed> confirmed(Candidate c, DisproverResult draft, Optional<Finding> prior,
                                                 TestGenResult test, TestEvidence failed, List<TestEvidence> all) {
        Optional<List<Finding.TraceStep>> trace = draftTrace(draft).or(() -> fullTrace(c.trace()));
        if (trace.isEmpty()) {
            return Optional.empty();
        }
        List<Finding.Evidence> evidence = new ArrayList<>();
        for (Citation ci : draft.evidence()) {
            if (!blank(ci.description())) {
                Finding.Evidence e = new Finding.Evidence(ci.file(), ci.line(), ci.description());
                if (!evidence.contains(e)) {
                    evidence.add(e);
                }
            }
        }
        if (evidence.isEmpty()) {
            evidence.addAll(evidence(c));
        }
        List<Finding.Condition> conditions = List.copyOf(new LinkedHashSet<>(draft.conditions()));
        List<String> payloads = test.payloads().stream().distinct().toList();
        if (payloads.isEmpty()) {
            payloads = List.of("see " + RegressionTestRun.fqcn(test));
        }
        String fqcn = RegressionTestRun.fqcn(test);
        Finding.Execution execution = new Finding.Execution(
                "An attacker who controls the input at the traced entrypoint.", payloads,
                List.of("Run the generated regression test " + fqcn + " (" + RegressionTestRun.testFile(test)
                        + ") offline against the unpatched code in the SecPipe sandbox."),
                failed.observedResult().orElseThrow());
        String rootCause = draft.rootCause().filter(s -> !blank(s)).orElse(rootCause(c));
        String intended = draft.intendedBehavior().filter(s -> !blank(s))
                .orElse("Untrusted input must not change the meaning of the operation at the sink.");
        String strategy = c.smallestFix().filter(s -> !blank(s)).orElse("Neutralize the untrusted input before it "
                + "reaches the sink (for example parameterize or validate it) so " + fqcn + " passes.");
        Finding.SecPipeExt ext = ext(c, prior, Optional.empty(), all);
        return Optional.of(new Finding.Confirmed(c.fingerprint().orElseThrow(), title(c), description(c), rootCause,
                intended, trace.get(), List.copyOf(evidence), conditions, execution,
                new Finding.Remediation(strategy, List.of()), severity(draft), new Finding.Confidence(
                        Finding.Level3.HIGH, "A generated regression test failed as expected on the unpatched code "
                        + "in the network-less sandbox."), ext));
    }

    /**
     * Provisional severity from the disprover's proposal (MEDIUM/MEDIUM without one). {@code overall_severity} is
     * kept at or below MEDIUM, the lowest scoring cap, so F4 holds before the {@code score} node rescores it.
     */
    static Finding.Severity severity(DisproverResult draft) {
        Optional<ProposedSeverity> p = draft.proposedSeverity();
        Score5 likelihood = p.map(ProposedSeverity::likelihood).orElse(Score5.MEDIUM);
        Score5 impact = p.map(ProposedSeverity::impact).orElse(Score5.MEDIUM);
        String lr = p.map(ProposedSeverity::likelihoodReason).filter(s -> !blank(s))
                .orElse("provisional; rescored by the score node");
        String ir = p.map(ProposedSeverity::impactReason).filter(s -> !blank(s))
                .orElse("provisional; rescored by the score node");
        Score5 max = likelihood.compareTo(impact) >= 0 ? likelihood : impact;
        Score5 overall = max.compareTo(Score5.MEDIUM) > 0 ? Score5.MEDIUM : max;
        return new Finding.Severity(new Finding.Scored(likelihood, lr), new Finding.Scored(impact, ir), overall);
    }

    private static Optional<List<Finding.TraceStep>> draftTrace(DisproverResult draft) {
        List<Finding.TraceStep> steps = new ArrayList<>();
        for (Step s : draft.trace()) {
            if (blank(s.scope()) || blank(s.description()) || s.line() < 1) {
                return Optional.empty();
            }
            Finding.TraceStep t = new Finding.TraceStep(s.kind(), s.file(), s.line(), s.scope(), s.description());
            if (!steps.contains(t)) {
                steps.add(t);
            }
        }
        return fullTrace(steps);
    }

    private static Optional<List<Finding.TraceStep>> fullTrace(List<Finding.TraceStep> steps) {
        if (steps.isEmpty() || steps.get(0).kind() != Finding.TraceKind.ENTRYPOINT
                || steps.get(steps.size() - 1).kind() != Finding.TraceKind.SINK) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(steps));
    }

    private static Finding.SecPipeExt ext(Candidate c, Optional<Finding> prior, Optional<Score5> hint,
                                          List<TestEvidence> evidence) {
        Optional<Finding.Verification> verification = prior.flatMap(p -> p.secpipe().verification());
        return new Finding.SecPipeExt(c.category(), c.origin(), c.toolSources(), c.cwe(), c.cve(), Optional.empty(),
                c.dependency(), hint, c.confidenceHint(), List.copyOf(evidence), List.of(), Optional.empty(),
                Optional.empty(), verification, c.ledgerUnits(), Optional.empty(), Optional.empty());
    }

    private static String title(Candidate c) {
        return blank(c.title()) ? "candidate " + c.fingerprint().orElseThrow() : c.title();
    }

    private static String description(Candidate c) {
        return blank(c.message()) ? title(c) : c.message();
    }

    private static String rootCause(Candidate c) {
        return blank(c.claimedRootCause()) ? description(c) : c.claimedRootCause();
    }

    private static List<Finding.Evidence> evidence(Candidate c) {
        if (!c.evidence().isEmpty()) {
            return c.evidence();
        }
        Finding.TraceStep sink = c.trace().get(c.trace().size() - 1);
        return List.of(new Finding.Evidence(sink.file(), sink.line(), blank(sink.description()) ? "sink"
                : sink.description()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
