package ca.carleton.secpipe.llm.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.ProviderClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RateLimitedException;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RawCompletion;
import ca.carleton.secpipe.llm.ProviderStatusException;
import ca.carleton.secpipe.llm.SecretLeakAssertions;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

class AnthropicProviderTest {

    private static final String KEY = "sk-ant-test-key-0123456789";
    private static final String PATH = "/v1/messages";
    private static final LlmClient.Request REQUEST =
            new LlmClient.Request("sys prompt", "user prompt", "agent/hunter", 500, 0.2, "hunter-v1");

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static String fixture(String name) {
        try (var in = AnthropicProviderTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SecPipeConfig config(Optional<String> baseUrl) {
        return new SecPipeConfig(Path.of("."), Path.of("out"), RunProfile.values()[0], SecPipeConfig.ScopeMode.FULL,
                Optional.empty(), Optional.empty(), ProviderMode.ANTHROPIC, Optional.empty(), Map.of(), Map.of(),
                baseUrl, Set.of(), Set.of(), 10, 3, 5, SecPipeConfig.FixMode.FALSE, false, true, Optional.empty(),
                false, false, Set.of(), Optional.empty(), false, List.of(), 80, 6, Set.of(), false, false, false,
                SecPipeConfig.GraphEngine.PLAIN, Duration.ofMinutes(1), SecPipeConfig.Verbosity.NORMAL);
    }

    private ProviderClient client(Map<String, String> env) {
        return new AnthropicProviderFactory().create(config(Optional.of(wm.baseUrl())), env);
    }

    @Test
    void happyPathSendsKeyHeaderFromEnvAndJoinsTextBlocks() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(fixture("messages-ok.json"))));

        RawCompletion out = client(Map.of("ANTHROPIC_API_KEY", KEY)).complete("claude-x", REQUEST);

        assertThat(out.text()).isEqualTo("{\"verdict\":\"ok\"}");
        assertThat(out.inputTokens()).isEqualTo(21);
        assertThat(out.outputTokens()).isEqualTo(7);
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-api-key", equalTo(KEY))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(containing("\"model\":\"claude-x\""))
                .withRequestBody(containing("\"max_tokens\":500"))
                .withRequestBody(containing("exactly one JSON object"))
                .withRequestBody(containing("user prompt")));
    }

    @Test
    void secpipeApiKeyTakesPrecedence() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(fixture("messages-ok.json"))));

        client(Map.of("SECPIPE_API_KEY", "sk-ant-first-0000001", "ANTHROPIC_API_KEY", KEY)).complete("m", REQUEST);

        wm.verify(postRequestedFor(urlEqualTo(PATH)).withHeader("x-api-key", equalTo("sk-ant-first-0000001")));
    }

    @Test
    void rateLimitIsRetryableAndCarriesRetryAfter() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429).withHeader("retry-after", "3")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}")));

        assertThatThrownBy(() -> client(Map.of("ANTHROPIC_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(3)));
    }

    @Test
    void overloadedIsRetryableStatus() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(529)));

        assertThatThrownBy(() -> client(Map.of("ANTHROPIC_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void unauthorizedIsNotRetriedHasHintAndNeverEchoesKey() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"message\":\"invalid x-api-key " + KEY + "\"}}")));

        assertThatThrownBy(() -> client(Map.of("ANTHROPIC_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("hint:").contains("ANTHROPIC_API_KEY");
                    SecretLeakAssertions.assertNoSecret(KEY, e);
                });
    }

    @Test
    void malformedBodyIsAnErrorWithoutRepair() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(fixture("messages-malformed.json"))));

        assertThatThrownBy(() -> client(Map.of("ANTHROPIC_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class,
                        e -> assertThat(e.getMessage()).contains("malformed provider response"));
    }

    @Test
    void missingKeyAtCallTimeFailsWithHintAndSendsNothing() {
        wm.resetAll();
        assertThatThrownBy(() -> client(Map.of()).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.getMessage()).contains("hint:");
                });
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void probeNeedsKeyAndAcceptsAnyAlias() {
        assertThat(new AnthropicProviderFactory().probe(config(Optional.empty()), Map.of()))
                .hasValueSatisfying(m -> assertThat(m).contains("hint:").contains("ANTHROPIC_API_KEY"));
        assertThat(new AnthropicProviderFactory().probe(config(Optional.empty()), Map.of("SECPIPE_LLM_KEY", KEY)))
                .isEmpty();
    }

    @Test
    void factoryIsRegisteredWithServiceLoaderAndDefaultsCoverEveryRole() {
        List<LlmProviderFactory> found = ServiceLoader.load(LlmProviderFactory.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        assertThat(found).extracting(LlmProviderFactory::mode).containsExactly(ProviderMode.ANTHROPIC);
        assertThat(found.get(0).defaultModels()).containsOnlyKeys(AgentRole.values());
    }
}
