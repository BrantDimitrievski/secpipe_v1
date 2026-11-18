package ca.carleton.secpipe.llm.anthropic;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Messages API client over java.net.http. Reads the key from the env map on every call. */
final class AnthropicMessagesClient implements ProviderClient {

    static final String API_VERSION = "2023-06-01";
    private static final String JSON_RULE =
            "\n\nRespond with exactly one JSON object and nothing else: no prose, no code fences.";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final URI endpoint;
    private final Map<String, String> env;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    AnthropicMessagesClient(String baseUrl, Map<String, String> env) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/v1/messages");
        this.env = env;
    }

    /** First non-blank of SECPIPE_API_KEY, ANTHROPIC_API_KEY, SECPIPE_LLM_KEY. */
    static Optional<String> key(Map<String, String> env) {
        for (String name : new String[] {"SECPIPE_API_KEY", "ANTHROPIC_API_KEY", "SECPIPE_LLM_KEY"}) {
            String v = env.get(name);
            if (v != null && !v.isBlank()) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    @Override
    public RawCompletion complete(String modelId, LlmClient.Request request) throws Exception {
        String key = key(env).orElseThrow(() -> new ProviderStatusException(401,
                "no API key\nhint: set SECPIPE_API_KEY or ANTHROPIC_API_KEY"));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", key)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body(modelId, request)))
                .build();
        return parse(http.send(httpRequest, HttpResponse.BodyHandlers.ofString()));
    }

    private static String body(String modelId, LlmClient.Request r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", modelId);
        m.put("max_tokens", r.maxOutputTokens());
        m.put("temperature", r.temperature());
        m.put("system", r.systemPrompt() + JSON_RULE);
        m.put("messages", List.of(Map.of("role", "user", "content", r.userPrompt())));
        return MAPPER.writeValueAsString(m);
    }

    private static RawCompletion parse(HttpResponse<String> response) throws Exception {
        int status = response.statusCode();
        if (status == 429) {
            throw new RateLimitedException("rate limited (HTTP 429)", retryAfter(response));
        }
        if (status == 401 || status == 403) {
            throw new ProviderStatusException(status, "authentication failed (HTTP " + status
                    + ")\nhint: check SECPIPE_API_KEY or ANTHROPIC_API_KEY");
        }
        if (status < 200 || status >= 300) {
            throw new ProviderStatusException(status, "provider returned HTTP " + status);
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            StringBuilder text = new StringBuilder();
            boolean found = false;
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asString(""))) {
                    text.append(block.path("text").asString(""));
                    found = true;
                }
            }
            if (!found) {
                throw new IOException("no text content block");
            }
            JsonNode usage = root.path("usage");
            return new RawCompletion(text.toString(), usage.path("input_tokens").asLong(0),
                    usage.path("output_tokens").asLong(0));
        } catch (RuntimeException | IOException e) {
            throw new ProviderStatusException(status, "malformed provider response: " + e.getClass().getSimpleName());
        }
    }

    private static Optional<Duration> retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("retry-after").flatMap(v -> {
            try {
                return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }
}
