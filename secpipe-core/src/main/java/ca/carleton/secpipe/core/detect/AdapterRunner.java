package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Runs selected adapters in parallel on a pool of {@code min(adapters, cores)} threads. Each adapter call runs on its
 * own virtual thread and gets its own timeout, measured from when it starts. A timeout cancels (interrupts) the call
 * and records {@code TIMEOUT}; an exception records {@code FAILED}; the other adapters are unaffected.
 */
final class AdapterRunner {

    /** What one adapter produced. Coverage claims are kept only for runs with status {@code OK}. */
    record Outcome(AnalyzerAdapter.Result result, RunMetadata.ToolRun run) { }

    private AdapterRunner() { }

    static List<Outcome> runAll(List<AdapterSelector.Selection> selected,
                                Function<AnalyzerAdapter, AnalyzerAdapter.Request> requestFor, Duration timeout,
                                RunContext ctx) throws InterruptedException {
        if (selected.isEmpty()) {
            return List.of();
        }
        int threads = Math.max(1, Math.min(selected.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "secpipe-detect");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (AdapterSelector.Selection s : selected) {
                futures.add(pool.submit(() -> runOne(s, requestFor, timeout, ctx)));
            }
            List<Outcome> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    // runOne never throws; keep the run going if it somehow does.
                    out.add(failed(selected.get(i), "detect error: " + AdapterSelector.message(e.getCause()), 0));
                }
            }
            return List.copyOf(out);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Outcome runOne(AdapterSelector.Selection s,
                                  Function<AnalyzerAdapter, AnalyzerAdapter.Request> requestFor, Duration timeout,
                                  RunContext ctx) {
        AnalyzerAdapter adapter = s.adapter();
        Clock clock = ctx.clock();
        long start = clock.millis();
        ctx.log().info("detect: running " + adapter.id());
        FutureTask<AnalyzerAdapter.Result> task = new FutureTask<>(() -> adapter.analyze(requestFor.apply(adapter)));
        Thread.ofVirtual().name("secpipe-detect-" + adapter.id()).start(task);
        try {
            AnalyzerAdapter.Result result = task.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            long ms = clock.millis() - start;
            if (result == null) {
                return failed(s, "adapter returned no result", ms);
            }
            List<Candidate> candidates = result.candidates() == null ? List.of() : result.candidates();
            RunMetadata.ToolRun reported = result.run();
            RunMetadata.ToolRun run = new RunMetadata.ToolRun(adapter.id(),
                    reported == null ? s.version() : reported.version().or(s::version),
                    reported == null ? RunMetadata.ToolRun.Status.OK : reported.status(),
                    reported == null ? Optional.empty() : reported.reason(), ms, candidates.size());
            ctx.log().info("detect: " + adapter.id() + " " + run.status().name().toLowerCase() + ", "
                    + candidates.size() + " candidate(s) in " + ms + " ms");
            return new Outcome(result, run);
        } catch (TimeoutException e) {
            task.cancel(true);
            String reason = "timed out after "
                    + (timeout.toMillis() % 1000 == 0 ? timeout.toSeconds() + " s" : timeout.toMillis() + " ms");
            ctx.log().warn("detect: " + adapter.id() + " " + reason);
            return new Outcome(empty(), new RunMetadata.ToolRun(adapter.id(), s.version(),
                    RunMetadata.ToolRun.Status.TIMEOUT, Optional.of(reason), clock.millis() - start, 0));
        } catch (ExecutionException e) {
            String reason = AdapterSelector.message(e.getCause() == null ? e : e.getCause());
            ctx.log().warn("detect: " + adapter.id() + " failed: " + reason);
            return failed(s, reason, clock.millis() - start);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failed(s, "interrupted", clock.millis() - start);
        }
    }

    private static Outcome failed(AdapterSelector.Selection s, String reason, long ms) {
        return new Outcome(empty(), new RunMetadata.ToolRun(s.id(), s.version(), RunMetadata.ToolRun.Status.FAILED,
                Optional.of(reason), ms, 0));
    }

    private static AnalyzerAdapter.Result empty() {
        return new AnalyzerAdapter.Result(List.of(), List.of(), null);
    }
}
