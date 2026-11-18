package ca.carleton.secpipe.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;

class RedactorTest {

    @Test
    void scrubsKeyValuesFromEnvironment() {
        Redactor r = Redactor.forEnv(
                Map.of("ANTHROPIC_API_KEY", "abc123secretvalue", "MY_SERVICE_TOKEN", "tok-999999"));
        String out = r.redact("401 for key abc123secretvalue and tok-999999");
        assertEquals("401 for key [REDACTED] and [REDACTED]", out);
    }

    @Test
    void scrubsWellKnownKeyShapesEvenWithoutEnv() {
        String out = Redactor.shapesOnly()
                .redact("auth: Bearer eyJhbGciOiJIUzI1NiJ9.x key=sk-ant-api03-ABCDEFGHIJKLMNOP"
                        + " gh=ghp_abcdefghijklmnopqrstuvwxyz");
        assertFalse(out.contains("sk-ant"), out);
        assertFalse(out.contains("eyJhbGci"), out);
        assertFalse(out.contains("ghp_"), out);
    }

    @Test
    void leavesNonSecretVariablesAndShortValuesAlone() {
        Redactor r = Redactor.forEnv(Map.of("HOME", "/home/user", "SECPIPE_API_KEY", "abc"));
        assertEquals("cd /home/user abc", r.redact("cd /home/user abc"));
    }

    @Test
    void absentTextBecomesEmpty() {
        assertEquals("", Redactor.shapesOnly().redact(null));
        assertTrue(Redactor.isSecretName("openai_api_key"));
    }
}
