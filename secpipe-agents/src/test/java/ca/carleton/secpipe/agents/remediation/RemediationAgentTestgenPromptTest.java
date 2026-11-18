package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * WO-M.3: the testgen prompt tells the generator to write regression tests that remediation can verify. The stub
 * records both the unsafe and the safe API of the sink family, the test asserts on the payload reaching the query
 * text, and assumptions are only for environment setup, so a correct fix makes the test pass instead of skip.
 */
class RemediationAgentTestgenPromptTest {

    private static String system() throws IOException {
        try (InputStream in = RemediationAgentTestgenPromptTest.class.getClassLoader()
                .getResourceAsStream("prompts/testgen.v1.txt")) {
            assertNotNull(in, "prompts/testgen.v1.txt on the classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.substring(text.indexOf("===SYSTEM==="), text.indexOf("===USER==="));
        }
    }

    @Test
    void testgenPromptAsksForStubsThatRecordBothTheUnsafeAndTheSafeApi() throws IOException {
        String s = system();
        assertTrue(s.contains("record BOTH the unsafe and the safe API"), s);
        assertTrue(s.contains("Statement.executeQuery(String)"), s);
        assertTrue(s.contains("Connection.prepareStatement(String) + setString"), s);
        assertTrue(s.contains("executeQuery()"), s);
        assertTrue(s.contains("PASS on a correct fix"), s);
    }

    @Test
    void testgenPromptAssertsOnTheAttackerVisibleEffectAndKeepsAssumptionsForSetupOnly() throws IOException {
        String s = system();
        assertTrue(s.contains("payload reaching the command, query or path text"), s);
        assertTrue(s.contains("Use Assumptions only for environment setup"), s);
        assertTrue(s.contains("must not make the test skip"), s);
        assertFalse(s.contains("the sink was\n  reached) must be a JUnit assumption"), "old unsafe-only wording");
    }
}
