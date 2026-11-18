package ca.carleton.secpipe.agents.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.evidence.RegressionTestRun.Kind;
import ca.carleton.secpipe.contracts.spi.SandboxRunner.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

class TestEvidenceRunClassifyTest {

    @TempDir
    Path tmp;

    private Optional<Path> report(String xml) throws Exception {
        Path p = tmp.resolve("TEST-junit-jupiter.xml");
        Files.writeString(p, xml);
        return Optional.of(p);
    }

    private static Result exit(int code, boolean timedOut, String out) {
        return new Result(code, timedOut, out, "", List.of(), Duration.ofSeconds(3), Set.of());
    }

    @Test
    void onlyAFailureElementConfirms() throws Exception {
        assertThat(RegressionTestRun.classify(exit(1, false, ""), report(FakeSandboxRunner.FAILED_REPORT)).kind())
                .isEqualTo(Kind.FAILED_AS_EXPECTED);
        assertThat(RegressionTestRun.classify(exit(1, false, ""), report(FakeSandboxRunner.ERROR_REPORT)).kind())
                .isEqualTo(Kind.ERROR);
        assertThat(RegressionTestRun.classify(exit(0, false, ""), report(FakeSandboxRunner.PASSED_REPORT)).kind())
                .isEqualTo(Kind.PASSED);
    }

    @Test
    void timeoutAndResourceLimitWinOverAnyReport() throws Exception {
        assertThat(RegressionTestRun.classify(exit(-1, true, ""), report(FakeSandboxRunner.FAILED_REPORT)).kind())
                .isEqualTo(Kind.TIMEOUT);
        assertThat(RegressionTestRun.classify(exit(137, false, ""), report(FakeSandboxRunner.FAILED_REPORT)).kind())
                .isEqualTo(Kind.RESOURCE_LIMIT);
    }

    @Test
    void buildFailuresAreCompileErrorsOnlyWithCompilerOutput() {
        assertThat(RegressionTestRun.classify(exit(RegressionTestRun.BUILD_FAILED_EXIT, false,
                "[ERROR] COMPILATION ERROR :"), Optional.empty()).kind()).isEqualTo(Kind.COMPILE_ERROR);
        assertThat(RegressionTestRun.classify(exit(RegressionTestRun.BUILD_FAILED_EXIT, false,
                "[ERROR] offline: cannot access central"), Optional.empty()).kind()).isEqualTo(Kind.ERROR);
        assertThat(RegressionTestRun.classify(exit(1, false, ""), Optional.empty()).kind()).isEqualTo(Kind.ERROR);
    }

    @Test
    void reportWithADoctypeIsRejectedNotParsed() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<testsuite><testcase name=\"a\" classname=\"b\"><failure message=\"&x;\"/></testcase></testsuite>";
        assertThat(RegressionTestRun.classify(exit(1, false, ""), report(xxe)).kind()).isEqualTo(Kind.ERROR);
    }

    @Test
    void commandRunsTheSelectedClassOfflineWithoutEditingThePom() {
        List<String> cmd = RegressionTestRun.command("secpipe.generated.ASecurityTest");
        assertThat(cmd.get(0)).isEqualTo("bash");
        assertThat(cmd.get(2)).contains(" -o ").contains("test-compile").contains("--select-class \"$1\"");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("secpipe.generated.ASecurityTest");
    }
}
