package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.spi.LlmClient;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stub pipeline nodes registered by FQCN for the runner tests. The runner never sees these types at compile time:
 * {@link NodeLoader} instantiates them reflectively from the id → FQCN table built by {@link #table}.
 */
final class StubNodes {

    private StubNodes() { }

    /** Every graph id mapped to {@link Next} (or {@link LlmNext} for LLM nodes), then {@code overrides} applied. */
    static NodeLoader loader(Map<String, Class<?>> overrides) {
        return new NodeLoader(table(overrides), StubNodes.class.getClassLoader());
    }

    static Map<String, String> table(Map<String, Class<?>> overrides) {
        Map<String, String> table = new LinkedHashMap<>();
        for (String id : PipelineGraph.NODES.keySet()) {
            boolean llm = switch (id) {
                case "recon_summary", "disprove", "remediate", "verify_records" -> true;
                default -> false;
            };
            Class<?> type = switch (id) {
                case "hunt" -> Hunt.class;
                case "critic" -> CriticTwice.class;
                default -> llm ? LlmNext.class : Next.class;
            };
            table.put(id, type.getName());
        }
        overrides.forEach((id, type) -> table.put(id, type.getName()));
        return table;
    }

    static RunContext context(ProviderMode mode, Path workDir) {
        return DefaultRunContext.builder()
                .workDir(workDir)
                .outDir(workDir.resolve("out"))
                .llm(new ModeOnlyLlm(mode))
                .clock(new TickingClock(7))
                .log(DefaultRunLog.collecting())
                .build();
    }

    /** Base for stubs that do not need an LLM or sandbox. */
    abstract static class Base implements PipelineNode {
        @Override
        public String id() {
            return getClass().getSimpleName();
        }

        @Override
        public boolean requiresLlm() {
            return false;
        }

        @Override
        public boolean usesSandbox() {
            return false;
        }
    }

    /** Returns NEXT with an empty delta. */
    public static final class Next extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return Result.next(EngineFixtures.delta().build());
        }
    }

    /** An LLM node that returns NEXT. */
    public static final class LlmNext extends Base {
        @Override
        public boolean requiresLlm() {
            return true;
        }

        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return Result.next(EngineFixtures.delta().build());
        }
    }

    /** LLM hunter: increments hunterWave, then NEXT (to critic). */
    public static final class Hunt extends Base {
        @Override
        public boolean requiresLlm() {
            return true;
        }

        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return Result.next(EngineFixtures.delta().hunterWave(state.hunterWave() + 1).build());
        }
    }

    /** LLM critic: increments criticPasses; LOOP until two passes are done, then DONE. */
    public static final class CriticTwice extends Base {
        @Override
        public boolean requiresLlm() {
            return true;
        }

        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            int passes = state.criticPasses() + 1;
            String label = passes < 2 ? PipelineGraph.LOOP : PipelineGraph.DONE;
            return new Result(label, EngineFixtures.delta().criticPasses(passes).build());
        }
    }

    /** Critic that never stops looping. */
    public static final class CriticForever extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return new Result(PipelineGraph.LOOP, EngineFixtures.delta().criticPasses(state.criticPasses() + 1)
                    .build());
        }
    }

    /** Throws from apply. */
    public static final class Throwing extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            throw new IllegalStateException("boom");
        }
    }

    /** Puts one record; the test validator rejects it. */
    public static final class PutsRecord extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            Finding f = EngineFixtures.rejected("fp-1", "false positive");
            return Result.next(EngineFixtures.delta().putRecords(f).notes("record written").build());
        }
    }

    /** Returns a label that is not declared for any node. */
    public static final class Undeclared extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return new Result("bogus", EngineFixtures.delta().notes("undeclared ran").build());
        }
    }

    /** Returns null. */
    public static final class ReturnsNull extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return null;
        }
    }

    /** Tries to write state directly instead of returning a delta. */
    public static final class Mutating extends Base {
        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            state.notes().add("smuggled");
            return Result.next(EngineFixtures.delta().build());
        }
    }

    /** Has no public no-arg constructor. */
    public static final class NoDefaultCtor extends Base {
        public NoDefaultCtor(String unused) { }

        @Override
        public Result apply(PipelineState state, RunContext ctx) {
            return Result.next(EngineFixtures.delta().build());
        }
    }

    /** Not a PipelineNode at all. */
    public static final class NotANode { }

    /** Reports a fixed provider mode; the runner only reads mode(). Never called. */
    static final class ModeOnlyLlm implements LlmClient {
        private final ProviderMode mode;

        ModeOnlyLlm(ProviderMode mode) {
            this.mode = mode;
        }

        @Override
        public ProviderMode mode() {
            return mode;
        }

        @Override
        public ModelInfo modelFor(AgentRole role) {
            return new ModelInfo(mode, "stub", Finding.VerifierRef.CapabilityTier.STANDARD);
        }

        @Override
        public Response complete(AgentRole role, Request request) throws LlmUnavailableException {
            throw new LlmUnavailableException("stub LLM is never called by the engine", null);
        }
    }

    /** A clock that advances {@code stepMs} every time it is read. */
    static final class TickingClock extends Clock {
        private final AtomicLong now = new AtomicLong(1_000_000L);
        private final long stepMs;

        TickingClock(long stepMs) {
            this.stepMs = stepMs;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.getAndAdd(stepMs));
        }

        @Override
        public long millis() {
            return now.getAndAdd(stepMs);
        }
    }
}
