package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.failure;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.fixture;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.resolve;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.writeYaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.finding.Finding;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** The secpipe.yml layer: schema validation, the API-key ban, unknown keys and value forms. */
class ConfigResolverYamlTest {

    @TempDir
    Path dir;

    @Test
    void yamlWithAnApiKeyIsRejectedWithAHintAndWithoutEchoingTheValue() {
        ConfigException e = failure(map(), map(), Optional.of(fixture("api-key.yml")));

        assertThat(e.getMessage()).contains("api-key").contains("hint:").contains("SECPIPE_API_KEY");
        assertThat(e.problem()).doesNotContain(": x");
        assertThat(e.getMessage().lines().count()).isEqualTo(2);
        assertThat(e.getMessage().lines().toList().get(1)).startsWith("hint: ");
    }

    @Test
    void secretLookingKeysAreRejectedAtAnyDepthAndInAnyCase() throws IOException {
        ConfigException nested = failure(map(), map(), Optional.of(fixture("nested-token.yml")));
        assertThat(nested.getMessage()).contains("model-tier.github_token").contains("hint:");

        for (String key : List.of("API_KEY", "apiKey", "openai-api-key", "client_secret", "github-token")) {
            ConfigException e = failure(map(), map(), Optional.of(writeYaml(dir, key + ": abc123\n")));
            assertThat(e.getMessage()).as(key).contains(key).contains("hint:").doesNotContain("abc123");
        }
    }

    @Test
    void apiKeysAreNeverAcceptedAsOverridesEither() {
        ConfigException e = failure(map("--api-key", "sk-live-123"), map(), Optional.empty());

        assertThat(e.getMessage()).contains("hint:").doesNotContain("sk-live-123");
    }

    @Test
    void unknownYamlKeyIsRejectedWithASuggestion() {
        ConfigException e = failure(map(), map(), Optional.of(fixture("unknown-key.yml")));

        assertThat(e.problem()).contains("unknown key 'max-agent-call'").contains("did you mean 'max-agent-calls'");
        assertThat(e.getMessage()).contains("hint:");
    }

    @Test
    void configIsNotAFileKey() throws IOException {
        ConfigException e = failure(map(), map(), Optional.of(writeYaml(dir, "config: other.yml\n")));

        assertThat(e.problem()).contains("unknown key 'config'");
    }

    @Test
    void theSchemaItselfRejectsUnknownKeysAndAcceptsTheFullFixture() throws IOException {
        Schema schema;
        try (InputStream in = getClass().getResourceAsStream(ConfigResolver.SCHEMA_RESOURCE)) {
            assertThat(in).isNotNull();
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        }
        YAMLMapper yaml = YAMLMapper.builder().build();
        JsonNode unknown = yaml.readTree("profile: quick\nmax-agent-call: 10\n");
        JsonNode full = yaml.readTree(java.nio.file.Files.readString(fixture("full.yml")));

        assertThat(schema.validate(unknown)).isNotEmpty();
        assertThat(schema.validate(full)).isEmpty();
    }

    @Test
    void wrongValueTypeIsRejectedBySchemaValidation() {
        ConfigException e = failure(map(), map(), Optional.of(fixture("wrong-type.yml")));

        assertThat(e.problem()).contains("invalid value").contains("max-agent-calls");
        assertThat(e.getMessage()).contains("hint:");
    }

    @Test
    void badEnumValueIsRejectedBySchemaValidation() throws IOException {
        ConfigException e = failure(map(), map(), Optional.of(writeYaml(dir, "profile: thorough\n")));

        assertThat(e.problem()).contains("invalid value").contains("profile");
    }

    @Test
    void emptyYamlMeansDefaults() {
        assertThat(resolve(map(), map(), fixture("empty.yml"))).isEqualTo(resolve(map(), map()));
    }

    @Test
    void duplicateKeysAreRejected() {
        ConfigException e = failure(map(), map(), Optional.of(fixture("duplicate-key.yml")));

        assertThat(e.problem()).contains("not valid YAML");
    }

