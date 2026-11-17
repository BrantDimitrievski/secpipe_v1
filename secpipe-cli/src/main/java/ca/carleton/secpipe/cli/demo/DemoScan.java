package ca.carleton.secpipe.cli.demo;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.validators.SchemaRegistry;
import ca.carleton.secpipe.validators.ValidationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Demo slice (WO-DEMO.1): deterministic CWE-89 detector to findings.json (schema-validated) and report.md.
 * No AI, no sandbox, no remediation. Every hit is a {@code needs_validation} finding: the contract has no lower
 * verdict, and without a sandbox regression test nothing can be confirmed.
 */
public final class DemoScan {

    public static final String TOOL = "secpipe-demo-builtin";
    public static final String RULE_ID = "demo-sqli-concat";
    public static final String CWE = "CWE-89";
    public static final String BLOCKER = "demo slice: no sandbox regression test or adversarial disprover was run; "
            + "the hit is a static pattern match only";

    private DemoScan() {
    }

    /** Outcome of a demo scan. */
    public record Outcome(List<Finding> findings, List<SqliHit> hits, int filesParsed, int filesSkipped,
                          ValidationResult validation, Path findingsFile, Path reportFile) {
        public Outcome {
            findings = List.copyOf(findings);
            hits = List.copyOf(hits);
        }
    }

    /** Scans {@code target} and writes {@code findings.json} and {@code report.md} into {@code out}. */
    public static Outcome run(Path target, String targetLabel, Path out) {
        DemoSqliDetector.Result result = new DemoSqliDetector().scan(target);
        List<Finding> findings = toFindings(result.hits());
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path findingsFile = out.resolve("findings.json");
        ContractJson.writeFindings(findingsFile, findings);
        String json;
        try {
            json = Files.readString(findingsFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ValidationResult validation = SchemaRegistry.shared().validate("findings.schema.json", json);
        Path reportFile = out.resolve("report.md");
        try {
            Files.writeString(reportFile, DemoReport.render(targetLabel, result, findings), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Outcome(findings, result.hits(), result.filesParsed(), result.filesSkipped(), validation,
                findingsFile, reportFile);
    }

    static List<Finding> toFindings(List<SqliHit> hits) {
        Map<String, Integer> occurrences = new HashMap<>();
        List<Finding> findings = new ArrayList<>(hits.size());
        for (SqliHit hit : hits) {
            String key = hit.file() + "\u0000" + hit.scope() + "\u0000" + hit.sinkCall();
            int occurrence = occurrences.merge(key, 1, Integer::sum) - 1;
            findings.add(toFinding(hit, Fingerprints.code(CWE, hit.file(), hit.scope(), hit.sinkCall(), occurrence)));
        }
        return List.copyOf(findings);
    }

    private static Finding toFinding(SqliHit hit, String fingerprint) {
        String operands = String.join(", ", hit.variables());
        String how = hit.viaVariable().isEmpty()
                ? "is built by string concatenation with non-literal value(s): " + operands
                : "is the variable '" + hit.viaVariable() + "', assigned from a string concatenation with non-literal"
                        + " value(s): " + operands;
        String title = "Possible SQL injection: concatenated SQL passed to " + hit.method() + "()";
        String description = "In " + hit.scope() + ", the SQL argument of " + hit.method() + "() " + how
                + ". If any of those values is attacker-controlled, the query structure can be changed.";
        String rootCause = "SQL text is assembled by string concatenation instead of using bind parameters.";
        Finding.TraceStep sink = new Finding.TraceStep(Finding.TraceKind.SINK, hit.file(), hit.line(), hit.scope(),
                "JDBC sink " + hit.method() + "() receives concatenated SQL: " + hit.sinkCall());
        Finding.Evidence evidence = new Finding.Evidence(hit.file(), hit.line(),
                "Static match (" + RULE_ID + "): SQL argument " + how + ".");
        Finding.ValidationPlan plan = new Finding.ValidationPlan(Optional.of(
                "Trace each concatenated value back to its source; if any is request-controlled, write a JUnit test "
                        + "that passes a quote-breaking payload and observe the altered query."), Optional.empty());
        Finding.SecPipeExt ext = new Finding.SecPipeExt(Finding.Category.CODE, Finding.Origin.TOOL,
                List.of(new Finding.ToolSource(TOOL, RULE_ID, Optional.of(Finding.Score5.HIGH),
                        Optional.of(hit.file()), Optional.of(hit.line()))),
                List.of(CWE), List.of(), Optional.of("A03:2021-Injection"), Optional.empty(),
                Optional.of(Finding.Score5.HIGH), Optional.of(Finding.Level3.LOW), List.of(), List.of(),
                Optional.empty(),
                Optional.of("Use a PreparedStatement with '?' placeholders and bind values with setString/setInt."),
                Optional.empty(), List.of(), Optional.empty(), Optional.empty());
        return new Finding.NeedsValidation(fingerprint, title, description, rootCause, List.of(sink),
                List.of(evidence), List.of(BLOCKER), plan, ext);
    }
}
