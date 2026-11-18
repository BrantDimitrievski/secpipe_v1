package ca.carleton.secpipe.agents.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence.Phase;
import org.junit.jupiter.api.Test;

class TestEvidenceIdTest {

    @Test
    void idIsDeterministicAndMatchesTheSchemaPattern() {
        String id = TestEvidenceId.of(EvidenceFixtures.FP, Phase.PRE_FIX, 1);
        assertThat(id).matches("^te-[0-9a-f]{12}$");
        assertThat(TestEvidenceId.of(EvidenceFixtures.FP, Phase.PRE_FIX, 1)).isEqualTo(id);
    }

    @Test
    void fingerprintPhaseAndAttemptAllChangeTheId() {
        String id = TestEvidenceId.of(EvidenceFixtures.FP, Phase.PRE_FIX, 1);
        assertThat(TestEvidenceId.of(EvidenceFixtures.FP, Phase.PRE_FIX, 2)).isNotEqualTo(id);
        assertThat(TestEvidenceId.of(EvidenceFixtures.FP, Phase.POST_FIX, 1)).isNotEqualTo(id);
        assertThat(TestEvidenceId.of(EvidenceFixtures.FP_MALFORMED, Phase.PRE_FIX, 1)).isNotEqualTo(id);
        assertThatThrownBy(() -> TestEvidenceId.of(EvidenceFixtures.FP, Phase.PRE_FIX, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
