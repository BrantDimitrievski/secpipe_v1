package ca.carleton.secpipe.cli.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.cli.SecPipeCli;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.llm.SecretLeakAssertions;
import ca.carleton.secpipe.validators.SchemaRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The e2e fixture scan (folded from WO-0.2): {@code fixtures/e2e/sqli-min} through the real pipeline with no Docker,
 * once with AI off and once with the fake LLM, compared with {@code expected.json} (fingerprints and verdicts).
 */
class E2eFixtureScanTest {

    static final Path FIXTURE = Path.of("..", "fixtures", "e2e", "sqli-min").toAbsolutePath().normalize();
    static final String API_KEY = "sk-e2e-test-0123456789abcdefSECRET";

    @TempDir
    Path tmp;

    record Run(int exit, String out, String err, Path dir) { }

    private Run scan(Map<String, String> env, String... extra) {
        Path dir = tmp.resolve("out-" + Math.abs(String.join(" ", extra).hashCode()) + "-" + System.nanoTime());
        List<String> args = new ArrayList<>(List.of("scan", FIXTURE.toString(), "--no-sandbox", "-o", dir.toString()));
        args.addAll(List.of(extra));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = SecPipeCli.run(args.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env);
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), dir);
    }

    private static Map<String, String> fakeEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("SECPIPE_ALLOW_FAKE_LLM", "1");
        env.put("SECPIPE_FAKE_LLM_DIR", FIXTURE.resolve("llm").toString());
        return env;
    }

    private static List<String> actual(Path findings) {
        return ContractJson.readFindings(findings).stream()
                .map(f -> f.fingerprint() + " " + f.verdict().name().toLowerCase(java.util.Locale.ROOT)).sorted().toList();
    }

    private static List<String> expected(String key) {
        JsonNode root = ContractJson.mapper().readTree(FIXTURE.resolve("expected.json").toFile());
        List<String> out = new ArrayList<>();
        root.get(key).forEach(n -> out.add(n.get("fingerprint").asString() + " " + n.get("verdict").asString()));
        return out.stream().sorted().toList();
    }

    private static int count(String key, String verdict) {
        return ContractJson.mapper().readTree(FIXTURE.resolve("expected.json").toFile()).get(key).get(verdict).asInt();
    }

    @Test
    void noAiScanMatchesExpectedFingerprintsAndVerdicts() {
        Run run = scan(Map.of(), "--no-ai");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(actual(run.dir().resolve("findings.json"))).isEqualTo(expected("no_ai_findings"));
        assertThat(actual(run.dir().resolve("findings.json")))
                .filteredOn(s -> s.endsWith(" needs_validation")).hasSize(count("no_ai", "needs_validation"));
        assertThat(actual(run.dir().resolve("findings.json")))
                .filteredOn(s -> s.endsWith(" rejected")).hasSize(count("no_ai", "rejected"));
    }

    @Test
    void fakeLlmScanMatchesExpectedFingerprintsAndVerdicts() throws IOException {
        Run run = scan(fakeEnv(), "--profile", "quick", "--llm-provider", "fake", "-q");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(actual(run.dir().resolve("findings.json"))).isEqualTo(expected("fake_no_sandbox_findings"));
        // the survivor of the fake disprover stays needs_validation without a sandbox (WO-G.7)
        assertThat(Files.readString(run.dir().resolve("findings.json"))).contains("sandbox unavailable");
        assertThat(run.out()).as("-q prints no summary").isEmpty();
    }

    @Test
    void fakeLlmRunMetadataReportsUsageAndIsByteStable() throws IOException {
        Run a = scan(fakeEnv(), "--profile", "quick", "--llm-provider", "fake", "-q");
        Run b = scan(fakeEnv(), "--profile", "quick", "--llm-provider", "fake", "-q");

        assertThat(a.exit()).as(a.err()).isZero();
        String json = Files.readString(a.dir().resolve("run-metadata.json"));
        assertThat(SchemaRegistry.shared().validate("run-metadata.schema.json", json).errors()).isEmpty();
        JsonNode ai = ContractJson.mapper().readTree(json).get("ai");
        assertThat(ai.get("requested_mode").asString()).isEqualTo("fake");
        assertThat(ai.get("effective_mode").asString()).isEqualTo("fake");
        assertThat(ai.get("usage")).isNotEmpty();
        assertThat(ai.get("usage").get(0).get("calls").asInt()).isPositive();
        assertThat(ai.get("models")).isNotEmpty();
        // started/finished and stage timings vary with the wall clock; the ai block must not
        assertThat(ai.toString()).isEqualTo(
                ContractJson.mapper().readTree(Files.readString(b.dir().resolve("run-metadata.json"))).get("ai")
                        .toString());
    }

    @Test
    void noAiRunMetadataHasAnEmptyAiBlockAndEffectiveModeNone() throws IOException {
        Run run = scan(Map.of(), "--no-ai");

        JsonNode ai = ContractJson.mapper().readTree(Files.readString(run.dir().resolve("run-metadata.json")))
                .get("ai");
        assertThat(ai.get("effective_mode").asString()).isEqualTo("none");
        assertThat(ai.get("usage")).isEmpty();
        assertThat(ai.get("models")).isEmpty();
    }

    @Test
    void writesEveryReportFileAndAConsoleSummaryLikeTheDemo() {
        Run run = scan(Map.of(), "--no-ai");

        for (String f : List.of("findings.json", "coverage-ledger.json", "run-metadata.json", "secpipe.sarif",
                "report.md")) {
            assertThat(run.dir().resolve(f)).as(f).isRegularFile();
        }
        assertThat(run.out()).contains("findings: 0 confirmed, 1 needs_validation, 1 rejected")
                .contains("findings.json: ").contains("report.md: ")
                .contains("needs_validation CWE-89").contains("LoginServlet.java:21");
    }

    @Test
    void findingsAndLedgerAreByteStableAcrossRuns() throws IOException {
        Run a = scan(Map.of(), "--no-ai");
        Run b = scan(Map.of(), "--no-ai");

        for (String f : List.of("findings.json", "coverage-ledger.json", "secpipe.sarif")) {
            assertThat(Files.readString(a.dir().resolve(f))).as(f).isEqualTo(Files.readString(b.dir().resolve(f)));
        }
        // state.json is engine scratch: it lives under .work and is never compared
        assertThat(a.dir().resolve(".work/state.json")).isRegularFile();
    }

    @Test
    void failOnNeedsValidationExitsOne() {
        assertThat(scan(Map.of(), "--no-ai", "--fail-on-needs-validation").exit()).isEqualTo(1);
    }

    @Test
    void apiKeyInTheEnvironmentNeverReachesAnyOutputOrLog() throws IOException {
        Map<String, String> env = fakeEnv();
        env.put("SECPIPE_API_KEY", API_KEY);
        env.put("ANTHROPIC_API_KEY", API_KEY);
        Run run = scan(env, "--llm-provider", "fake", "-v");

        assertThat(run.exit()).as(run.err()).isZero();
        SecretLeakAssertions.assertNoSecret(API_KEY, run.out(), "stdout");
        SecretLeakAssertions.assertNoSecret(API_KEY, run.err(), "stderr");
        try (Stream<Path> files = Files.walk(run.dir())) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                SecretLeakAssertions.assertNoSecret(API_KEY, Files.readString(p), p.toString());
            }
        }
    }
}
