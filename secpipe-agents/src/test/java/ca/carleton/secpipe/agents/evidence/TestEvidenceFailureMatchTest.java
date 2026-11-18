package ca.carleton.secpipe.agents.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestEvidenceFailureMatchTest {

    private static final String EXPECTED = "attacker-controlled username reached the SQL text";

    @Test
    void containmentAfterWhitespaceNormalisation() {
        assertThat(FailureMatch.matches("attacker-controlled  username\n reached\tthe SQL text: SELECT ...",
                "  attacker-controlled username reached the SQL text ")).isTrue();
        assertThat(FailureMatch.matches("attacker-controlled username reached the SQL", EXPECTED)).isFalse();
        assertThat(FailureMatch.matches("the login query did not run", EXPECTED)).isFalse();
    }

    @Test
    void blankOrNullExpectedFailureNeverMatches() {
        assertThat(FailureMatch.matches("anything", "")).isFalse();
        assertThat(FailureMatch.matches("anything", " \n ")).isFalse();
        assertThat(FailureMatch.matches("anything", null)).isFalse();
        assertThat(FailureMatch.matches(null, EXPECTED)).isFalse();
    }

    @Test
    void regressionTestRunUsesTheSameRule() {
        assertThat(RegressionTestRun.matches(EXPECTED + " ==> expected: <false>", EXPECTED)).isTrue();
        assertThat(RegressionTestRun.matches("other", EXPECTED)).isFalse();
    }
}
