package ca.carleton.secpipe.contracts.config;

import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.failure;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.fixture;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.map;
import static ca.carleton.secpipe.contracts.config.ConfigTestSupport.resolve;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The FAKE provider is accepted only when SECPIPE_ALLOW_FAKE_LLM=1. */
class ConfigResolverFakeGateTest {

    @Test
    void fakeProviderIsRejectedWithoutTheGate() {
        ConfigException e = failure(map("llm-provider", "fake"), map(), Optional.empty());

        assertThat(e.problem()).contains("fake");
        assertThat(e.getMessage()).contains("hint:").contains("SECPIPE_ALLOW_FAKE_LLM=1");
    }

    @Test
    void fakeProviderIsAcceptedWhenTheGateIsExactlyOne() {
        SecPipeConfig c = resolve(map("llm-provider", "fake"), map("SECPIPE_ALLOW_FAKE_LLM", "1"));

        assertThat(c.llmProvider()).isEqualTo(ProviderMode.FAKE);
    }

    @Test
    void otherGateValuesDoNotUnlockFake() {
        for (String value : new String[] {"true", "yes", "0", ""}) {
            ConfigException e = failure(map(), map("SECPIPE_LLM_PROVIDER", "fake", "SECPIPE_ALLOW_FAKE_LLM", value),
                    Optional.empty());
            assertThat(e.problem()).as("gate=%s", value).contains("fake");
        }
    }

    @Test
    void fakeFromYamlIsGatedToo() {
        failure(map(), map(), Optional.of(fixture("fake-provider.yml")));

        assertThat(resolve(map(), map("SECPIPE_ALLOW_FAKE_LLM", "1"), fixture("fake-provider.yml")).llmProvider())
                .isEqualTo(ProviderMode.FAKE);
    }
}
