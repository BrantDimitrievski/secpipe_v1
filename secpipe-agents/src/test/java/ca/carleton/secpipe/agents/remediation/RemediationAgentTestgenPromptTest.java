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
 * WO-M.2 (M.3 review follow-up): concrete safe-API guidance for every sink family testgen covers (CWE-89, CWE-78,
 * CWE-22, CWE-502).
 */
class RemediationAgentTestgenPromptTest {

    private static String system() throws IOException {
        try (InputStream in = RemediationAgentTestgenPromptTest.class.getClassLoader()
                .getResourceAsStream("prompts/testgen.v1.md")) {
            assertNotNull(in, "prompts/testgen.v1.md on the classpath");
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
    void testgenPromptGivesConcreteSafeApiGuidanceForOsCommands() throws IOException {
        String s = system();
        assertTrue(s.contains("OS command (CWE-78)"), s);
        assertTrue(s.contains("unsafe Runtime.exec(String)"), s);
        assertTrue(s.contains("new ProcessBuilder(List<String>)"), s);
        assertTrue(s.contains("Runtime.exec(String[])"), s);
        assertTrue(s.contains("never start a real process"), s);
    }

    @Test
    void testgenPromptGivesConcreteSafeApiGuidanceForPathTraversal() throws IOException {
        String s = system();
        assertTrue(s.contains("Path traversal (CWE-22)"), s);
        assertTrue(s.contains("unsafe new File(String) or Paths.get(...)"), s);
        assertTrue(s.contains("normalize() followed by a startsWith(base) check"), s);
    }

    @Test
    void testgenPromptGivesConcreteSafeApiGuidanceForDeserialization() throws IOException {
        String s = system();
        assertTrue(s.contains("Deserialization (CWE-502)"), s);
        assertTrue(s.contains("unsafe raw ObjectInputStream.readObject()"), s);
        assertTrue(s.contains("ObjectInputFilter (ObjectInputStream.setObjectInputFilter)"), s);
        assertTrue(s.contains("allow-list of classes"), s);
    }

    @Test
    void testgenPromptGivesConcreteSafeApiGuidanceForSql() throws IOException {
        String s = system();
        assertTrue(s.contains("SQL (CWE-89): unsafe Statement.executeQuery(String)"), s);
        assertTrue(s.contains("`?` placeholders + setString/setObject + executeQuery()"), s);
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
