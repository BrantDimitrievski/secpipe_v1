package ca.carleton.secpipe.eval.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@code scripts/eval/remediation.sh --check-env} (WO-M.4): the provider-aware API-key pre-check, run with a
 * controlled environment. No Docker, jar, key or network is needed because {@code --check-env} exits right after the
 * argument and key checks.
 */
class RemediationScriptTest {

    private record Outcome(int exit, String stdout, String stderr) { }

    private static Path script() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("scripts/eval/remediation.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("scripts/eval/remediation.sh not found above " + Path.of("").toAbsolutePath());
    }

    private static Outcome run(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("bash", script().toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("PATH", System.getenv("PATH"));
        pb.environment().putAll(env);
        Path out = Files.createTempFile("remediation-script", ".out");
        Path err = Files.createTempFile("remediation-script", ".err");
        try {
            pb.redirectOutput(out.toFile()).redirectError(err.toFile());
            Process p = pb.start();
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("script finished").isTrue();
            return new Outcome(p.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    @Test
    void anthropicWithOnlyAnOpenAiKeyFailsWithAOneLineErrorAndAHint() throws Exception {
        Outcome o = run(Map.of("OPENAI_API_KEY", "sk-secret-value"), "--provider", "anthropic", "--check-env");

        assertThat(o.exit()).isEqualTo(2);
        assertThat(o.stderr()).startsWith("error: ").contains("\nhint: ").contains("ANTHROPIC_API_KEY")
                .doesNotContain("sk-secret-value");
        assertThat(o.stderr().lines()).hasSize(2);
    }

    @Test
    void anthropicAcceptsAnyOfItsThreeVariables() throws Exception {
        for (String name : List.of("SECPIPE_API_KEY", "ANTHROPIC_API_KEY", "SECPIPE_LLM_KEY")) {
            assertThat(run(Map.of(name, "x"), "--provider", "anthropic", "--check-env").exit()).as(name).isZero();
        }
    }

    @Test
    void openAiProvidersReadOpenAiApiKeyAndAnthropicKeyDoesNotCount() throws Exception {
        assertThat(run(Map.of("OPENAI_API_KEY", "x"), "--provider", "openai", "--check-env").exit()).isZero();
        assertThat(run(Map.of("ANTHROPIC_API_KEY", "x"), "--provider", "openai", "--check-env").exit()).isEqualTo(2);
        assertThat(run(Map.of("OPENAI_API_KEY", "x"), "--provider", "openai-compatible", "--check-env").exit())
                .as("openai-compatible without a base URL").isEqualTo(2);
        assertThat(run(Map.of("OPENAI_API_KEY", "x"), "--provider", "openai-compatible", "--llm-base-url",
                "http://localhost:8000/v1", "--check-env").exit()).isZero();
    }

    @Test
    void noneNeedsNoKeyOllamaIsNotBuiltAndFakeNeedsItsTwoVariables() throws Exception {
        assertThat(run(Map.of(), "--provider", "none", "--check-env").exit()).isZero();
        Outcome ollama = run(Map.of(), "--provider", "ollama", "--check-env");
        assertThat(ollama.exit()).isEqualTo(2);
        assertThat(ollama.stderr()).contains("hint: use --provider openai-compatible --llm-base-url "
                + "http://localhost:11434/v1 --model <name>");
        assertThat(run(Map.of(), "--provider", "fake", "--check-env").exit()).isEqualTo(2);
        assertThat(run(Map.of("SECPIPE_ALLOW_FAKE_LLM", "1"), "--provider", "fake", "--check-env").exit())
                .isEqualTo(2);
        assertThat(run(Map.of("SECPIPE_ALLOW_FAKE_LLM", "1", "SECPIPE_FAKE_LLM_DIR", "/tmp/fixtures"), "--provider",
                "fake", "--check-env").exit()).isZero();
    }

    @Test
    void checkEnvWithFromSkipsTheKeyCheck() throws Exception {
        Path findings = Files.createTempFile("findings", ".json");
        try {
            Outcome o = run(Map.of(), "--check-env", "--from", findings.toString());
            assertThat(o.exit()).isZero();
            assertThat(o.stdout()).contains("check-env: ok");
        } finally {
            Files.deleteIfExists(findings);
        }
    }

    @Test
    void unknownProviderIsAUsageErrorAndCheckEnvNeverTouchesDocker() throws Exception {
        Outcome bad = run(Map.of(), "--provider", "bogus", "--check-env");
        assertThat(bad.exit()).isEqualTo(2);
        assertThat(bad.stderr()).contains("hint: ");

        // the default provider (anthropic) is checked too, and --check-env exits before any Docker check
        Outcome ok = run(Map.of("ANTHROPIC_API_KEY", "x"), "--check-env");
        assertThat(ok.exit()).isZero();
        assertThat(ok.stdout()).contains("check-env: ok");
    }
}
