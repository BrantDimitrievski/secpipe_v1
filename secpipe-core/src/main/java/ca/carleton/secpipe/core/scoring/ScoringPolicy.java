package ca.carleton.secpipe.core.scoring;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.core.knowledge.ImpactClass;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;
import ca.carleton.secpipe.validators.CapPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic severity policy (feature H, FR-8). Each CWE family has an impact anchor derived from its
 * {@link KnowledgeBase#impactClass(String) impact class}; a confirmed record's impact and overall severity never exceed
 * that anchor. A {@code needs_validation} record carries no severity, so its {@code tool_severity_hint} is capped at
 * {@link #NEEDS_VALIDATION_CAP}. Dependency findings without reachability evidence keep likelihood at or below
 * {@code medium}. No LLM calls (CLAUDE.md §8).
 *
 * <p>The policy is also the real {@link CapPolicy} for rule F4 of the findings validator ({@code ScoringPolicy::cap}).
 */
public final class ScoringPolicy implements CapPolicy {

    /** The highest severity hint an unverified ({@code needs_validation}) record may carry. */
    public static final Score5 NEEDS_VALIDATION_CAP = Score5.MEDIUM;

    /** Anchor for CWEs outside the knowledge base, and for records with no CWE. */
    public static final Score5 DEFAULT_ANCHOR = Score5.MEDIUM;

    private final KnowledgeBase knowledge;

    public ScoringPolicy() {
        this(KnowledgeBase.load());
    }

    public ScoringPolicy(KnowledgeBase knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    /** The impact anchor of an impact class. */
    public static Score5 anchor(ImpactClass impactClass) {
        return switch (Objects.requireNonNull(impactClass, "impactClass")) {
            case INJECTION, UNSAFE_DESERIALIZATION -> Score5.CRITICAL;
            case FILE_ACCESS, CREDENTIAL_EXPOSURE -> Score5.HIGH;
            case CRYPTOGRAPHIC_WEAKNESS -> Score5.MEDIUM;
        };
    }

    /** The highest anchor over {@code cwes}; {@link #DEFAULT_ANCHOR} when none is known. */
    public Score5 anchor(List<String> cwes) {
        Score5 best = null;
        for (String cwe : cwes) {
            Optional<ImpactClass> c = knowledge.impactClass(knowledge.family(cwe));
            if (c.isPresent()) {
                Score5 a = anchor(c.get());
                best = best == null ? a : max(best, a);
            }
        }
        return best == null ? DEFAULT_ANCHOR : best;
    }

    /** F4 cap: the impact anchor of the record's CWE families. */
    @Override
    public Optional<Score5> cap(Finding.Confirmed finding) {
        return Optional.of(anchor(finding.secpipe().cwe()));
    }

    /** The record with policy-conformant severity (confirmed) or severity hint (needs_validation); others unchanged. */
    public Finding apply(Finding finding) {
        return switch (finding) {
            case Finding.Confirmed c -> withSeverity(c, score(c));
            case Finding.NeedsValidation n -> withHint(n, n.secpipe().toolSeverityHint().map(h -> min(h,
                    NEEDS_VALIDATION_CAP)));
            case Finding.Rejected r -> r;
        };
    }

    /** Severity for a confirmed record: impact capped at the anchor, overall = min(max(likelihood, impact), cap). */
    public Finding.Severity score(Finding.Confirmed c) {
        Score5 cap = cap(c).orElseThrow();
        Finding.Scored likelihood = c.severity().likelihood();
        if (c.secpipe().category() == Finding.Category.DEPENDENCY && !reachable(c)) {
            likelihood = capped(likelihood, Score5.MEDIUM, "no reachability evidence");
        }
        Finding.Scored impact = capped(c.severity().impact(), cap, "impact anchor for " + families(c));
        Score5 overall = min(max(likelihood.score(), impact.score()), cap);
        return new Finding.Severity(likelihood, impact, overall);
    }

    private static boolean reachable(Finding.Confirmed c) {
        return c.secpipe().dependency()
                .filter(d -> d.reachable() == Finding.DependencyRef.Reachability.YES).isPresent();
    }

    private String families(Finding.Confirmed c) {
        List<String> fams = c.secpipe().cwe().stream().map(knowledge::family).distinct().sorted().toList();
        return fams.isEmpty() ? "unknown family" : String.join(", ", fams);
    }

    private static Finding.Scored capped(Finding.Scored s, Score5 cap, String why) {
        if (s.score().compareTo(cap) <= 0) {
            return s;
        }
        return new Finding.Scored(cap, s.reason() + " (capped at " + lower(cap) + ": " + why + ")");
    }

    private static Finding.Confirmed withSeverity(Finding.Confirmed c, Finding.Severity s) {
        if (s.equals(c.severity())) {
            return c;
        }
        return new Finding.Confirmed(c.fingerprint(), c.title(), c.description(), c.rootCause(), c.intendedBehavior(),
                c.trace(), c.evidence(), c.conditions(), c.execution(), c.remediation(), s, c.confidence(),
                c.secpipe());
    }

    private static Finding.NeedsValidation withHint(Finding.NeedsValidation n, Optional<Score5> hint) {
        Finding.SecPipeExt e = n.secpipe();
        if (hint.equals(e.toolSeverityHint())) {
            return n;
        }
        Finding.SecPipeExt ext = new Finding.SecPipeExt(e.category(), e.origin(), e.toolSources(), e.cwe(), e.cve(),
                e.owasp(), e.dependency(), hint, e.confidenceHint(), e.testEvidence(), e.fixAttempts(),
                e.remediationStatus(), e.recommendedRemediation(), e.verification(), e.ledgerUnits(),
                e.firstSeenRun(), e.carriedForwardFrom());
        return new Finding.NeedsValidation(n.fingerprint(), n.title(), n.description(), n.claimedRootCause(),
                n.trace(), n.evidence(), n.blockers(), n.validationPlan(), ext);
    }

    static Score5 max(Score5 a, Score5 b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    static Score5 min(Score5 a, Score5 b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    static String lower(Score5 s) {
        return s.name().toLowerCase(java.util.Locale.ROOT);
    }
}
