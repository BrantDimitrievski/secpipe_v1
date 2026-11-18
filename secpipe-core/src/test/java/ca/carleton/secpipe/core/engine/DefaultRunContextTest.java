package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRunContextTest {

    @Test
    void defaultsAreSafeForARunWithoutLlmOrSandbox() {
        DefaultRunContext ctx = DefaultRunContext.builder().workDir(Path.of("w")).outDir(Path.of("o")).build();

        assertThat(ctx.llm().mode()).isEqualTo(ProviderMode.NONE);
        assertThatThrownBy(() -> ctx.llm().complete(AgentRole.HUNTER,
                new LlmClient.Request("s", "u", "hunter", 10, 0.0, "t")))
                .isInstanceOf(LlmClient.LlmUnavailableException.class);
        assertThat(ctx.sandbox()).isEmpty();
        assertThat(ctx.budget().remaining()).isEqualTo(0);
        assertThat(ctx.budget().tryAcquire(AgentRole.CRITIC)).isFalse();
        assertThat(ctx.adapters()).isEmpty();
        assertThatThrownBy(() -> ctx.rescanner().rescan(Path.of("w"), Set.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void workDirAndOutDirAreRequired() {
        assertThatThrownBy(() -> DefaultRunContext.builder().outDir(Path.of("o")).build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("workDir");
    }

    @Test
    void runLogFiltersByVerbosityAndFormatsLevel() {
        List<String> quiet = new ArrayList<>();
        List<String> normal = new ArrayList<>();
        List<String> verbose = new ArrayList<>();
        for (DefaultRunLog log : List.of(new DefaultRunLog(SecPipeConfig.Verbosity.QUIET, quiet::add),
                new DefaultRunLog(SecPipeConfig.Verbosity.NORMAL, normal::add),
                new DefaultRunLog(SecPipeConfig.Verbosity.VERBOSE, verbose::add))) {
            log.debug("d");
            log.info("i");
            log.warn("w");
        }

        assertThat(quiet).containsExactly("[warn] w");
        assertThat(normal).containsExactly("[info] i", "[warn] w");
        assertThat(verbose).containsExactly("[debug] d", "[info] i", "[warn] w");
    }
}
