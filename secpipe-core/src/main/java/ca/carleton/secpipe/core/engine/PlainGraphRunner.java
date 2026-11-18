package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.pipeline.GraphRunner;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The reference {@link GraphRunner} (ADR-02): walks {@link PipelineGraph} from {@code setup} to {@code __end__},
 * one node at a time, and is the only writer of {@link PipelineState} (through {@link DeltaApplier}).
 *
 * <p>Per step, for graph id {@code id}:
 * <ol>
 *   <li>disabled by {@code --disable-node} → note "node &lt;id&gt; disabled by --disable-node", SKIPPED edge;</li>
 *   <li>class missing or unloadable ({@link NodeLoader}) → note "node &lt;id&gt; not installed ...", SKIPPED;</li>
 *   <li>{@code requiresLlm()} and {@code llm().mode()==NONE} → note, SKIPPED;</li>
 *   <li>otherwise the node runs; its delta is applied (validators included) and its elapsed time, measured with
 *       {@link RunContext#clock()}, is added to {@code stageTimingsMs[id]} (NFR-2).</li>
 * </ol>
 * A node that throws, returns no result, returns an undeclared label or a rejected delta makes the run incomplete
 * (first reason wins, later ones become notes) and follows its SKIPPED edge, so finalize and report still run.
 * A node in {@link PipelineGraph#REQUIRED_FOR_COMPLETE} that does not run also makes the run incomplete.
 *
 * <p>Loop guard: after {@value #MAX_NODE_STEPS} steps (skipped steps count) the run becomes incomplete and the
 * runner jumps to {@code finalize}, then {@code report}, so the run still writes its artifacts.
 *
 * <p>After every step the state is checkpointed to {@code workDir()/state.json} ({@link EngineCheckpoint}) unless
 * disabled; a checkpoint failure is logged once and never stops the run.
 */
public final class PlainGraphRunner implements GraphRunner {

    /** Maximum node steps per run before the loop guard trips. */
    public static final int MAX_NODE_STEPS = 200;

    /** The nodes still run after the loop guard trips, in order. */
    static final List<String> TAIL = List.of("finalize", "report");

    private final DeltaApplier applier;
    private final NodeLoader loader;
    private final boolean checkpoints;

    /** Loads nodes from {@link PipelineGraph#NODES} and checkpoints after every step. */
    public PlainGraphRunner(EngineValidators validators) {
        this(validators, new NodeLoader(), true);
    }

    public PlainGraphRunner(EngineValidators validators, NodeLoader loader, boolean checkpoints) {
        this.applier = new DeltaApplier(Objects.requireNonNull(validators, "validators"));
        this.loader = Objects.requireNonNull(loader, "loader");
        this.checkpoints = checkpoints;
    }

    @Override
    public PipelineState run(PipelineState initial, RunContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        Run run = new Run(DeltaApplier.freeze(initial), ctx);
        String current = PipelineGraph.START;
        int steps = 0;
        boolean guardTripped = false;
        while (!PipelineGraph.END.equals(current)) {
            if (steps >= MAX_NODE_STEPS && !guardTripped) {
                guardTripped = true;
                run.engine(List.of(), Optional.of("loop guard: more than " + MAX_NODE_STEPS
                        + " node steps; stopped before node " + current), Map.of());
                if (!TAIL.contains(current)) {
                    current = TAIL.get(0);
                }
            }
            if (steps >= MAX_NODE_STEPS + TAIL.size()) {
                run.engine(List.of("engine stopped at node " + current + " after the loop guard tail"),
                        Optional.empty(), Map.of());
                break;
            }
            String label = run.step(current);
            steps++;
            run.checkpoint();
            current = run.next(current, label);
        }
        ctx.log().debug("engine finished after " + steps + " node steps");
        return run.state;
    }

    /** Per-run mutable bookkeeping; the state itself is only replaced through the applier. */
    private final class Run {
        private PipelineState state;
        private final RunContext ctx;
        private final Map<String, Optional<PipelineNode>> nodes = new HashMap<>();
        private final Map<String, String> missingReasons = new HashMap<>();
        private boolean checkpointWarned;

        Run(PipelineState state, RunContext ctx) {
            this.state = state;
            this.ctx = ctx;
        }

        /** Runs or skips {@code id}; returns the transition label to follow. */
        String step(String id) {
            if (state.config().disabledNodes().contains(id)) {
                return skip(id, "node " + id + " disabled by --disable-node");
            }
            Optional<PipelineNode> node = nodes.computeIfAbsent(id, this::load);
            if (node.isEmpty()) {
                return skip(id, missingReasons.get(id));
            }
            if (node.get().requiresLlm() && ctx.llm().mode() == ProviderMode.NONE) {
                return skip(id, "node " + id + " skipped: llm-provider=none");
            }
            return execute(id, node.get());
        }

        private Optional<PipelineNode> load(String id) {
            NodeLoader.Lookup lookup = loader.lookup(id);
            if (lookup instanceof NodeLoader.Lookup.Found f) {
                return Optional.of(f.node());
            }
            missingReasons.put(id, ((NodeLoader.Lookup.Missing) lookup).reason());
            return Optional.empty();
        }

        private String skip(String id, String why) {
            ctx.log().info(why);
            Optional<String> incomplete = PipelineGraph.REQUIRED_FOR_COMPLETE.contains(id)
                    ? Optional.of("required node " + id + " did not run: " + why)
                    : Optional.empty();
            engine(List.of(why), incomplete, Map.of());
            return PipelineGraph.SKIPPED;
        }

        private String execute(String id, PipelineNode node) {
            ctx.log().debug("node " + id + " started");
            long start = ctx.clock().millis();
            PipelineNode.Result result;
            try {
                result = node.apply(state, ctx);
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                long elapsed = ctx.clock().millis() - start;
                String reason = "node " + id + " failed: " + describe(e);
                ctx.log().warn(reason);
                ctx.log().debug(stackTrace(e));
                engine(List.of(), Optional.of(reason), Map.of(id, elapsed));
                return PipelineGraph.SKIPPED;
            }
            long elapsed = ctx.clock().millis() - start;
            String label = PipelineGraph.SKIPPED;
            List<String> problems = new ArrayList<>();
            if (result == null || result.delta() == null || result.label() == null) {
                problems.add("node " + id + " returned no " + (result == null ? "result"
                        : result.delta() == null ? "delta" : "transition label"));
            } else if (!declared(id, result.label())) {
                problems.add("node " + id + " returned undeclared transition '" + result.label() + "'");
            } else {
                label = result.label();
            }
            if (result != null && result.delta() != null) {
                applyNodeDelta(id, result.delta()).ifPresent(problems::add);
            }
            for (String p : problems) {
                ctx.log().warn(p);
                engine(List.of(), Optional.of(p), Map.of());
            }
            engine(List.of(), Optional.empty(), Map.of(id, elapsed));
            ctx.log().debug("node " + id + " finished in " + elapsed + " ms -> " + label);
            return label;
        }

        /** Applies a node's delta; returns the incomplete reason when it was rejected or could not be applied. */
        private Optional<String> applyNodeDelta(String id, StateDelta delta) {
            try {
                state = applier.apply(state, delta);
                return Optional.empty();
            } catch (DeltaRejectedException e) {
                return Optional.of(e.incompleteReason(id));
            } catch (RuntimeException e) {
                ctx.log().debug(stackTrace(e));
                return Optional.of("engine could not apply delta from node " + id + ": " + describe(e));
            }
        }

        /** Applies an engine-authored delta (notes, incomplete reason, timings). It cannot fail validation. */
        void engine(List<String> notes, Optional<String> incomplete, Map<String, Long> timings) {
            StateDelta d = StateDelta.empty();
            StateDelta delta = new StateDelta(d.environment(), d.profile(), d.diffScope(), d.newLedgerUnits(),
                    d.ledgerUpdates(), d.replaceCandidates(), d.addCandidates(), d.putRecords(),
                    d.putSurvivorDrafts(), d.toolRuns(), timings, d.hunterWave(), d.criticPasses(),
                    d.criticSaysStop(), d.fixAttemptsByFingerprint(), d.prior(), notes, incomplete);
            try {
                state = applier.apply(state, delta);
            } catch (DeltaRejectedException e) {
                throw new IllegalStateException("engine delta rejected: " + e.getMessage(), e);
            }
        }

        String next(String id, String label) {
            Map<String, String> edges = PipelineGraph.TRANSITIONS.getOrDefault(id, Map.of());
            String target = edges.get(label);
            if (target == null && PipelineGraph.SKIPPED.equals(label)) {
                target = edges.get(PipelineGraph.NEXT);
            }
            if (target == null) {
                engine(List.of(), Optional.of("no transition '" + label + "' from node " + id), Map.of());
                return PipelineGraph.END;
            }
            return target;
        }

        void checkpoint() {
            if (!checkpoints) {
                return;
            }
            try {
                EngineCheckpoint.write(state, ctx.workDir());
            } catch (IOException | RuntimeException e) {
                if (!checkpointWarned) {
                    checkpointWarned = true;
                    ctx.log().warn("could not write " + EngineCheckpoint.FILE_NAME + " to " + ctx.workDir()
                            + ": " + describe(e));
                }
            }
        }
    }

    private static boolean declared(String id, String label) {
        return PipelineGraph.SKIPPED.equals(label)
                || PipelineGraph.TRANSITIONS.getOrDefault(id, Map.of()).containsKey(label);
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    private static String stackTrace(Throwable e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
