package ca.carleton.secpipe.llm.openai;

import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.ProviderClient;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RateLimitedException;
import ca.carleton.secpipe.contracts.spi.LlmProviderFactory.RawCompletion;
import ca.carleton.secpipe.llm.ProviderStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Chat Completions client over java.net.http. Reads the key from the env map on every call. */
final class OpenAiChatClient implements ProviderClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final URI endpoint;
    private final Map<String, String> env;
    private final String providerKeyVariable;
    private final boolean keyRequired;
    private final boolean official;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /** {@code official}: api.openai.com semantics (max_completion_tokens, no temperature). */
    OpenAiChatClient(String baseUrl, Map<String, String> env, String providerKeyVariable, boolean keyRequired,
                     boolean official) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/chat/completions");
        this.env = env;
        this.providerKeyVariable = providerKeyVariable;
        this.keyRequired = keyRequired;
        this.official = official;
    }

    @Override
    public RawCompletion complete(String modelId, LlmClient.Request request) throws Exception {
        Optional<String> key = OpenAiCompatibleSupport.key(env, providerKeyVariable);
        if (key.isEmpty() && keyRequired) {
            throw new ProviderStatusException(401,
                    "no API key\nhint: set SECPIPE_API_KEY or " + providerKeyVariable);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(modelId, request)));
        key.ifPresent(k -> builder.header("Authorization", "Bearer " + k));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return parse(response);
    }

    private String body(String modelId, LlmClient.Request r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", modelId);
        m.put("messages", java.util.List.of(
                Map.of("role", "system", "content", r.systemPrompt()),
                Map.of("role", "user", "content", r.userPrompt())));
        m.put("response_format", Map.of("type", "json_object"));
        if (official) {
            m.put("max_completion_tokens", r.maxOutputTokens());
        } else {
            m.put("max_tokens", r.maxOutputTokens());
            m.put("temperature", r.temperature());
        }
        return MAPPER.writeValueAsString(m);
    }

    private RawCompletion parse(HttpResponse<String> response) throws Exception {
        int status = response.statusCode();
        if (status == 429) {
            throw new RateLimitedException("rate limited (HTTP 429)", retryAfter(response));
        }
        if (status == 401 || status == 403) {
            throw new ProviderStatusException(status, "authentication failed (HTTP " + status
                    + ")\nhint: check SECPIPE_API_KEY or " + providerKeyVariable);
        }
        if (status < 200 || status >= 300) {
            throw new ProviderStatusException(status, "provider returned HTTP " + status);
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isString()) {
                throw new IOException("no choices[0].message.content text");
            }
            JsonNode usage = root.path("usage");
            return new RawCompletion(content.asString(), usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0));
        } catch (RuntimeException | IOException e) {
            throw new ProviderStatusException(status, "malformed provider response: " + e.getClass().getSimpleName());
        }
    }

    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }
}
