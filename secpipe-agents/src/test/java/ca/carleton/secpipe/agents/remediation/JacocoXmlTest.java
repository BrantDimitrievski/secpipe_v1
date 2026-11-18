package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link JacocoXml}: line coverage from JaCoCo XML reports (WO-M.2). */
class JacocoXmlTest {

    static Path resource(String name) {
        try {
            return Path.of(Objects.requireNonNull(JacocoXmlTest.class.getResource("jacoco/" + name)).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @TempDir
    Path tmp;

    @Test
    void readsExecutableAndCoveredLinesPerSourceFileDespiteTheDoctype() {
        JacocoXml.Coverage cov = JacocoXml.parse(resource("login-line22-missed.xml")).orElseThrow();
        String login = "com/shop/app/LoginServlet.java";
        assertTrue(cov.hasFile(login));
        assertTrue(cov.hasFile("com/shop/app/util/Strings.java"));
        assertEquals(Optional.of(true), cov.covered(login, 20));
        assertEquals(Optional.of(true), cov.covered(login, 21));
        assertEquals(Optional.of(false), cov.covered(login, 22));
        assertEquals(Optional.empty(), cov.covered(login, 11), "imports are not executable");
        assertEquals(Optional.empty(), cov.covered("com/shop/app/Other.java", 1));
        assertEquals(Optional.of(false), cov.covered("com/shop/app/util/Strings.java", 3));
    }

    @Test
    void anyCoveredInstructionCoversTheLine() {
        JacocoXml.Coverage cov = JacocoXml.parse(resource("login-covered.xml")).orElseThrow();
        assertEquals(Optional.of(true), cov.covered("com/shop/app/LoginServlet.java", 22));
        assertEquals(Optional.of(true), cov.covered("com/shop/app/LoginServlet.java", 23), "partial branches");
    }

    @Test
    void notAReportMissingOrMalformedIsEmpty() throws Exception {
        assertEquals(Optional.empty(), JacocoXml.parse(new ByteArrayInputStream(
                "<testsuite name=\"x\"/>".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Optional.empty(), JacocoXml.parse(new ByteArrayInputStream(
                "<report><package".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Optional.empty(), JacocoXml.parse(tmp.resolve("missing.xml")));
        Path bad = tmp.resolve("bad.xml");
        Files.writeString(bad, "<report><package name=\"a\"><sourcefile name=\"A.java\"><line nr=\"x\" ci=\"1\"/>"
                + "</sourcefile></package></report>");
        assertEquals(Optional.empty(), JacocoXml.parse(bad));
    }

    @Test
    void neverResolvesExternalEntities() throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET");
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE report [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<report><package name=\"&x;\"><sourcefile name=\"A.java\"><line nr=\"1\" mi=\"0\" ci=\"1\"/>"
                + "</sourcefile></package></report>";
        Optional<JacocoXml.Coverage> cov = JacocoXml.parse(new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)));
        assertFalse(cov.map(c -> c.lines().keySet().toString().contains("TOP-SECRET")).orElse(false),
                String.valueOf(cov));
    }
}
