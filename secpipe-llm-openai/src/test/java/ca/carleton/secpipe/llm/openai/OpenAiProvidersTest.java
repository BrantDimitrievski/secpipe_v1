package ca.carleton.secpipe.llm.openai;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

class OpenAiProvidersTest {

    private static final String KEY = "sk-test-OPENAI-key-0123456789";
    private static final String PATH = "/v1/chat/completions";
    private static final LlmClient.Request REQUEST =
            new LlmClient.Request("sys prompt", "user prompt", "agent/hunter", 500, 0.2, "hunter-v1");

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static String fixture(String name) {
        try (var in = OpenAiProvidersTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SecPipeConfig config(ProviderMode mode, Optional<String> baseUrl) {
        return new SecPipeConfig(Path.of("."), Path.of("out"), RunProfile.values()[0], SecPipeConfig.ScopeMode.FULL,
                Optional.empty(), Optional.empty(), mode, Optional.empty(), Map.of(), Map.of(), baseUrl,
                Set.of(), Set.of(), 10, 3, 5, SecPipeConfig.FixMode.FALSE, false, true, Optional.empty(), false,
                false, Set.of(), Optional.empty(), false, List.of(), 80, 6, Set.of(), false, false, false,
                SecPipeConfig.GraphEngine.PLAIN, Duration.ofMinutes(1), SecPipeConfig.Verbosity.NORMAL);
    }

    private ProviderClient openai(Map<String, String> env) {
        return new OpenAiProviderFactory().create(config(ProviderMode.OPENAI, Optional.of(wm.baseUrl() + "/v1")), env);
    }

    private ProviderClient compatible(Map<String, String> env) {
        return new OpenAiCompatibleProviderFactory()
                .create(config(ProviderMode.OPENAI_COMPATIBLE, Optional.of(wm.baseUrl() + "/v1")), env);
    }

    @Test
    void happyPathSendsBearerKeyFromEnvAndReturnsRawText() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(fixture("chat-ok.json"))));

        RawCompletion out = openai(Map.of("OPENAI_API_KEY", KEY)).complete("gpt-5", REQUEST);

        assertThat(out.text()).isEqualTo("{\"verdict\":\"ok\"}");
        assertThat(out.inputTokens()).isEqualTo(12);
        assertThat(out.outputTokens()).isEqualTo(5);
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withRequestBody(containing("\"model\":\"gpt-5\""))
                .withRequestBody(containing("\"json_object\""))
                .withRequestBody(containing("\"max_completion_tokens\":500"))
                .withRequestBody(containing("user prompt")));
    }

    @Test
    void secpipeApiKeyTakesPrecedenceOverProviderVariable() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(fixture("chat-ok.json"))));

        openai(Map.of("SECPIPE_API_KEY", "sk-first-key-000000001", "OPENAI_API_KEY", KEY)).complete("m", REQUEST);

        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-first-key-000000001")));
    }

    @Test
    void rateLimitIsRetryableAndCarriesRetryAfter() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "7").withBody("{\"error\":{\"message\":\"slow down\"}}")));

        assertThatThrownBy(() -> openai(Map.of("OPENAI_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(7)));
    }

    @Test
    void serverErrorIsRetryableStatus() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> openai(Map.of("OPENAI_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void unauthorizedIsNotRetriedHasHintAndNeverEchoesKey() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"message\":\"bad key " + KEY + "\"}}")));

        assertThatThrownBy(() -> openai(Map.of("OPENAI_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class, e -> {
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("hint:").contains("OPENAI_API_KEY");
                    SecretLeakAssertions.assertNoSecret(KEY, e);
                });
    }

    @Test
    void malformedBodyIsAnErrorWithoutRepair() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(fixture("chat-malformed.json"))));

        assertThatThrownBy(() -> openai(Map.of("OPENAI_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class,
                        e -> assertThat(e.getMessage()).contains("malformed provider response"));
    }

    @Test
    void notJsonBodyIsAnError() {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("<html>")));

        assertThatThrownBy(() -> openai(Map.of("OPENAI_API_KEY", KEY)).complete("m", REQUEST))
                .isInstanceOf(ProviderStatusException.class);
    }

    @Test
    void missingKeyAtCallTimeFailsWithHintAndSendsNothing() {
        wm.resetAll();
        assertThatThrownBy(() -> openai(Map.of()).complete("m", REQUEST))
                .isInstanceOfSatisfying(ProviderStatusException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.getMessage()).contains("hint:");
                });
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void keyIsReadFromEnvAtCallTime() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(fixture("chat-ok.json"))));
        Map<String, String> env = new HashMap<>();
        ProviderClient client = openai(env);
        env.put("OPENAI_API_KEY", "sk-late-key-0000000000001");

        client.complete("m", REQUEST);

        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer sk-late-key-0000000000001")));
    }

    @Test
    void compatibleWorksWithoutKeyAndOmitsAuthorizationHeader() throws Exception {
        wm.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(fixture("chat-ok.json"))));

        RawCompletion out = compatible(Map.of()).complete("local-model", REQUEST);

        assertThat(out.text()).isEqualTo("{\"verdict\":\"ok\"}");
        wm.verify(postRequestedFor(urlEqualTo(PATH))
                .withoutHeader("Authorization")
                .withRequestBody(containing("\"max_tokens\":500"))
                .withRequestBody(containing("\"temperature\":0.2")));
    }

    @Test
    void probesReportMissingKeyOrBaseUrlWithHints() {
        SecPipeConfig noUrl = config(ProviderMode.OPENAI_COMPATIBLE, Optional.empty());
        assertThat(new OpenAiCompatibleProviderFactory().probe(noUrl, Map.of()))
                .hasValueSatisfying(m -> assertThat(m).contains("hint:").contains("--llm-base-url"));
        assertThat(new OpenAiProviderFactory().probe(config(ProviderMode.OPENAI, Optional.empty()), Map.of()))
                .hasValueSatisfying(m -> assertThat(m).contains("hint:").contains("OPENAI_API_KEY"));
        assertThat(new OpenAiProviderFactory().probe(config(ProviderMode.OPENAI, Optional.empty()),
                Map.of("SECPIPE_LLM_KEY", KEY))).isEmpty();
    }

    @Test
    void factoriesAreRegisteredWithServiceLoaderAndDefaultsCoverEveryRole() {
        List<LlmProviderFactory> found = ServiceLoader.load(LlmProviderFactory.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        assertThat(found).extracting(LlmProviderFactory::mode)
                .containsExactlyInAnyOrder(ProviderMode.OPENAI, ProviderMode.OPENAI_COMPATIBLE);
        Map<AgentRole, String> defaults = new OpenAiProviderFactory().defaultModels();
        assertThat(defaults).containsOnlyKeys(AgentRole.values());
        assertThat(defaults.get(AgentRole.DISPROVER)).isNotEqualTo(defaults.get(AgentRole.HUNTER));
    }
}