    @Test
    void nonMappingDocumentsAndBrokenYamlAreRejected() throws IOException {
        assertThat(failure(map(), map(), Optional.of(fixture("not-a-mapping.yml"))).problem())
                .contains("must be a mapping");
        assertThat(failure(map(), map(), Optional.of(writeYaml(dir, "profile: [quick\n"))).problem())
                .contains("not valid YAML");
    }

    @Test
    void missingConfigFileIsAConfigError() {
        ConfigException e = failure(map(), map(), Optional.of(dir.resolve("nope.yml")));

        assertThat(e.problem()).contains("does not exist");
        assertThat(e.getMessage()).contains("hint:");
    }

    @Test
    void fullYamlMapsEveryKeyOntoTheRecord() {
        SecPipeConfig c = resolve(map(), map(), fixture("full.yml"));

        assertThat(c.target()).isEqualTo(Path.of("services/api"));
        assertThat(c.outDir()).isEqualTo(Path.of("build/secpipe"));
        assertThat(c.profile()).isEqualTo(RunProfile.DEEP);
        assertThat(c.scope()).isEqualTo(SecPipeConfig.ScopeMode.DIFF);
        assertThat(c.baseRef()).contains("origin/main");
        assertThat(c.headRef()).contains("feature/x");
        assertThat(c.llmProvider()).isEqualTo(ProviderMode.ANTHROPIC);
        assertThat(c.defaultModel()).contains("claude-sonnet-5");
        assertThat(c.modelForRole())
                .containsEntry(AgentRole.DISPROVER, "claude-opus-5")
                .containsEntry(AgentRole.RECORD_VERIFIER, "claude-opus-5")
                .hasSize(2);
        assertThat(c.tierOverrides()).containsEntry("local-llama", Finding.VerifierRef.CapabilityTier.SMALL);
        assertThat(c.llmBaseUrl()).contains("https://llm.example.test/v1");
        assertThat(c.tools()).containsExactly("pmd", "semgrep");
        assertThat(c.excludeTools()).containsExactly("grype", "trivy");
        assertThat(c.maxAgentCalls()).isEqualTo(123);
        assertThat(c.maxFixAttempts()).isEqualTo(2);
        assertThat(c.maxSandboxRuns()).isEqualTo(9);
        assertThat(c.enableFixes()).isEqualTo(SecPipeConfig.FixMode.PR);
        assertThat(c.sandboxEnabled()).isFalse();
        assertThat(c.failOnSeverity()).contains(Finding.Score5.MEDIUM);
        assertThat(c.failOnNeedsValidation()).isTrue();
        assertThat(c.failOnIncomplete()).isTrue();
        assertThat(c.formats()).containsExactlyInAnyOrder(SecPipeConfig.OutputFormat.JSON,
                SecPipeConfig.OutputFormat.SARIF);
        assertThat(c.priorRunDir()).contains(Path.of("previous-run"));
        assertThat(c.printPrompts()).isTrue();
        assertThat(c.denyPaths()).containsExactly("**/fixtures/{a,b}/**", "**/*.secret.txt");
        assertThat(c.maxSnippetLines()).isEqualTo(40);
        assertThat(c.maxFilesPerCall()).isEqualTo(3);
        assertThat(c.disabledNodes()).containsExactly("disprove");
        assertThat(c.includeTests()).isTrue();
        assertThat(c.allowBuildExtensions()).isTrue();
        assertThat(c.acceptCodeqlTerms()).isTrue();
        assertThat(c.engine()).isEqualTo(SecPipeConfig.GraphEngine.LANGGRAPH4J);
        assertThat(c.toolTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(c.verbosity()).isEqualTo(SecPipeConfig.Verbosity.VERBOSE);
    }

    @Test
    void resolvedCollectionsAreImmutable() {
        SecPipeConfig c = resolve(map(), map(), fixture("full.yml"));

        assertThrows(UnsupportedOperationException.class, () -> c.tools().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> c.denyPaths().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> c.modelForRole().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.formats().clear());
    }
}
