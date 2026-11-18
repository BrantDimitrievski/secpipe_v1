package ca.carleton.secpipe.eval.benchmark;

import ca.carleton.secpipe.contracts.finding.Finding;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Synthetic inputs shared by the scorecard tests (no real Benchmark content). */
final class BenchmarkScorecardFixtures {

    private BenchmarkScorecardFixtures() {
    }

    static String resource(String name) {
        try (InputStream in = BenchmarkScorecardFixtures.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<ExpectedResults.Row> expected() {
        return ExpectedResults.parse(resource("expected-mini.csv"));
    }

    private static Finding.SecPipeExt ext(String cwe) {
        return new Finding.SecPipeExt(Finding.Category.CODE, Finding.Origin.TOOL, List.of(), List.of(cwe), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty());
    }

    private static Finding.TraceStep sink(int test) {
        return new Finding.TraceStep(Finding.TraceKind.SINK,
                "src/main/java/org/owasp/benchmark/testcode/BenchmarkTest%05d.java".formatted(test), 10, "m", "sink");
    }

    static Finding needsValidation(int test, String cwe) {
        return new Finding.NeedsValidation("code:" + cwe + ":" + test, "t", "d", "claimed", List.of(sink(test)),
                List.of(), List.of("No verifier configured"), new Finding.ValidationPlan(Optional.empty(),
                Optional.empty()), ext(cwe));
    }

    static Finding rejected(int test, String cwe) {
        return new Finding.Rejected("code:" + cwe + ":" + test, "t", "d", "claimed", List.of(sink(test)), List.of(),
                "refuted", ext(cwe));
    }

    static Finding confirmed(int test, String cwe) {
        return new Finding.Confirmed("code:" + cwe + ":" + test, "t", "d", "root", "intended", List.of(sink(test)),
                List.of(), List.of(), new Finding.Execution("a", List.of(), List.of(), "observed"),
                new Finding.Remediation("fix", List.of()), new Finding.Severity(
                new Finding.Scored(Finding.Score5.HIGH, "r"), new Finding.Scored(Finding.Score5.HIGH, "r"),
                Finding.Score5.HIGH), new Finding.Confidence(Finding.Level3.HIGH, "r"), ext(cwe));
    }

    /** Expected: 1 TP (family 23->22), 2 TN (rejected), 3 TP, 4 FP, 5 FN, 6 TN, 7 TP (326->327), 8 FN (wrong family). */
    static List<Finding> findings() {
        return List.of(
                needsValidation(1, "CWE-23"),
                rejected(2, "CWE-22"),
                needsValidation(3, "CWE-78"),
                confirmed(4, "CWE-78"),
                needsValidation(7, "CWE-326"),
                needsValidation(8, "CWE-327"));
    }
}
