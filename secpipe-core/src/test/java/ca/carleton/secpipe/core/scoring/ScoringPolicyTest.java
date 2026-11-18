package ca.carleton.secpipe.core.scoring;

import static ca.carleton.secpipe.core.scoring.ScoringFixtures.confirmed;
import static ca.carleton.secpipe.core.scoring.ScoringFixtures.needsValidation;
import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.core.knowledge.ImpactClass;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class ScoringPolicyTest {

    private final ScoringPolicy policy = new ScoringPolicy();

    @ParameterizedTest
    @CsvSource({
        "CWE-89, CRITICAL", "CWE-78, CRITICAL", "CWE-502, CRITICAL", "CWE-22, HIGH", "CWE-23, HIGH",
        "CWE-798, HIGH", "CWE-327, MEDIUM", "CWE-328, MEDIUM", "CWE-9999, MEDIUM"})
    void anchorPerFamily(String cwe, Score5 expected) {
        assertThat(policy.anchor(List.of(cwe))).isEqualTo(expected);
    }

    @Test
    void everyKnowledgeBaseFamilyHasAnAnchor() {
        KnowledgeBase kb = KnowledgeBase.load();
        for (String family : kb.familyIds()) {
            ImpactClass c = kb.impactClass(family).orElseThrow();
            assertThat(policy.anchor(List.of(family))).isEqualTo(ScoringPolicy.anchor(c));
        }
    }

    @Test
    void confirmedOverallIsCappedAtTheFamilyAnchor() {
        Finding.Confirmed crypto = confirmed("fp-a", "CWE-327", Score5.CRITICAL, Score5.CRITICAL, Score5.CRITICAL);
        Finding.Severity s = policy.score(crypto);
        assertThat(s.impact().score()).isEqualTo(Score5.MEDIUM);
        assertThat(s.impact().reason()).contains("capped at medium").contains("CWE-327");
        assertThat(s.overallSeverity()).isEqualTo(Score5.MEDIUM);
        assertThat(policy.cap(crypto)).contains(Score5.MEDIUM);
    }

    @Test
    void injectionKeepsCriticalAndLowStaysLow() {
        assertThat(policy.score(confirmed("fp-b", "CWE-89", Score5.HIGH, Score5.CRITICAL, Score5.CRITICAL))
                .overallSeverity()).isEqualTo(Score5.CRITICAL);
        assertThat(policy.score(confirmed("fp-c", "CWE-89", Score5.LOW, Score5.LOW, Score5.LOW))
                .overallSeverity()).isEqualTo(Score5.LOW);
    }

    @Test
    void unreachableDependencyLikelihoodIsAtMostMedium() {
        Finding.DependencyRef dep = new Finding.DependencyRef("pkg:maven/g/a@1", "g", "a", "1", Optional.empty(),
                Optional.empty(), List.of(), Finding.DependencyRef.Reachability.UNKNOWN);
        Finding.Confirmed c = confirmed("fp-d", Finding.Category.DEPENDENCY, "CWE-502", Score5.CRITICAL,
                Score5.MEDIUM, Score5.CRITICAL, Optional.of(dep));
        Finding.Severity s = policy.score(c);
        assertThat(s.likelihood().score()).isEqualTo(Score5.MEDIUM);
        assertThat(s.overallSeverity()).isEqualTo(Score5.MEDIUM);
    }

    @Test
    void needsValidationHintIsCappedAtMedium() {
        Finding high = policy.apply(needsValidation("fp-e", "CWE-89", Optional.of(Score5.CRITICAL)));
        assertThat(high.secpipe().toolSeverityHint()).contains(Score5.MEDIUM);
        Finding low = needsValidation("fp-f", "CWE-89", Optional.of(Score5.LOW));
        assertThat(policy.apply(low)).isSameAs(low);
    }

    @Test
    void scoredRecordsPassFindingsValidatorWithPolicyCapButUncappedFail() {
        Finding.Confirmed over = confirmed("fp-g", "CWE-327", Score5.HIGH, Score5.HIGH, Score5.HIGH);
        assertThat(FindingsValidator.validate(List.of(over), Optional.empty(), CapPolicy.NONE).ok()).isTrue();
        assertThat(FindingsValidator.validate(List.of(over), Optional.empty(), policy::cap).errors())
                .anySatisfy(e -> assertThat(e.code()).isEqualTo("F4"));
        assertThat(FindingsValidator.validate(List.of(policy.apply(over)), Optional.empty(), policy::cap).ok())
                .isTrue();
    }
}
