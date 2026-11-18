package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.failure;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WO-CC.1: the {@code copilot} provider mode was removed after the spike S.2 NO-GO. Every config layer rejects it
 * with a one-line problem plus a hint naming spike S.2, and neither schema accepts it.
 */
class ConfigResolverCopilotRemovedTest {

    private static final String HINT = "copilot was removed after spike S.2 (no-tools guarantee does not hold); "
            + "use anthropic, openai, openai-compatible, ollama or none";

    private static final String CONFIG_ID = "https://secpipe.dev/schemas/config.schema.json";
    private static final String RUN_METADATA_ID = "https://secpipe.dev/schemas/run-metadata.schema.json";

    @Test
    void copilotIsNoLongerAProviderMode() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProviderMode.fromCli("copilot"));
        assertTrue(e.getMessage().contains("copilot"));
        List<String> names = new ArrayList<>();
        for (ProviderMode m : ProviderMode.values()) {
            names.add(m.cliName());
        }
        assertEquals(List.of("none", "anthropic", "openai", "openai-compatible", "ollama", "fake"), names);
    }

    @Test
    void copilotOnTheCommandLineIsRejectedWithTheSpikeHint() {
        ConfigException e = failure(map("llm-provider", "copilot"), map(), Optional.empty());

        assertEquals("--llm-provider: expected an LLM provider, got 'copilot'", e.problem());
        assertEquals(HINT, e.hint());
        assertEquals(e.problem() + "\nhint: " + HINT, e.getMessage());
    }

    @Test
    void copilotFromTheEnvironmentIsRejectedWithTheSpikeHintRegardlessOfCase() {
        ConfigException e = failure(map(), map("SECPIPE_LLM_PROVIDER", " Copilot "), Optional.empty());

        assertTrue(e.problem().contains("SECPIPE_LLM_PROVIDER"), e.problem());
        assertEquals(HINT, e.hint());
    }

    @Test
    void copilotInAnOldSecpipeYmlIsRejectedWithTheSpikeHint() throws IOException {
        Path dir = Files.createTempDirectory("secpipe-cc1-");
        Path yaml = ConfigTestSupport.writeYaml(dir, "profile: quick\nllm-provider: copilot\n");
        try {
            ConfigException e = failure(map(), map(), Optional.of(yaml));

            assertEquals("config file " + yaml + " key 'llm-provider': expected an LLM provider, got 'copilot'",
                    e.problem());
            assertEquals(HINT, e.hint());
            assertFalse(e.getMessage().contains("\tat "), "no stack trace in the message");
        } finally {
            Files.deleteIfExists(yaml);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void otherUnknownProvidersKeepTheGeneralHintWithoutCopilot() {
        ConfigException e = failure(map("llm-provider", "github-models"), map(), Optional.empty());

        assertEquals("use one of none, anthropic, openai, openai-compatible, ollama", e.hint());
        assertFalse(e.getMessage().toLowerCase(Locale.ROOT).contains("copilot"));
    }

    @Test
    void secretOptionHintNamesTheKeyAliasesWithoutACopilotToken() {
        ConfigException e = failure(map("api-key", "x"), map(), Optional.empty());

        assertTrue(e.hint().contains("SECPIPE_API_KEY"), e.hint());
        assertTrue(e.hint().contains("SECPIPE_LLM_KEY"), e.hint());
        assertFalse(e.hint().toUpperCase(Locale.ROOT).contains("COPILOT"), e.hint());
    }

    @Test
    void configSchemaRejectsCopilotAsLlmProvider() {
        Schema schema = schema(CONFIG_ID, "/schemas/config.schema.json", "");

        assertFalse(schema.validate("{\"llm-provider\": \"copilot\"}", InputFormat.JSON).isEmpty());
        List<Error> ok = schema.validate("{\"llm-provider\": \"anthropic\"}", InputFormat.JSON);
        assertTrue(ok.isEmpty(), ok::toString);
    }

    @Test
    void runMetadataSchemaRejectsCopilotAsProviderMode() {
        Schema providerMode = schema(RUN_METADATA_ID, "/schemas/run-metadata.schema.json", "#/$defs/providerMode");

        assertFalse(providerMode.validate("\"copilot\"", InputFormat.JSON).isEmpty());
        for (ProviderMode m : ProviderMode.values()) {
            List<Error> ok = providerMode.validate("\"" + m.cliName() + "\"", InputFormat.JSON);
            assertTrue(ok.isEmpty(), () -> m + ": " + ok);
        }
    }

    private static Schema schema(String id, String resource, String fragment) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                        builder -> builder.schemas(Map.of(id, read(resource))))
                .getSchema(SchemaLocation.of(id + fragment));
    }

    private static String read(String resource) {
        try (InputStream in = ConfigResolverCopilotRemovedTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
