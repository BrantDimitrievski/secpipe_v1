package ca.carleton.secpipe.agents.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * [DOCKER] WO-G.7 acceptance: fixtures/e2e/sqli-min through the real Docker sandbox (prefetch, offline compile, JUnit
 * console) with the fake LLM's regression test. The LoginServlet finding must end as in {@code expected-sandbox.json}.
 * Work directories are not deleted: the container user (uid 10001) owns parts of them; CI runners are ephemeral.
 */
@Tag("sandbox")
class TestEvidenceSandboxTest {

    @Test
    void sqliMinLoginServletIsConfirmedByAFailingRegressionTest() throws Exception {
        Optional<SandboxRunner> runner = ServiceLoader.load(SandboxRunner.class).findFirst();
        // In CI (runner image configured) the sandbox must be usable: fail instead of silently skipping.
        boolean ci = System.getenv("SECPIPE_RUNNER_IMAGE") != null;
        if (ci) {
            assertThat(runner).as("SandboxRunner via ServiceLoader").isPresent();
            assertThat(runner.get().probe().available()).as("%s", runner.get().probe()).isTrue();
        }
        assumeTrue(runner.isPresent(), "no SandboxRunner on the class path");
        assumeTrue(runner.get().probe().available(), "Docker sandbox not available");
        Path work = Files.createTempDirectory("secpipe-g7-");
        String fp = EvidenceFixtures.FP_SQLI_MIN;
        EvidenceFixtures.Log log = new EvidenceFixtures.Log();

        StateDelta delta = TestEvidenceNode.evaluate(
                EvidenceFixtures.state(List.of(EvidenceFixtures.candidate(fp)), Map.of(fp, EvidenceFixtures.awaiting(fp)),
                        Map.of(fp, EvidenceFixtures.draft(fp))),
                EvidenceFixtures.settings(ProviderMode.FAKE, EvidenceFixtures.SQLI_MIN, work),
                EvidenceFixtures.fake(), runner, log);

        ValidationResult v = FindingsValidator.validate(delta.putRecords(), Optional.of(EvidenceFixtures.SQLI_MIN),
                CapPolicy.NONE);
        assertThat(v.ok()).as("%s", v).isTrue();
        JsonNode expected = ContractJson.mapper().readTree(
                Files.readString(EvidenceFixtures.SQLI_MIN.resolve("expected-sandbox.json"))).get("findings").get(0);
        assertThat(delta.putRecords()).as("log: %s", log.lines).hasSize(1);
        Finding f = delta.putRecords().get(0);
        assertThat(f.trace().get(f.trace().size() - 1).file()).isEqualTo(expected.get("file").asString());
        assertThat(f.secpipe().cwe()).contains(expected.get("cwe").asString());
        assertThat(f.verdict().name().toLowerCase(Locale.ROOT)).as("record %s, log %s", f, log.lines)
                .isEqualTo(expected.get("verdict").asString());
        assertThat(f.secpipe().testEvidence().get(f.secpipe().testEvidence().size() - 1).outcome().name()
                .toLowerCase(Locale.ROOT)).isEqualTo(expected.get("test_outcome").asString());
    }
}
