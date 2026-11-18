package ca.carleton.secpipe.report.patch;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Adequacy;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.RemediationStatus;
import ca.carleton.secpipe.contracts.finding.Finding.SecPipeExt;
import ca.carleton.secpipe.contracts.json.ContractJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Confirmed records in each remediation state, derived from the shared report fixture (first record). */
public final class RemediationFixtures {

    static final String BASE = "/ca/carleton/secpipe/report/";
    public static final String FP_FIXED = "code:CWE-89:3f9a1c0d5e7b2a4c6d8e0f12";
    public static final String FP_STILL = "code:CWE-89:b2b2b2b2b2b2b2b2b2b2b2b2";
    public static final String FP_UNVERIFIED = "code:CWE-89:c3c3c3c3c3c3c3c3c3c3c3c3";
    public static final String FP_NOT_ATTEMPTED = "code:CWE-89:d4d4d4d4d4d4d4d4d4d4d4d4";

    private RemediationFixtures() {
    }

    public static String resource(String name) {
        try (InputStream in = RemediationFixtures.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + BASE + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads {@code remediation/<artifact>} from the test resources. */
    public static Optional<String> patch(String artifact) {
        try (InputStream in = RemediationFixtures.class.getResourceAsStream(BASE + "remediation/" + artifact)) {
            return in == null ? Optional.empty()
                    : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Finding.Confirmed base() {
        return (Finding.Confirmed) ContractJson.readFindings(resource("inputs/findings.json")).getFirst();
    }

    private static Finding.Confirmed with(String fp, String title, List<FixAttempt> attempts,
            Optional<RemediationStatus> status) {
        Finding.Confirmed b = base();
        SecPipeExt x = b.secpipe();
        SecPipeExt ext = new SecPipeExt(x.category(), x.origin(), x.toolSources(), x.cwe(), x.cve(), x.owasp(),
                x.dependency(), x.toolSeverityHint(), x.confidenceHint(), x.testEvidence(), attempts, status,
                x.recommendedRemediation(), x.verification(), x.ledgerUnits(), x.firstSeenRun(),
                x.carriedForwardFrom());
        return new Finding.Confirmed(fp, title, b.description(), b.rootCause(), b.intendedBehavior(), b.trace(),
                b.evidence(), b.conditions(), b.execution(), b.remediation(), b.severity(), b.confidence(), ext);
    }

    private static FixAttempt attempt(int n, String patch, FixAttempt.Outcome outcome, Optional<Boolean> regression,
            Optional<Boolean> suite, Optional<Boolean> rescan, List<String> fps, Optional<Adequacy> adequacy) {
        return new FixAttempt(n, Optional.ofNullable(patch), Optional.of("strategy " + n), outcome, regression, suite,
                rescan, fps, adequacy);
    }

    /** The shared fixture's own record: fixed on attempt 1. */
    public static Finding.Confirmed fixed() {
        return base();
    }

    public static Finding.Confirmed fixedAs(String fp, String patch) {
        return with(fp, "Fixed " + fp, List.of(attempt(1, patch, FixAttempt.Outcome.FIXED, Optional.of(true),
                Optional.of(true), Optional.of(true), List.of(), Optional.empty())),
                Optional.of(RemediationStatus.FIXED));
    }

    /** Attempt 1 broke the tests, attempt 2 left the finding in place. */
    public static Finding.Confirmed stillPresent() {
        return with(FP_STILL, "Still vulnerable admin export", List.of(
                attempt(2, "patches/code-CWE-89-b2b2b2b2-attempt2.patch", FixAttempt.Outcome.STILL_PRESENT,
                        Optional.of(false), Optional.empty(), Optional.empty(), List.of(), Optional.empty()),
                attempt(1, "patches/code-CWE-89-b2b2b2b2-attempt1.patch", FixAttempt.Outcome.TESTS_BROKEN,
                        Optional.of(true), Optional.of(false), Optional.empty(), List.of(), Optional.empty())),
                Optional.of(RemediationStatus.STILL_PRESENT));
    }

    /** Still present because the regression test was skipped after the patch (WO-M.3). */
    public static Finding.Confirmed unverified() {
        return with(FP_UNVERIFIED, "Export servlet injection", List.of(
                attempt(1, "patches/code-CWE-89-c3c3c3c3-attempt1.patch", FixAttempt.Outcome.STILL_PRESENT,
                        Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                        Optional.of(new Adequacy(4, 1, false, Optional.of("only one changed line is exercised"))))),
                Optional.of(RemediationStatus.STILL_PRESENT));
    }

    /** No status and no attempts. */
    public static Finding.Confirmed notAttempted() {
        return with(FP_NOT_ATTEMPTED, "Report servlet injection", List.of(), Optional.empty());
    }
}
