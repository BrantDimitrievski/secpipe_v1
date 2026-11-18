package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The engine's {@link RunContext}: an immutable bundle of services built once per run with {@link #builder()}.
 *
 * <p>Defaults, for wiring that has not supplied a service yet: an LLM client with {@code mode()==NONE} that refuses
 * every call, no sandbox, a zero budget, no adapters, a rescanner that refuses to run, {@link Clock#systemUTC()} and
 * a discarding log. {@code workDir} and {@code outDir} are required.
 */
public final class DefaultRunContext implements RunContext {

    private final Path workDir;
    private final Path outDir;
    private final LlmClient llm;
    private final Optional<SandboxRunner> sandbox;
    private final Budget budget;
    private final List<AnalyzerAdapter> adapters;
    private final Rescanner rescanner;
    private final Clock clock;
    private final RunLog log;

    private DefaultRunContext(Builder b) {
        this.workDir = Objects.requireNonNull(b.workDir, "workDir");
        this.outDir = Objects.requireNonNull(b.outDir, "outDir");
        this.llm = b.llm;
        this.sandbox = b.sandbox;
        this.budget = b.budget;
        this.adapters = List.copyOf(b.adapters);
        this.rescanner = b.rescanner;
        this.clock = b.clock;
        this.log = b.log;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Path workDir() {
        return workDir;
    }

    @Override
    public Path outDir() {
        return outDir;
    }

    @Override
    public LlmClient llm() {
        return llm;
    }

    @Override
    public Optional<SandboxRunner> sandbox() {
        return sandbox;
    }

    @Override
    public Budget budget() {
        return budget;
    }

    @Override
    public List<AnalyzerAdapter> adapters() {
        return adapters;
    }

    @Override
    public Rescanner rescanner() {
        return rescanner;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public RunLog log() {
        return log;
    }

    /** Builder for {@link DefaultRunContext}. Not thread-safe; build once and share the result. */
    public static final class Builder {
        private Path workDir;
        private Path outDir;
        private LlmClient llm = DisabledLlm.INSTANCE;
        private Optional<SandboxRunner> sandbox = Optional.empty();
        private Budget budget = ZeroBudget.INSTANCE;
        private List<AnalyzerAdapter> adapters = List.of();
        private Rescanner rescanner = NoRescanner.INSTANCE;
        private Clock clock = Clock.systemUTC();
        private RunLog log = DefaultRunLog.discarding();

        private Builder() { }

        public Builder workDir(Path p) {
            workDir = Objects.requireNonNull(p, "workDir");
            return this;
        }

        public Builder outDir(Path p) {
            outDir = Objects.requireNonNull(p, "outDir");
            return this;
        }

        public Builder llm(LlmClient c) {
            llm = Objects.requireNonNull(c, "llm");
            return this;
        }

        public Builder sandbox(Optional<SandboxRunner> s) {
            sandbox = Objects.requireNonNull(s, "sandbox");
            return this;
        }

        public Builder budget(Budget b) {
            budget = Objects.requireNonNull(b, "budget");
            return this;
        }

        public Builder adapters(List<AnalyzerAdapter> a) {
            adapters = List.copyOf(a);
            return this;
        }

        public Builder rescanner(Rescanner r) {
            rescanner = Objects.requireNonNull(r, "rescanner");
            return this;
        }

        public Builder clock(Clock c) {
            clock = Objects.requireNonNull(c, "clock");
            return this;
        }

        public Builder log(RunLog l) {
            log = Objects.requireNonNull(l, "log");
            return this;
        }

        public DefaultRunContext build() {
            return new DefaultRunContext(this);
        }
    }

    /** {@code llm-provider=none}: reports mode NONE and refuses every call. */
    private enum DisabledLlm implements LlmClient {
        INSTANCE;

        @Override
        public ProviderMode mode() {
            return ProviderMode.NONE;
        }

        @Override
        public ModelInfo modelFor(AgentRole role) {
            return new ModelInfo(ProviderMode.NONE, "none", Finding.VerifierRef.CapabilityTier.SMALL);
        }

        @Override
        public Response complete(AgentRole role, Request request) throws LlmUnavailableException {
            throw new LlmUnavailableException("llm-provider=none: no LLM calls are made in this run", null);
        }
    }

    /** A budget with no agent calls and no sandbox runs. */
    private enum ZeroBudget implements Budget {
        INSTANCE;

        @Override
        public int max() {
            return 0;
        }

        @Override
        public int used() {
            return 0;
        }

        @Override
        public int remaining() {
            return 0;
        }

        @Override
        public int reserve(AgentRole role, int n) {
            return 0;
        }

        @Override
        public boolean tryAcquire(AgentRole role) {
            return false;
        }

        @Override
        public boolean exhausted() {
            return true;
        }

        @Override
        public boolean tryAcquireSandboxRun() {
            return false;
        }
    }

    /** Used until the CLI assembly wires a real rescanner (WO-K.13). */
    private enum NoRescanner implements Rescanner {
        INSTANCE;

        @Override
        public List<Candidate> rescan(Path workingCopy, Set<String> scopeFiles) {
            throw new UnsupportedOperationException("no rescanner configured for this run");
        }
    }
}
