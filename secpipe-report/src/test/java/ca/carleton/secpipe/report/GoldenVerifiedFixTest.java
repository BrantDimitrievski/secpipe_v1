package ca.carleton.secpipe.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.report.patch.PatchSource;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WO-M.5: the shared golden bundle rendered <em>with</em> a {@link PatchSource} that holds its patch shows the
 * verified-fix wording; {@code ReportTest} covers the same bundle without one ("fixed (not verified)").
 */
class GoldenVerifiedFixTest {

    private static final String ARTIFACT = "patches/code-CWE-89-3f9a1c0d.patch";

    private static String render(PatchSource patches) {
        ReportBundle b = ReportBundle.render(Fixtures.inputs(), new DefaultReportRenderer(), Fixtures.allFormats(),
                patches);
        assertTrue(b.ok(), () -> "bundle failed: " + b.incompleteReason());
        return b.files().get(ReportBundle.MARKDOWN);
    }

    @Test
    void aReadablePatchMakesTheGoldenFixVerified() {
        String md = render(a -> a.equals(ARTIFACT)
                ? Optional.of(Fixtures.resource("remediation/patches/code-CWE-89-3f9a1c0d.patch")) : Optional.empty());

        assertTrue(md.contains("1 of 1 confirmed findings fixed."), md);
        assertTrue(md.contains("- Outcome: fixed\n"), md);
        assertTrue(md.contains("- Patch (verified): [" + ARTIFACT + "](" + ARTIFACT + ")"), md);
        assertFalse(md.contains("not verified"), md);
    }

    @Test
    void withoutThePatchTheSameBundleStaysNotVerifiedAndMatchesTheGolden() {
        String md = render(PatchSource.NONE);

        assertEquals(Fixtures.resource("golden/report.md"), md);
        assertTrue(md.contains("fixed (not verified: its patch file was not found)"), md);
    }
}
