package ca.carleton.secpipe.agents.testgen;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.testgen.GeneratedTestChecker.Rule;
import ca.carleton.secpipe.agents.testgen.GeneratedTestChecker.Violation;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import org.junit.jupiter.api.Test;

import java.util.List;

class GeneratedTestCheckerTest {

    /** A clean test class with {@code imports} and {@code body} spliced in. */
    private static String source(String imports, String body) {
        return """
                package secpipe.generated;

                import static org.junit.jupiter.api.Assertions.assertTrue;

                import org.junit.jupiter.api.Test;
                %s
                class SampleSecurityTest {

                    @Test
                    void payloadDoesNotReachSink() throws Exception {
                %s
                        assertTrue(!"payload".isEmpty());
                    }
                }
                """.formatted(imports, body);
    }

    private static List<Rule> rules(String src) {
        return GeneratedTestChecker.check(src).stream().map(Violation::rule).distinct().toList();
    }

    @Test
    void cleanTestHasNoViolations() {
        String src = source("import java.nio.file.Files;\nimport java.nio.file.Path;\n"
                + "import org.junit.jupiter.api.io.TempDir;\n",
                "        Path p = Files.createTempFile(\"x\", \".txt\");\n"
                        + "        String payload = \"../../../../etc/passwd\";\n");
        assertThat(GeneratedTestChecker.check(src)).isEmpty();
    }

    @Test
    void javaNetImportIsRejected() {
        String src = source("import java.net.Socket;\n", "        new Socket(\"localhost\", 80).close();\n");
        List<Violation> v = GeneratedTestChecker.check(src);
        assertThat(v).extracting(Violation::rule).contains(Rule.NETWORK);
        assertThat(v).filteredOn(x -> x.rule() == Rule.NETWORK).first()
                .satisfies(x -> assertThat(x.line()).isEqualTo(6));
    }

    @Test
    void fullyQualifiedNetworkUseAndHttpClientAreRejected() {
        assertThat(rules(source("", "        java.net.URI u = java.net.URI.create(\"http://example.com\");\n")))
                .containsExactly(Rule.NETWORK);
        assertThat(rules(source("import java.net.http.HttpClient;\n",
                "        HttpClient c = HttpClient.newHttpClient();\n"))).containsExactly(Rule.NETWORK);
        assertThat(rules(source("import javax.naming.InitialContext;\n", ""))).containsExactly(Rule.NETWORK);
    }

    @Test
    void absoluteOrEscapingFilePathsAreRejected() {
        assertThat(rules(source("import java.io.File;\n",
                "        new File(\"/etc/passwd\").exists();\n"))).containsExactly(Rule.FILESYSTEM);
        assertThat(rules(source("import java.nio.file.Files;\nimport java.nio.file.Path;\n",
                "        Files.readString(Path.of(\"../../secrets.txt\"));\n"))).containsExactly(Rule.FILESYSTEM);
        assertThat(rules(source("", "        String home = System.getProperty(\"user.home\");\n")))
                .containsExactly(Rule.FILESYSTEM);
    }

    @Test
    void referencesToSecPipeAreRejected() {
        assertThat(rules(source("import ca.carleton.secpipe.agents.testgen.GeneratedTestChecker;\n", "")))
                .containsExactly(Rule.SECPIPE_REFLECTION);
        assertThat(rules(source("",
                "        Class.forName(\"ca.carleton.secpipe.cli.Main\").getDeclaredMethods();\n")))
                .containsExactly(Rule.SECPIPE_REFLECTION);
    }

    @Test
    void systemExitAndRuntimeHaltAreRejected() {
        assertThat(rules(source("", "        System.exit(0);\n"))).containsExactly(Rule.SYSTEM_EXIT);
        assertThat(rules(source("", "        Runtime.getRuntime().halt(1);\n"))).containsExactly(Rule.SYSTEM_EXIT);
    }

    @Test
    void processCreationAndSleepAreRejected() {
        assertThat(rules(source("", "        new ProcessBuilder(\"id\").start();\n"))).containsExactly(Rule.PROCESS);
        assertThat(rules(source("", "        Runtime.getRuntime().exec(\"id\");\n"))).containsExactly(Rule.PROCESS);
        assertThat(rules(source("", "        Thread.sleep(1000);\n"))).containsExactly(Rule.SLEEP);
    }

    @Test
    void wrongPackageIsRejected() {
        String src = source("", "").replace("package secpipe.generated;", "package com.shop.app;");
        assertThat(rules(src)).containsExactly(Rule.PACKAGE);
    }

    @Test
    void missingJunit5TestOrJunit4ImportIsRejected() {
        String noTest = source("", "").replace("    @Test\n", "");
        assertThat(rules(noTest)).containsExactly(Rule.JUNIT5);
        String junit4 = source("", "").replace("import org.junit.jupiter.api.Test;", "import org.junit.Test;");
        assertThat(rules(junit4)).containsExactly(Rule.JUNIT5);
    }

    @Test
    void unparseableSourceIsRejected() {
        assertThat(rules("package secpipe.generated; class Broken {")).containsExactly(Rule.PARSE_ERROR);
    }

    @Test
    void overlongSourceIsRejected() {
        String body = "        int x = 0;\n".repeat(GeneratedTestChecker.MAX_LINES);
        assertThat(rules(source("", body))).containsExactly(Rule.TOO_LONG);
    }

    @Test
    void resultFieldsMustMatchTheSource() {
        String src = source("", "");
        TestGenResult ok = result("secpipe.generated", "SampleSecurityTest", src);
        assertThat(GeneratedTestChecker.check(ok)).isEmpty();
        TestGenResult wrongClass = result("secpipe.generated", "OtherSecurityTest", src);
        assertThat(GeneratedTestChecker.check(wrongClass)).extracting(Violation::rule)
                .containsExactly(Rule.DECLARATION_MISMATCH);
        TestGenResult wrongPkg = result("secpipe.generated.sub", "SampleSecurityTest", src);
        assertThat(GeneratedTestChecker.check(wrongPkg)).extracting(Violation::rule)
                .containsExactly(Rule.DECLARATION_MISMATCH);
    }

    private static TestGenResult result(String pkg, String cls, String src) {
        return new TestGenResult("code:CWE-89:000000000000000000000001", pkg, cls, src, List.of("A#b()"),
                List.of("' OR '1'='1"), "payload reached sink", "because");
    }
}
