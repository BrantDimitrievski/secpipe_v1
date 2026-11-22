package ca.carleton.secpipe.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.config.ConfigResolver;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ScanCommandTest {

    static final Path FIXTURE = Path.of("..", "fixtures", "e2e", "sqli-min").toAbsolutePath().normalize();

    @TempDir
    Path tmp;

    record Res(int exit, String out, String err) { }

    private static Res cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = SecPipeCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), env);
        return new Res(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Res cli(String... args) {
        return cli(Map.of(), args);
    }

    private static void assertUsageError(Res r, String problem) {
        assertThat(r.exit()).isEqualTo(SecPipeCli.EXIT_USAGE);
        assertThat(r.err()).contains("error: ").contains(problem).contains("\nhint: ");
        assertThat(r.err()).doesNotContain("\tat ");
    }

    @Test
    void versionAndHelp() {
        Res v = cli("--version");
        assertThat(v.exit()).isZero();
        assertThat(v.out().strip()).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");

        Res h = cli("--help");
        assertThat(h.exit()).isZero();
        assertThat(h.out()).contains("scan").contains("validate");

        Res sh = cli("scan", "--help");
        assertThat(sh.exit()).isZero();
        assertThat(sh.out()).contains("--llm-provider").contains("--fail-on-needs-validation").contains("--no-ai")
                .contains("-o, --out");
    }

    @Test
    void noCommandAndUnknownOptionsAreUsageErrors() {
        assertUsageError(cli(), "no command given");
        assertUsageError(cli("scan", "--bogus"), "--bogus");
        assertUsageError(cli("frobnicate"), "frobnicate");
    }

    @Test
    void configProblemsExitTwoWithTheResolverHint() {
        assertUsageError(cli("scan", FIXTURE.toString(), "--llm-provider", "copilot"), "copilot");
        assertUsageError(cli("scan", FIXTURE.toString(), "--profile", "turbo"), "turbo");
        assertUsageError(cli("scan", FIXTURE.toString(), "-q", "-v"), "quiet and verbose");
        // fake is refused unless SECPIPE_ALLOW_FAKE_LLM=1
        assertUsageError(cli("scan", FIXTURE.toString(), "--llm-provider", "fake"), "fake");
    }

    @Test
    void missingTargetIsAUsageError() {
        assertUsageError(cli("scan", tmp.resolve("nope").toString(), "-o", tmp.resolve("o").toString()),
                "scan target is not a directory");
        assertThat(tmp.resolve("o")).doesNotExist();
    }

    @Test
    void unsupportedPrintPromptsAndEngineAreRefusedBeforeAnyWork() {
        Path out = tmp.resolve("o");
        assertUsageError(cli("scan", FIXTURE.toString(), "--print-prompts", "-o", out.toString()), "--print-prompts");
        assertUsageError(cli("scan", FIXTURE.toString(), "--engine", "langgraph4j", "--no-ai", "--no-sandbox",
                "-o", out.toString()), "langgraph4j");
        assertThat(out.resolve("findings.json")).doesNotExist();
    }

    @Test
    void ollamaProviderIsRefusedWithTheWorkingRouteBeforeAnyWork() {
        Path out = tmp.resolve("o");
        Res r = cli("scan", FIXTURE.toString(), "--llm-provider", "ollama", "-o", out.toString());
        assertUsageError(r, "ollama");
        assertThat(r.err()).contains("hint: use --llm-provider openai-compatible --llm-base-url "
                + "http://localhost:11434/v1 --model <name>");
        assertUsageError(cli("scan", FIXTURE.toString(), "--offline", "--llm-provider", "ollama", "-o",
                out.toString()), "ollama");
        assertThat(out.resolve("findings.json")).doesNotExist();
    }

    @Test
    void noAiStillOverridesOllamaAndTheScanRuns() {
        Path out = tmp.resolve("o");
        Res r = cli("scan", FIXTURE.toString(), "--no-ai", "--llm-provider", "ollama", "--no-sandbox", "--fail-on",
                "none", "-o", out.toString());
        assertThat(r.exit()).isZero();
        assertThat(out.resolve("findings.json")).exists();
    }

    @Test
    void helpNoLongerListsOllamaAsAWorkingProvider() {
        Res sh = cli("scan", "--help");
        assertThat(sh.out()).contains("none|anthropic|openai|openai-compatible").contains("not built")
                .doesNotContain("openai-compatible|ollama").doesNotContain("unless ollama")
                .contains("No network (implies --llm-provider none)");
    }

    private static Path smokeScript() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("scripts/eval/smoke.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("scripts/eval/smoke.sh not found");
    }

    private Res smoke(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", smokeScript().toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", System.getenv("PATH"));
        pb.environment().putAll(env);
        Path o = Files.createTempFile(tmp, "smoke", ".out");
        Path e = Files.createTempFile(tmp, "smoke", ".err");
        pb.redirectOutput(o.toFile()).redirectError(e.toFile());
        Process p = pb.start();
        assertThat(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).as("script finished").isTrue();
        return new Res(p.exitValue(), Files.readString(o, StandardCharsets.UTF_8),
                Files.readString(e, StandardCharsets.UTF_8));
    }

    @Test
    void smokeCheckEnvFailsWithoutAKeyAndPassesWithOneWithoutEchoingIt() throws Exception {
        Res none = smoke(Map.of(), "--provider", "anthropic", "--check-env");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("error: ").contains("\nhint: ");

        Res ok = smoke(Map.of("ANTHROPIC_API_KEY", "sk-ant-secret-value"), "--provider", "anthropic", "--check-env");
        assertThat(ok.exit()).isZero();
        assertThat(ok.out() + ok.err()).doesNotContain("sk-ant-secret-value");

        Res ollama = smoke(Map.of(), "--provider", "ollama", "--check-env");
        assertThat(ollama.exit()).isEqualTo(2);
        assertThat(ollama.err()).contains("hint: use --provider openai-compatible --llm-base-url "
                + "http://localhost:11434/v1 --model <name>");
    }

    @Test
    void optionsReachTheResolverAsTyped() {
        CommandLine cl = SecPipeCli.commandLine(System.out, System.err, Map.of());
        CommandLine.ParseResult parsed = cl.parseArgs("scan", "proj", "-o", "out", "--deny-path", "**/a,b/**",
                "--deny-path", "**/c/**", "--no-ai", "-q", "--model-for", "disprover=m1", "--max-agent-calls", "7");
        Map<String, String> map = ScanCommand.overrides(parsed.subcommand());

        assertThat(map).containsEntry("target", "proj").containsEntry("out", "out")
                .containsEntry("deny-path", "**/a,b/**" + ConfigResolver.REPEAT_SEPARATOR + "**/c/**")
                .containsEntry("no-ai", "").containsEntry("quiet", "").containsEntry("model-for", "disprover=m1")
                .containsEntry("max-agent-calls", "7");
        SecPipeConfig cfg = ConfigResolver.resolve(map, Map.of(), Optional.empty());
        assertThat(cfg.denyPaths()).containsExactly("**/a,b/**", "**/c/**");
        assertThat(cfg.maxAgentCalls()).isEqualTo(7);
        assertThat(cfg.verbosity()).isEqualTo(SecPipeConfig.Verbosity.QUIET);
    }

    @Test
    void exitCodesFollowTheFrozenTable() throws IOException {
        Path nv = tmp.resolve("nv.json");
        Res run = cli("scan", FIXTURE.toString(), "--no-ai", "--no-sandbox", "-q", "-o", tmp.resolve("o").toString());
        assertThat(run.exit()).as(run.err()).isZero();
        Files.copy(tmp.resolve("o/findings.json"), nv);
        List<Finding> findings = ContractJson.readFindings(nv);

        SecPipeConfig plain = ConfigResolver.resolve(Map.of(), Map.of(), Optional.empty());
        SecPipeConfig failNv = ConfigResolver.resolve(Map.of("fail-on-needs-validation", ""), Map.of(),
                Optional.empty());
        SecPipeConfig failIncomplete = ConfigResolver.resolve(Map.of("fail-on-incomplete", "",
                "fail-on-needs-validation", ""), Map.of(), Optional.empty());

        assertThat(new ScanOutcome(plain, findings, false, Optional.empty()).exitCode()).isEqualTo(0);
        assertThat(new ScanOutcome(plain, findings, true, Optional.of("x")).exitCode()).isEqualTo(0);
        assertThat(new ScanOutcome(failNv, findings, false, Optional.empty()).exitCode()).isEqualTo(1);
        assertThat(new ScanOutcome(failNv, List.of(), false, Optional.empty()).exitCode()).isEqualTo(0);
        assertThat(new ScanOutcome(failIncomplete, findings, true, Optional.of("x")).exitCode()).isEqualTo(3);
        assertThat(new ScanOutcome(failIncomplete, findings, false, Optional.empty()).exitCode()).isEqualTo(1);
    }

    @Test
    void validateReportsValidInvalidAndUnknownKinds() throws IOException {
        Res run = cli("scan", FIXTURE.toString(), "--no-ai", "--no-sandbox", "-q", "-o", tmp.resolve("o").toString());
        assertThat(run.exit()).as(run.err()).isZero();

        Res ok = cli("validate", "findings", tmp.resolve("o/findings.json").toString(), "--root", FIXTURE.toString());
        assertThat(ok.exit()).as(ok.err()).isZero();
        assertThat(ok.out()).startsWith("valid: ");
        assertThat(cli("validate", "run", tmp.resolve("o/run-metadata.json").toString()).exit()).isZero();

        Path bad = Files.writeString(tmp.resolve("bad.json"), "[{\"verdict\":\"confirmed\"}]");
        Res invalid = cli("validate", "findings", bad.toString());
        assertThat(invalid.exit()).isEqualTo(1);
        assertThat(invalid.err()).startsWith("invalid: ");

        assertUsageError(cli("validate", "agent:no-such-schema", bad.toString()), "unknown kind");
        assertUsageError(cli("validate", "findings", tmp.resolve("missing.json").toString()), "cannot read");
    }
}
