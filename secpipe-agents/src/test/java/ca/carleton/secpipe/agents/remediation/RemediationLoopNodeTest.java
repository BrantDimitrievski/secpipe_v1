package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.agents.remediation.RemediationAgent.RegressionTest;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.RemediationStatus;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;
import ca.carleton.secpipe.llm.CallBudget;
import ca.carleton.secpipe.llm.FakeLlmClient;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link RemediationLoopNode} with a content-driven fake sandbox, a fake rescanner and {@link FakeLlmClient}
 * (fixtures in {@code llm-fixtures/remediation-loop}). The fakes look at the patched {@code LoginServlet.java} of each
 * scratch copy: {@code prepareStatement(} makes the regression test pass, {@code createStatement} keeps the original
 * finding detected, and the markers {@code BROKEN_BUILD}, {@code BREAKS_SUITE} and {@code NEW_VULN} break the build,
 * a baseline test, or add a finding. WO-M.3 markers: {@code SKIPS_REGRESSION} skips the regression test (failed
 * assumption), {@code SANDBOX_DIES_REGRESSION} / {@code SANDBOX_DIES_SUITE} time the sandbox out in that run.
 */
class RemediationLoopNodeTest {

    static final Path FIXTURE = SuiteBaselineTest.FIXTURE;
    static final String LOGIN = "src/main/java/com/shop/app/LoginServlet.java";
    static final String REGRESSION_PATH = "src/test/java/secpipe/generated/LoginServletSqliRegressionTest.java";
    static final String REGRESSION_CLASS = "secpipe.generated.LoginServletSqliRegressionTest";
    static final String FP_OTHER = "code:CWE-89:0000000000000000000000a1";
    static final String FP_NEW = "code:CWE-78:0000000000000000000000ee";
    static final List<String> BASELINE = List.of(
            "com.shop.app.LoginServletTest#answersServerErrorWhenNoDatabaseIsConfigured()",
            "com.shop.app.LoginServletTest#isMappedToLogin()",
            "com.shop.app.TagFilterTest#answersServerErrorWhenNoDatabaseIsConfigured()",
            "com.shop.app.TagFilterTest#isMappedToProductTags()");

    @TempDir
    Path tmp;

    static String fp(String suffix) {
        return "code:CWE-89:00000000000000000000" + suffix;
    }

    // ------------------------------------------------------------------ fakes

    /** Scripted by content: answers prefetch, dry check, suite and regression runs; records every spec. */
    static final class FakeSandbox implements SandboxRunner {
        final List<Spec> specs = new ArrayList<>();
        boolean available = true;

        @Override
        public Capabilities probe() {
            return new Capabilities(available, EnumSet.allOf(Control.class),
                    available ? Optional.empty() : Optional.of("docker not found"), Optional.of("fake"));
        }

        @Override
        public Result run(Spec spec) {
            specs.add(spec);
            String purpose = spec.purpose();
            if (purpose.startsWith("maven ")) {
                return new Result(0, false, "", "", List.of(), Duration.ofSeconds(1), EnumSet.allOf(Control.class));
            }
            assertEquals(Network.NONE, spec.network(), purpose);
            assertEquals(EnumSet.allOf(Control.class), spec.required(), purpose);
            String login = read(spec.scratchDir().resolve(LOGIN));
            if (purpose.startsWith("regression test")) {
                assertTrue(Files.isRegularFile(spec.scratchDir().resolve(REGRESSION_PATH)), "regression test copied");
                if (login.contains("BROKEN_BUILD")) {
                    return new Result(SuiteBaseline.BUILD_FAILED_EXIT, false,
                            "[ERROR] COMPILATION ERROR : LoginServlet.java:[22,1] not a statement", "", List.of(),
                            Duration.ofSeconds(1), EnumSet.allOf(Control.class));
                }
                if (login.contains("SANDBOX_DIES_REGRESSION")) {
                    return died();
                }
                String body = login.contains("SKIPS_REGRESSION") ? "<skipped message=\"login query did not run\"/>"
                        : login.contains("prepareStatement(") ? ""
                        : "<failure message=\"attacker-controlled username reached the SQL text\"/>";
                return report(spec, "<testcase classname=\"" + REGRESSION_CLASS
                        + "\" name=\"usernamePayloadMustNotReachTheQueryText()\">" + body + "</testcase>");
            }
            assertTrue(purpose.startsWith(SuiteBaseline.PURPOSE_PREFIX), purpose);
            assertFalse(Files.exists(spec.scratchDir().resolve(REGRESSION_PATH)), "suite copy has no regression test");
            if (login.contains("SANDBOX_DIES_SUITE")) {
                return died();
            }
            StringBuilder cases = new StringBuilder();
            for (String id : BASELINE) {
                String[] parts = id.split("#");
                boolean broken = login.contains("BREAKS_SUITE") && id.equals(BASELINE.get(1));
                cases.append("<testcase classname=\"").append(parts[0]).append("\" name=\"").append(parts[1])
                        .append("\">").append(broken ? "<failure message=\"broken\"/>" : "").append("</testcase>");
            }
            return report(spec, cases.toString());
        }

        private static Result died() {
            return new Result(-1, true, "", "", List.of(), Duration.ofSeconds(600), EnumSet.allOf(Control.class));
        }

        private static Result report(Spec spec, String cases) {
            Path p = spec.scratchDir().resolve(SuiteBaseline.REPORTS.get(0));
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"JUnit Jupiter\">"
                        + cases + "</testsuite>", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Result(cases.contains("<failure") ? 1 : 0, false, "", "", List.of(p), Duration.ofSeconds(2),
                    EnumSet.allOf(Control.class));
        }

        long runs(String prefix) {
            return specs.stream().filter(s -> s.purpose().startsWith(prefix)).count();
        }
    }

    /** Original finding while {@code createStatement} is there, plus an unrelated baseline finding. */
    static final class FakeRescanner implements RunContext.Rescanner {
        final String original;
        final List<Path> copies = new ArrayList<>();

        FakeRescanner(String original) {
            this.original = original;
        }

        @Override
        public List<Candidate> rescan(Path copy, Set<String> scope) {
            copies.add(copy);
            String login = read(copy.resolve(LOGIN));
            List<Candidate> out = new ArrayList<>();
            out.add(candidate(FP_OTHER, "CWE-89"));
            if (login.contains("createStatement")) {
                out.add(candidate(original, "CWE-89"));
            }
            if (login.contains("NEW_VULN")) {
                out.add(candidate(FP_NEW, "CWE-78"));
            }
            return out;
        }
    }

    static Candidate candidate(String fp, String cwe) {
        String scope = "com.shop.app.LoginServlet#doPost(HttpServletRequest, HttpServletResponse)";
        return new Candidate(Optional.of(fp), Finding.Category.CODE, Finding.Origin.TOOL, List.of(), List.of(cwe),
                List.of(), "Finding " + cwe, "message", "root cause",
                List.of(new Finding.TraceStep(Finding.TraceKind.SINK, LOGIN, 22, scope, "sink")), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of());
    }

    static class Log implements RunContext.RunLog {
        final List<String> lines = new ArrayList<>();

        @Override
        public void info(String msg) {
            lines.add(msg);
        }

        @Override
        public void warn(String msg) {
            lines.add(msg);
        }

        @Override
        public void debug(String msg) {
            lines.add(msg);
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static FakeLlmClient fake() {
        try {
            return FakeLlmClient.fromDir(Path.of(Objects.requireNonNull(
                    RemediationLoopNodeTest.class.getResource("/llm-fixtures/remediation-loop")).toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static RegressionTest regressionTest() {
        return new RegressionTest(REGRESSION_PATH, read(FIXTURE.resolve(
                "regression/secpipe/generated/LoginServletSqliRegressionTest.java")));
    }

    /** A confirmed LoginServlet record that passes FindingsValidator F1–F9. */
    static Finding.Confirmed confirmed(String fingerprint) {
        String scope = "com.shop.app.LoginServlet#doPost(HttpServletRequest, HttpServletResponse)";
        String id = "te-0123456789ab";
        TestEvidence te = new TestEvidence(id, TestEvidence.Phase.PRE_FIX, Optional.of(REGRESSION_PATH),
                Optional.of(REGRESSION_CLASS), TestEvidence.Outcome.FAILED_AS_EXPECTED, 1,
                Optional.of(id + ": failed as expected"), Optional.empty(), Optional.empty());
        Finding.SecPipeExt ext = new Finding.SecPipeExt(Finding.Category.CODE, Finding.Origin.TOOL, List.of(),
                List.of("CWE-89"), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(te), List.of(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                Optional.empty(), Optional.empty());
        return new Finding.Confirmed(fingerprint, "SQL injection in LoginServlet", "username reaches executeQuery",
                "username is concatenated into the SQL text", "username is bound as a query parameter",
                List.of(new Finding.TraceStep(Finding.TraceKind.ENTRYPOINT, LOGIN, 18, scope, "request parameter"),
                        new Finding.TraceStep(Finding.TraceKind.SINK, LOGIN, 21, scope, "executeQuery")),
                List.of(new Finding.Evidence(LOGIN, 21, "concatenated query")), List.of(),
                new Finding.Execution("anonymous user", List.of("' OR '1'='1"), List.of("POST /login"),
                        id + ": failed as expected"),
                new Finding.Remediation("use a PreparedStatement", List.of()),
                new Finding.Severity(new Finding.Scored(Finding.Score5.MEDIUM, "remote"),
                        new Finding.Scored(Finding.Score5.MEDIUM, "data"), Finding.Score5.MEDIUM),
                new Finding.Confidence(Finding.Level3.HIGH, "regression test failed as expected"), ext);
    }

    static PipelineState state(Finding... records) {
        Map<String, Finding> byFp = new TreeMap<>();
        for (Finding f : records) {
            byFp.put(f.fingerprint(), f);
        }
        return new PipelineState("run-1", null, Optional.empty(), Optional.empty(), List.of(), List.of(), List.of(),
                byFp, Map.of(), List.of(), Map.of(), 0, 0, false, Map.of(), Optional.empty(), List.of(),
                Optional.empty());
    }

    // ------------------------------------------------------------------ harness

    Path target;
    FakeSandbox sandbox;
    FakeRescanner rescanner;
    FakeLlmClient llm;
    Log log;

    StateDelta run(Finding.Confirmed record, int maxAttempts) throws Exception {
        return run(record, maxAttempts, SecPipeConfig.FixMode.SUGGEST, new CallBudget(200, 25), true);
    }

    StateDelta run(Finding.Confirmed record, int maxAttempts, SecPipeConfig.FixMode mode, Budget budget,
                   boolean withSandbox) throws Exception {
        target = tmp.resolve("target");
        if (!Files.exists(target)) {
            SuiteBaseline.freshCopy(FIXTURE, target);
        }
        sandbox = new FakeSandbox();
        rescanner = new FakeRescanner(record.fingerprint());
        llm = fake().withBudget(budget);
        log = new Log();
        RemediationLoopNode.Settings settings = new RemediationLoopNode.Settings(mode, maxAttempts, true, false,
                target, tmp.resolve("out/.work"), tmp.resolve("out"));
        RemediationLoopNode.Services services = new RemediationLoopNode.Services(llm,
                withSandbox ? Optional.of(sandbox) : Optional.empty(), rescanner, budget, log,
                (r, work) -> Optional.of(regressionTest()));
        return RemediationLoopNode.evaluate(state(record), settings, services);
    }

    /** The records pass the findings schema and F1–F9 (F7 against the target tree). */
    void assertValid(List<Finding> records) {
        String json = ContractJson.writeFindings(records);
        ValidationResult r = FindingsValidator.validateJson(json, Optional.of(target), CapPolicy.NONE);
        assertTrue(r.ok(), r.toString());
    }

    static Finding.Confirmed only(StateDelta d, String fp) {
        return assertInstanceOf(Finding.Confirmed.class, d.putRecords().stream()
                .filter(f -> f.fingerprint().equals(fp)).findFirst().orElseThrow());
    }

    static List<FixAttempt.Outcome> outcomes(Finding.Confirmed c) {
        return c.secpipe().fixAttempts().stream().map(FixAttempt::outcome).toList();
    }

    static Map<String, byte[]> snapshot(Path root) throws IOException {
        return SuiteBaselineTest.snapshot(root);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void fixedOnTheFirstAttemptWritesThePatchAndLeavesTheTargetByteIdentical() throws Exception {
        target = tmp.resolve("target");
        SuiteBaseline.freshCopy(FIXTURE, target);
        Map<String, byte[]> before = snapshot(target);
        String fp = fp("c001");

        StateDelta d = run(confirmed(fp), 3);

        Finding.Confirmed c = only(d, fp);
        assertEquals(Optional.of(RemediationStatus.FIXED), c.secpipe().remediationStatus());
        assertEquals(List.of(FixAttempt.Outcome.FIXED), outcomes(c));
        FixAttempt a = c.secpipe().fixAttempts().get(0);
        assertEquals(Optional.of(true), a.regressionTestPassed());
        assertEquals(Optional.of(true), a.suitePassed());
        assertEquals(Optional.of(true), a.rescanClean());
        assertEquals(Optional.of("patches/CWE-89-000000000000-attempt1.patch"), a.patchArtifact());
        Path patch = tmp.resolve("out").resolve(a.patchArtifact().orElseThrow());
        assertEquals(read(FIXTURE.resolve("fix/prepared-statement.patch")), read(patch));
        assertEquals(Map.of(fp, 1), d.fixAttemptsByFingerprint());
        assertValid(d.putRecords());
        // once per run: prefetch + dry check + baseline; then one regression and one suite run
        assertEquals(1, sandbox.runs("maven prefetch"));
        assertEquals(1, sandbox.runs(SuiteBaseline.BASELINE_PURPOSE));
        assertEquals(1, sandbox.runs("regression test"));
        assertEquals(2, sandbox.runs(SuiteBaseline.PURPOSE_PREFIX));
        assertTrue(Files.isRegularFile(tmp.resolve("out/.work/remediation/" + SuiteBaseline.FILE_NAME)));
        // the user's tree is byte-identical
        Map<String, byte[]> after = snapshot(target);
        assertEquals(before.keySet(), after.keySet());
        before.forEach((k, v) -> assertArrayEquals(v, after.get(k), k));
        // no scratch copy is the target
        sandbox.specs.forEach(s -> assertFalse(s.scratchDir().toAbsolutePath().startsWith(target.toAbsolutePath())));
        rescanner.copies.forEach(p -> assertFalse(p.toAbsolutePath().startsWith(target.toAbsolutePath())));
    }

    @Test
    void stillPresentThenFixedOnTheSecondAttemptWithTheFailureInThePrompt() throws Exception {
        String fp = fp("c002");
        StateDelta d = run(confirmed(fp), 3);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.STILL_PRESENT, FixAttempt.Outcome.FIXED), outcomes(c));
        assertEquals(Optional.of(RemediationStatus.FIXED), c.secpipe().remediationStatus());
        assertEquals(Optional.of(false), c.secpipe().fixAttempts().get(0).regressionTestPassed());
        assertEquals(2, llm.calls().size());
        String second = llm.calls().get(1).request().userPrompt();
        assertTrue(second.contains("regression test did not pass (failed)"), second);
        assertTrue(second.contains("resp.sendError(403);"), "previous patch is shown");
        assertTrue(Files.isRegularFile(tmp.resolve("out/patches/CWE-89-000000000000-attempt1.patch")));
        assertTrue(Files.isRegularFile(tmp.resolve("out/patches/CWE-89-000000000000-attempt2.patch")));
        // fresh copies: attempt 2 did not see attempt 1's edits (it applied cleanly to the pristine source)
        assertTrue(Files.exists(tmp.resolve("out/.work/remediation/CWE-89-000000000000/attempt2-regression")));
        assertValid(d.putRecords());
    }

    @Test
    void newFindingMakesTheAttemptNewlyIntroducedAndAddsANeedsValidationRecord() throws Exception {
        String fp = fp("c003");
        StateDelta d = run(confirmed(fp), 1);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.NEWLY_INTRODUCED_FINDINGS), outcomes(c));
        assertEquals(Optional.of(RemediationStatus.NEWLY_INTRODUCED), c.secpipe().remediationStatus());
        assertEquals(List.of(FP_NEW), c.secpipe().fixAttempts().get(0).newFingerprints());
        assertEquals(Optional.of(false), c.secpipe().fixAttempts().get(0).rescanClean());
        Finding.NeedsValidation nv = assertInstanceOf(Finding.NeedsValidation.class, d.putRecords().stream()
                .filter(f -> f.fingerprint().equals(FP_NEW)).findFirst().orElseThrow());
        assertEquals(List.of("introduced by candidate fix patches/CWE-89-000000000000-attempt1.patch; not validated"),
                nv.blockers());
        assertEquals(Optional.of(RemediationStatus.NOT_ATTEMPTED), nv.secpipe().remediationStatus());
        assertTrue(Files.isRegularFile(tmp.resolve("out/patches/CWE-89-000000000000-attempt1.patch")));
        assertEquals(List.of(FP_NEW, fp), d.putRecords().stream().map(Finding::fingerprint).sorted().toList());
        assertValid(d.putRecords());
    }

    @Test
    void brokenBaselineTestIsTestsBroken() throws Exception {
        String fp = fp("c004");
        StateDelta d = run(confirmed(fp), 1);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.TESTS_BROKEN), outcomes(c));
        FixAttempt a = c.secpipe().fixAttempts().get(0);
        assertEquals(Optional.of(true), a.regressionTestPassed());
        assertEquals(Optional.of(false), a.suitePassed());
        assertEquals(Optional.of(RemediationStatus.STILL_PRESENT), c.secpipe().remediationStatus());
        assertTrue(rescanner.copies.size() == 1, "no rescan after a broken suite (only the baseline rescan)");
        assertValid(d.putRecords());
    }

    @Test
    void compileErrorIsBuildBrokenAndRunsNoSuite() throws Exception {
        String fp = fp("c005");
        StateDelta d = run(confirmed(fp), 1);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.BUILD_BROKEN), outcomes(c));
        assertEquals(Optional.empty(), c.secpipe().fixAttempts().get(0).regressionTestPassed());
        assertEquals(1, sandbox.runs(SuiteBaseline.PURPOSE_PREFIX), "only the baseline suite ran");
        assertValid(d.putRecords());
    }

    @Test
    void maxAttemptsReachedKeepsEveryAttemptAndIsStillPresent() throws Exception {
        String fp = fp("c006");
        StateDelta d = run(confirmed(fp), 3);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.STILL_PRESENT, FixAttempt.Outcome.STILL_PRESENT,
                FixAttempt.Outcome.STILL_PRESENT), outcomes(c));
        assertEquals(List.of(1, 2, 3), c.secpipe().fixAttempts().stream().map(FixAttempt::attempt).toList());
        assertEquals(Optional.of(RemediationStatus.STILL_PRESENT), c.secpipe().remediationStatus());
        assertEquals(3, llm.calls().size());
        assertEquals(Map.of(fp, 3), d.fixAttemptsByFingerprint());
        assertValid(d.putRecords());
    }

    @Test
    void patchThatEditsTheBuildFileIsRefusedAndNeverRuns() throws Exception {
        String fp = fp("c007");
        StateDelta d = run(confirmed(fp), 1);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.REFUSED), outcomes(c));
        assertEquals(Optional.empty(), c.secpipe().fixAttempts().get(0).patchArtifact());
        assertEquals(0, sandbox.runs("regression test"));
        assertFalse(Files.exists(tmp.resolve("out/patches")));
        assertValid(d.putRecords());
    }

    @Test
    void noSandboxIsNotAttemptedWithASandboxUnavailableAttempt() throws Exception {
        String fp = fp("c001");
        StateDelta d = run(confirmed(fp), 3, SecPipeConfig.FixMode.SUGGEST, new CallBudget(200, 25), false);

        Finding.Confirmed c = only(d, fp);
        assertEquals(Optional.of(RemediationStatus.NOT_ATTEMPTED), c.secpipe().remediationStatus());
        assertEquals(List.of(FixAttempt.Outcome.SANDBOX_UNAVAILABLE), outcomes(c));
        assertEquals(0, llm.calls().size());
        assertTrue(d.notes().stream().anyMatch(n -> n.contains("sandbox unavailable")), d.notes().toString());
        assertValid(d.putRecords());
    }

    @Test
    void unavailableDockerIsAlsoNotAttempted() throws Exception {
        target = tmp.resolve("target");
        SuiteBaseline.freshCopy(FIXTURE, target);
        String fp = fp("c001");
        FakeSandbox down = new FakeSandbox();
        down.available = false;
        Log l = new Log();
        StateDelta d = RemediationLoopNode.evaluate(state(confirmed(fp)), new RemediationLoopNode.Settings(
                SecPipeConfig.FixMode.SUGGEST, 3, true, false, target, tmp.resolve("w"), tmp.resolve("o")),
                new RemediationLoopNode.Services(fake(), Optional.of(down), new FakeRescanner(fp),
                        new CallBudget(200, 25), l, (r, w) -> Optional.of(regressionTest())));
        assertEquals(Optional.of(RemediationStatus.NOT_ATTEMPTED), only(d, fp).secpipe().remediationStatus());
        assertTrue(down.specs.isEmpty());
    }

    @Test
    void enableFixesFalseIsANoOp() throws Exception {
        StateDelta d = run(confirmed(fp("c001")), 3, SecPipeConfig.FixMode.FALSE, new CallBudget(200, 25), true);

        assertEquals(StateDelta.empty(), d);
        assertTrue(sandbox.specs.isEmpty());
        assertEquals(0, llm.calls().size());
    }

    @Test
    void sandboxRunBudgetExhaustedKeepsTheRecordAndNotesIt() throws Exception {
        String fp = fp("c008");
        // baseline + regression run fit; the suite run does not
        StateDelta d = run(confirmed(fp), 3, SecPipeConfig.FixMode.SUGGEST, new CallBudget(200, 2), true);

        assertTrue(d.putRecords().isEmpty(), "the record keeps its status");
        assertTrue(d.notes().stream().anyMatch(n -> n.contains("sandbox run budget exhausted")), d.notes().toString());
        assertEquals(1, sandbox.runs("regression test"));
    }

    @Test
    void agentCallBudgetExhaustedKeepsTheRecordAndNotesIt() throws Exception {
        String fp = fp("c008");
        StateDelta d = run(confirmed(fp), 3, SecPipeConfig.FixMode.SUGGEST, new CallBudget(0, 25), true);

        assertTrue(d.putRecords().isEmpty(), "the record keeps its status");
        assertTrue(d.notes().stream().anyMatch(n -> n.contains("agent-call budget exhausted")), d.notes().toString());
    }

    @Test
    void nonConfirmedCodeRecordsAreMarkedNotAttemptedWithoutAnySandboxRun() throws Exception {
        target = tmp.resolve("target");
        SuiteBaseline.freshCopy(FIXTURE, target);
        Finding.Confirmed c = confirmed(fp("c001"));
        Finding.NeedsValidation nv = new Finding.NeedsValidation(fp("c009"), "t", "d", "r", c.trace(), c.evidence(),
                List.of("sandbox unavailable"), new Finding.ValidationPlan(Optional.of("x"), Optional.empty()),
                c.secpipe());
        Finding.Rejected rj = new Finding.Rejected(fp("c00a"), "t", "d", "r", c.trace(), c.evidence(),
                "input is validated", c.secpipe());
        FakeSandbox sb = new FakeSandbox();
        FakeLlmClient client = fake();
        StateDelta d = RemediationLoopNode.evaluate(state(nv, rj), new RemediationLoopNode.Settings(
                SecPipeConfig.FixMode.SUGGEST, 3, true, false, target, tmp.resolve("w"), tmp.resolve("o")),
                new RemediationLoopNode.Services(client, Optional.of(sb), new FakeRescanner(nv.fingerprint()),
                        new CallBudget(200, 25), new Log(), (r, w) -> Optional.of(regressionTest())));

        assertEquals(List.of(nv.fingerprint(), rj.fingerprint()),
                d.putRecords().stream().map(Finding::fingerprint).toList());
        d.putRecords().forEach(f -> {
            assertEquals(Optional.of(RemediationStatus.NOT_ATTEMPTED), f.secpipe().remediationStatus(),
                    f.fingerprint());
            assertTrue(f.secpipe().fixAttempts().isEmpty(), f.fingerprint());
        });
        assertInstanceOf(Finding.NeedsValidation.class, d.putRecords().get(0));
        assertInstanceOf(Finding.Rejected.class, d.putRecords().get(1));
        assertTrue(sb.specs.isEmpty());
        assertEquals(0, client.calls().size());
        assertValid(d.putRecords());
    }

    @Test
    void nonConfirmedRecordsAreLeftAloneWhenFixesAreOff() throws Exception {
        Finding.Confirmed c = confirmed(fp("c001"));
        Finding.NeedsValidation nv = new Finding.NeedsValidation(fp("c009"), "t", "d", "r", c.trace(), c.evidence(),
                List.of("sandbox unavailable"), new Finding.ValidationPlan(Optional.of("x"), Optional.empty()),
                c.secpipe());
        StateDelta d = RemediationLoopNode.evaluate(state(nv), new RemediationLoopNode.Settings(
                SecPipeConfig.FixMode.FALSE, 3, true, false, tmp, tmp.resolve("w"), tmp.resolve("o")),
                new RemediationLoopNode.Services(fake(), Optional.of(new FakeSandbox()),
                        new FakeRescanner(nv.fingerprint()), new CallBudget(200, 25), new Log(),
                        (r, w) -> Optional.of(regressionTest())));
        assertEquals(StateDelta.empty(), d);
    }

    @Test
    void regressionTestSkippedAfterThePatchIsStillPresentButMarkedFixUnverifiedAndNotRetried() throws Exception {
        String fp = fp("c010");
        StateDelta d = run(confirmed(fp), 3);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.STILL_PRESENT), outcomes(c));
        FixAttempt a = c.secpipe().fixAttempts().get(0);
        assertTrue(a.strategy().orElseThrow().startsWith(FixClassifier.FIX_UNVERIFIED), a.toString());
        assertTrue(a.strategy().orElseThrow().contains("PreparedStatement"), "the agent's strategy is kept");
        assertTrue(FixClassifier.isFixUnverified(a));
        assertEquals(Optional.of(false), a.regressionTestPassed());
        assertEquals(Optional.of(RemediationStatus.STILL_PRESENT), c.secpipe().remediationStatus());
        assertEquals(1, llm.calls().size(), "no further attempt after a post-fix skip");
        assertEquals(1, sandbox.runs("regression test"));
        assertEquals(1, sandbox.runs(SuiteBaseline.PURPOSE_PREFIX), "only the baseline suite ran");
        assertTrue(d.notes().stream().anyMatch(n -> n.contains(FixClassifier.FIX_UNVERIFIED)), d.notes().toString());
        assertTrue(Files.isRegularFile(tmp.resolve("out/patches/CWE-89-000000000000-attempt1.patch")));
        assertValid(d.putRecords());
    }

    @Test
    void sandboxDyingDuringTheRegressionTestIsSandboxUnavailableNotStillPresent() throws Exception {
        String fp = fp("c011");
        StateDelta d = run(confirmed(fp), 3);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.SANDBOX_UNAVAILABLE), outcomes(c));
        assertEquals(Optional.of(RemediationStatus.NOT_ATTEMPTED), c.secpipe().remediationStatus());
        assertEquals(1, llm.calls().size(), "the record is not retried once the sandbox died");
        assertEquals(Map.of(fp, 0), d.fixAttemptsByFingerprint());
        assertTrue(d.notes().stream().anyMatch(n -> n.contains("sandbox unavailable during the regression test")),
                d.notes().toString());
        assertValid(d.putRecords());
    }

    @Test
    void sandboxDyingDuringTheSuiteIsSandboxUnavailableNotTestsBroken() throws Exception {
        String fp = fp("c012");
        StateDelta d = run(confirmed(fp), 3);

        Finding.Confirmed c = only(d, fp);
        assertEquals(List.of(FixAttempt.Outcome.SANDBOX_UNAVAILABLE), outcomes(c));
        assertEquals(Optional.of(RemediationStatus.NOT_ATTEMPTED), c.secpipe().remediationStatus());
        assertEquals(1, llm.calls().size());
        assertEquals(1, rescanner.copies.size(), "no rescan after the sandbox died (only the baseline rescan)");
        assertTrue(d.notes().stream().anyMatch(n -> n.contains("sandbox unavailable during the project tests")),
                d.notes().toString());
        assertValid(d.putRecords());
    }

    @Test
    void regressionTestFromEvidenceIsReadFromTheEvidenceScratchCopy() throws Exception {
        Finding.Confirmed c = confirmed(fp("c001"));
        Path work = tmp.resolve("work");
        assertEquals(Optional.empty(), RemediationLoopNode.FROM_EVIDENCE.find(c, work));
        Path file = work.resolve("evidence/te-0123456789ab").resolve(REGRESSION_PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X { }\n");
        assertEquals(Optional.of(new RegressionTest(REGRESSION_PATH, "class X { }\n")),
                RemediationLoopNode.FROM_EVIDENCE.find(c, work));
    }

    @Test
    void shortIdAndTestClassName() {
        assertEquals("CWE-89-0123456789ab", RemediationLoopNode.shortId("code:CWE-89:0123456789abcdef01234567"));
        assertEquals(REGRESSION_CLASS, RemediationLoopNode.fqcn(REGRESSION_PATH));
    }
}
