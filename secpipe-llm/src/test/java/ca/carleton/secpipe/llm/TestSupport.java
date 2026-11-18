package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.RunContext.RunLog;
import ca.carleton.secpipe.contracts.spi.LlmClient;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared helpers for this module's tests. */
final class TestSupport {

    private TestSupport() { }

    static LlmClient.Request request(String templateId, String userPrompt) {
        return new LlmClient.Request("you are a security agent", userPrompt, "agent/hunter", 1000, 0.0, templateId);
    }

    static Path fixtures() {
        try {
            return Path.of(TestSupport.class.getResource("/llm-fixtures").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static SecPipeConfig config(ProviderMode mode, Optional<String> defaultModel, Map<AgentRole, String> perRole) {
        return new SecPipeConfig(Path.of("."), Path.of("out"), RunProfile.values()[0], SecPipeConfig.ScopeMode.FULL,
                Optional.empty(), Optional.empty(), mode, defaultModel, perRole, Map.of(), Optional.empty(),
                Set.of(), Set.of(), 10, 3, 5, SecPipeConfig.FixMode.FALSE, false, true, Optional.empty(), false,
                false, Set.of(), Optional.empty(), false, List.of(), 80, 6, Set.of(), false, false, false,
                SecPipeConfig.GraphEngine.PLAIN, Duration.ofMinutes(1), SecPipeConfig.Verbosity.NORMAL);
    }

    /** RunLog that keeps every line. */
    static final class CapturingLog implements RunLog {
        final List<String> lines = new ArrayList<>();
        @Override public synchronized void info(String msg) { lines.add("INFO " + msg); }
        @Override public synchronized void warn(String msg) { lines.add("WARN " + msg); }
        @Override public synchronized void debug(String msg) { lines.add("DEBUG " + msg); }
    }

    /** Sleeper that records requested delays instead of sleeping. */
    static final class RecordingSleeper implements Sleeper {
        final List<Duration> delays = new ArrayList<>();
        @Override public void sleep(Duration d) { delays.add(d); }
    }
}
