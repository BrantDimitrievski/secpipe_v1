package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code detect} node (WO-K.6): selects analyzer adapters, runs them in parallel with per-tool timeouts, records
 * one {@code ToolRun} per adapter (selected or not, sorted by id), adds their candidates <b>un-fingerprinted</b>
 * (normalize is the only place fingerprints are assigned) and settles ledger units from coverage claims.
 *
 * <p>Adapters come from {@link RunContext#adapters()} when the assembly supplied any, otherwise from
 * {@link java.util.ServiceLoader} on the thread context class loader. Adapters that require a sandbox are skipped
 * with reason "requires sandbox" when {@link RunContext#sandbox()} is empty; otherwise they receive the sandbox
 * handle in their request and must execute through it.
 */
public final class DetectNode implements PipelineNode {

    static final String REQUIRES_SANDBOX = "requires sandbox";

    private final Function<RunContext, List<AnalyzerAdapter>> discovery;

    public DetectNode() {
        this(ctx -> ctx.adapters().isEmpty()
                ? AdapterSelector.discover(Thread.currentThread().getContextClassLoader())
                : ctx.adapters());
    }

    DetectNode(Function<RunContext, List<AnalyzerAdapter>> discovery) {
        this.discovery = discovery;
    }

    @Override
    public String id() {
        return "detect";
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    @Override
    public boolean usesSandbox() {
        return false;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) throws Exception {
        List<AdapterSelector.Selection> selections = selections(state, ctx);
        List<AdapterSelector.Selection> toRun = selections.stream().filter(AdapterSelector.Selection::selected)
                .toList();
        List<RunMetadata.ToolRun> runs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (AdapterSelector.Selection s : selections) {
            if (!s.selected()) {
                runs.add(new RunMetadata.ToolRun(s.id(), s.version(), RunMetadata.ToolRun.Status.SKIPPED,
                        Optional.of(s.reason()), 0, 0));
                ctx.log().debug("detect: skipped " + s.id() + ": " + s.reason());
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        List<LedgerSettlement.ToolClaim> claims = new ArrayList<>();
        if (!toRun.isEmpty()) {
            Duration timeout = state.config().toolTimeout();
            ProjectProfile profile = state.profile().orElseThrow();
            Set<String> scope = Set.copyOf(new LinkedHashSet<>(state.diffScope()));
            Function<AnalyzerAdapter, AnalyzerAdapter.Request> requestFor = a -> new AnalyzerAdapter.Request(
                    profile.root(), profile, scope, workDir(ctx, a.id()), timeout, state.config().offline(),
                    a.requiresSandbox() ? ctx.sandbox() : Optional.empty());
            for (AdapterRunner.Outcome o : AdapterRunner.runAll(toRun, requestFor, timeout, ctx)) {
                runs.add(o.run());
                if (o.result().candidates() != null) {
                    o.result().candidates().forEach(c -> candidates.add(unfingerprinted(c)));
                }
                if (o.run().status() == RunMetadata.ToolRun.Status.OK && o.result().coverage() != null) {
                    o.result().coverage().forEach(c -> claims.add(new LedgerSettlement.ToolClaim(o.run().id(), c)));
                }
                if (o.run().status() == RunMetadata.ToolRun.Status.FAILED
                        || o.run().status() == RunMetadata.ToolRun.Status.TIMEOUT) {
                    notes.add("detect: " + o.run().id() + " " + o.run().status().name().toLowerCase() + ": "
                            + o.run().reason().orElse("no reason given"));
                }
            }
        }
        runs.sort(Comparator.comparing(RunMetadata.ToolRun::id));
        List<LedgerUnit.Update> updates =
                new LedgerSettlement(KnowledgeBase.load()).settle(state.ledger(), claims, candidates);

        StateDelta delta = new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                updates, Optional.empty(), List.copyOf(candidates), List.of(), Map.of(), List.copyOf(runs), Map.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.copyOf(notes),
                Optional.empty());
        return Result.next(delta);
    }

    private List<AdapterSelector.Selection> selections(PipelineState state, RunContext ctx) {
        List<AnalyzerAdapter> adapters = discovery.apply(ctx);
        Optional<EnvironmentInfo> env = state.environment();
        Optional<String> missing = env.isEmpty() ? Optional.of("environment not detected (setup did not run)")
                : state.profile().isEmpty() ? Optional.of("project profile not available") : Optional.empty();
        if (missing.isPresent()) {
            String why = missing.get();
            return adapters.stream().sorted(Comparator.comparing(AnalyzerAdapter::id))
                    .map(a -> new AdapterSelector.Selection(a, false, why, Optional.empty())).toList();
        }
        List<AdapterSelector.Selection> out = new ArrayList<>();
        for (AdapterSelector.Selection s : new AdapterSelector(adapters).select(state.config(), env.get())) {
            boolean noSandbox = s.selected() && s.adapter().requiresSandbox() && ctx.sandbox().isEmpty();
            out.add(noSandbox ? s.skip(REQUIRES_SANDBOX) : s);
        }
        return List.copyOf(out);
    }

    private static Path workDir(RunContext ctx, String adapterId) {
        Path dir = ctx.workDir().resolve("detect").resolve(adapterId);
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create adapter work dir " + dir, e);
        }
    }

    private static Candidate unfingerprinted(Candidate c) {
        if (c.fingerprint().isEmpty()) {
            return c;
        }
        return new Candidate(Optional.empty(), c.category(), c.origin(), c.toolSources(), c.cwe(), c.cve(), c.title(),
                c.message(), c.claimedRootCause(), c.trace(), c.evidence(), c.sinkStatement(), c.sinkScope(),
                c.dependency(), c.toolSeverityHint(), c.confidenceHint(), c.strongestControl(), c.smallestFix(),
                c.regressionCase(), c.ledgerUnits());
    }
}
