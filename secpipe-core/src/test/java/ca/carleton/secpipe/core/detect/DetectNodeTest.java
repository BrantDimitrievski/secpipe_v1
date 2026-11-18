package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DetectNodeTest {

    @TempDir
    Path work;

    @BeforeEach
    void setUp() {
        FakeAdapter.reset();
    }

    @AfterEach
    void tearDown() {
        FakeAdapter.reset();
    }

    private static DetectNode node() {
        return new DetectNode(ctx -> DetectFixtures.fakes());
    }

    private static RunMetadata.ToolRun run(StateDelta d, String id) {
        return d.toolRuns().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void isRegisteredAtItsGraphFqcnAndLoadsAdaptersThroughServiceLoaderByDefault() throws Exception {
        assertThat(PipelineGraph.NODES.get("detect")).isEqualTo(DetectNode.class.getName());
        PipelineNode reflective = (PipelineNode) Class.forName(PipelineGraph.NODES.get("detect"))
                .getConstructor().newInstance();
        Thread t = Thread.currentThread();
        ClassLoader before = t.getContextClassLoader();
        t.setContextClassLoader(DetectFixtures.servicesLoader());
        PipelineNode.Result r;
        try {
            r = reflective.apply(DetectFixtures.state(DetectFixtures.config(), List.of()),
                    DetectFixtures.ctx(work, Optional.empty()));
        } finally {
            t.setContextClassLoader(before);
        }
        assertThat(r.label()).isEqualTo(PipelineGraph.NEXT);
        assertThat(r.delta().toolRuns()).extracting(RunMetadata.ToolRun::id).containsExactly("fake-alpha", "fake-beta");
        assertThat(r.delta().toolRuns()).allMatch(x -> x.status() == RunMetadata.ToolRun.Status.OK);
        assertThat(reflective.id()).isEqualTo("detect");
        assertThat(reflective.requiresLlm()).isFalse();
    }

    @Test
    void runsAdaptersInParallel() throws Exception {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2, "needs two cores for a pool of two");
        FakeAdapter.barrier = new CountDownLatch(2);
        FakeAdapter.BEHAVIOR.put("fake-alpha", FakeAdapter.Behavior.BARRIER);
        FakeAdapter.BEHAVIOR.put("fake-beta", FakeAdapter.Behavior.BARRIER);

        StateDelta d = node().apply(DetectFixtures.state(DetectFixtures.config(), List.of()),
                DetectFixtures.ctx(work, Optional.empty())).delta();

        // Each adapter waits until both have started: a sequential runner would fail both.
        assertThat(d.toolRuns()).allMatch(x -> x.status() == RunMetadata.ToolRun.Status.OK);
        assertThat(d.addCandidates()).hasSize(2);
    }

    @Test
    void timedOutAdapterIsRecordedAsTimeoutAndOtherCandidatesSurvive() throws Exception {
        FakeAdapter.BEHAVIOR.put("fake-alpha", FakeAdapter.Behavior.HANG);
        var config = DetectFixtures.config(Set.of(), Set.of(), Duration.ofMillis(300));

        long start = System.nanoTime();
        StateDelta d = node().apply(DetectFixtures.state(config, List.of()),
                DetectFixtures.ctx(work, Optional.empty())).delta();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        RunMetadata.ToolRun alpha = run(d, "fake-alpha");
        assertThat(alpha.status()).isEqualTo(RunMetadata.ToolRun.Status.TIMEOUT);
        assertThat(alpha.candidates()).isZero();
        assertThat(alpha.reason()).hasValueSatisfying(r -> assertThat(r).contains("timed out"));
        assertThat(run(d, "fake-beta").status()).isEqualTo(RunMetadata.ToolRun.Status.OK);
        assertThat(d.addCandidates()).extracting(Candidate::title).containsExactly("fake-beta finding");
        assertThat(d.notes()).anyMatch(n -> n.contains("fake-alpha timeout"));
        assertThat(elapsedMs).isLessThan(30_000);
    }

    @Test
    void failingAdapterIsRecordedAsFailedAndTheRunContinues() throws Exception {
        FakeAdapter.BEHAVIOR.put("fake-beta", FakeAdapter.Behavior.FAIL);

        StateDelta d = node().apply(DetectFixtures.state(DetectFixtures.config(), List.of()),
                DetectFixtures.ctx(work, Optional.empty())).delta();

        RunMetadata.ToolRun beta = run(d, "fake-beta");
        assertThat(beta.status()).isEqualTo(RunMetadata.ToolRun.Status.FAILED);
        assertThat(beta.reason()).contains("fake-beta exploded");
        assertThat(run(d, "fake-alpha").status()).isEqualTo(RunMetadata.ToolRun.Status.OK);
        assertThat(run(d, "fake-alpha").version()).contains("9.9");
        assertThat(run(d, "fake-alpha").candidates()).isEqualTo(1);
        assertThat(d.addCandidates()).extracting(Candidate::title).containsExactly("fake-alpha finding");
    }

    @Test
    void candidatesLeaveDetectUnfingerprinted() throws Exception {
        StateDelta d = node().apply(DetectFixtures.state(DetectFixtures.config(), List.of()),
                DetectFixtures.ctx(work, Optional.empty())).delta();

        assertThat(d.addCandidates()).hasSize(2).allMatch(c -> c.fingerprint().isEmpty());
        assertThat(d.replaceCandidates()).isEmpty();
    }

    @Test
    void sandboxAdapterIsSkippedWithoutASandboxAndGetsTheHandleWithOne() throws Exception {
        FakeAdapter.SANDBOX.put("fake-alpha", true);

        StateDelta without = node().apply(DetectFixtures.state(DetectFixtures.config(), List.of()),
                DetectFixtures.ctx(work, Optional.empty())).delta();
        assertThat(run(without, "fake-alpha").status()).isEqualTo(RunMetadata.ToolRun.Status.SKIPPED);
        assertThat(run(without, "fake-alpha").reason()).contains("requires sandbox");
        assertThat(FakeAdapter.LAST_REQUEST).doesNotContainKey("fake-alpha");

        SandboxRunner sandbox = (SandboxRunner) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SandboxRunner.class}, (p, m, a) -> {
                    throw new UnsupportedOperationException(m.getName());
                });
        StateDelta with = node().apply(DetectFixtures.state(DetectFixtures.config(), List.of()),
                DetectFixtures.ctx(work, Optional.of(sandbox))).delta();
        assertThat(run(with, "fake-alpha").status()).isEqualTo(RunMetadata.ToolRun.Status.OK);
        assertThat(FakeAdapter.LAST_REQUEST.get("fake-alpha").sandbox()).containsSame(sandbox);
        assertThat(FakeAdapter.LAST_REQUEST.get("fake-beta").sandbox()).isEmpty();
        assertThat(FakeAdapter.LAST_REQUEST.get("fake-beta").workDir())
                .isEqualTo(work.resolve("detect").resolve("fake-beta"));
    }

    @Test
    void selectionFlagsProduceSkippedToolRuns() throws Exception {
        var config = DetectFixtures.config(Set.of(), Set.of("fake-beta"), Duration.ofSeconds(30));

        StateDelta d = node().apply(DetectFixtures.state(config, List.of()),
                DetectFixtures.ctx(work, Optional.empty())).delta();

        assertThat(run(d, "fake-beta").status()).isEqualTo(RunMetadata.ToolRun.Status.SKIPPED);
        assertThat(run(d, "fake-beta").reason()).contains("excluded with --exclude-tools");
        assertThat(FakeAdapter.LAST_REQUEST).containsOnlyKeys("fake-alpha");
        assertThat(d.addCandidates()).extracting(Candidate::title).containsExactly("fake-alpha finding");
    }
}
