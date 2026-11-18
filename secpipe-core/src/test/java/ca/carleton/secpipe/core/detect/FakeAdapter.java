package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Base of the two test adapters registered in this package's test-scope {@code META-INF/services} file. Behaviour is
 * set per adapter id through static state, because ServiceLoader instantiates them with their no-arg constructor.
 */
public abstract class FakeAdapter implements AnalyzerAdapter {

    enum Behavior { OK, FAIL, HANG, BARRIER }

    static final Map<String, Behavior> BEHAVIOR = new ConcurrentHashMap<>();
    static final Map<String, Boolean> SANDBOX = new ConcurrentHashMap<>();
    static volatile CountDownLatch barrier = new CountDownLatch(0);
    static final Map<String, Request> LAST_REQUEST = new ConcurrentHashMap<>();

    static void reset() {
        BEHAVIOR.clear();
        SANDBOX.clear();
        LAST_REQUEST.clear();
        barrier = new CountDownLatch(0);
    }

    /** CWE, file of the single candidate, and files the coverage claim covers. */
    abstract String cwe();

    abstract String candidateFile();

    abstract List<String> claimedFiles();

    @Override
    public String displayName() {
        return id();
    }

    @Override
    public Kind kind() {
        return Kind.SAST;
    }

    @Override
    public Set<String> supportedCwes() {
        return Set.of(cwe());
    }

    @Override
    public Availability availability(EnvironmentInfo env) {
        return Availability.yes("9.9");
    }

    @Override
    public boolean defaultEnabled(RunProfile profile) {
        return true;
    }

    @Override
    public String licenseNote() {
        return "test only";
    }

    @Override
    public boolean requiresSandbox() {
        return SANDBOX.getOrDefault(id(), false);
    }

    @Override
    public Result analyze(Request request) throws AnalyzerException {
        LAST_REQUEST.put(id(), request);
        switch (BEHAVIOR.getOrDefault(id(), Behavior.OK)) {
            case FAIL -> throw new AnalyzerException(id() + " exploded");
            case HANG -> sleep(60_000);
            case BARRIER -> {
                barrier.countDown();
                try {
                    if (!barrier.await(5, TimeUnit.SECONDS)) {
                        throw new AnalyzerException("barrier not reached: adapters did not run in parallel");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AnalyzerException("interrupted", e);
                }
            }
            case OK -> { }
        }
        Candidate c = new Candidate(Optional.of("deadbeef"), Finding.Category.CODE, Finding.Origin.TOOL,
                List.of(new Finding.ToolSource(id(), id() + "-rule", Optional.empty(), Optional.of(candidateFile()),
                        Optional.of(10))),
                List.of(cwe()), List.of(), id() + " finding", "message", "root cause",
                List.of(new Finding.TraceStep(Finding.TraceKind.SINK, candidateFile(), 10, "A#run()", "sink")),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of());
        return new Result(List.of(c), List.of(new CoverageClaim(cwe(), claimedFiles(), List.of(id() + "-rule"))),
                new RunMetadata.ToolRun(id(), Optional.empty(), RunMetadata.ToolRun.Status.OK, Optional.empty(), 0,
                        1));
    }

    private static void sleep(long ms) throws AnalyzerException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalyzerException("interrupted", e);
        }
    }
}
