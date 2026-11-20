package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.report.patch.DiffFiles;
import ca.carleton.secpipe.report.patch.FixedPatches;
import ca.carleton.secpipe.report.patch.PatchSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SARIF 2.1.0 for GitHub code scanning. One rule per CWE family; {@code partialFingerprints.secpipe/v1} is the
 * finding fingerprint; rejected findings are kept as suppressed results so they stay auditable.
 */
final class SarifWriter {

    static final String SCHEMA_URI = "https://json.schemastore.org/sarif-2.1.0.json";
    static final String FINGERPRINT_KEY = "secpipe/v1";
    static final String RULE_DEPENDENCY = "SECPIPE-DEPENDENCY";
    static final String RULE_UNCLASSIFIED = "SECPIPE-UNCLASSIFIED";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SarifWriter() {
    }

    static String render(ReportInputs inputs) {
        return render(inputs, PatchSource.NONE);
    }

    /** As {@link #render(ReportInputs)}, plus {@code fixes[]} on the results of verified fixes. */
    static String render(ReportInputs inputs, PatchSource patches) {
        List<Finding> findings = inputs.findings();
        Map<String, FixedPatches.Fix> fixes = new TreeMap<>();
        FixedPatches.fixes(findings, patches).forEach(f -> fixes.put(f.finding().fingerprint(), f));
        // rule id -> index, in sorted rule-id order so the rule table is byte-stable.
        Map<String, Integer> ruleIndex = new TreeMap<>();
        findings.forEach(f -> ruleIndex.put(ruleId(f), 0));
        int next = 0;
        for (Map.Entry<String, Integer> e : ruleIndex.entrySet()) {
            e.setValue(next++);
        }

        ObjectNode root = NODES.objectNode();
        root.put("$schema", SCHEMA_URI);
        root.put("version", "2.1.0");
        ObjectNode run = root.putArray("runs").addObject();
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", "SecPipe");
        driver.put("version", inputs.metadata().secpipeVersion());
        ArrayNode rules = driver.putArray("rules");
        ruleIndex.keySet().forEach(id -> rules.add(rule(id)));
        ArrayNode results = run.putArray("results");
        for (Finding f : findings) {
            ObjectNode r = result(f, ruleId(f), ruleIndex.get(ruleId(f)));
            FixedPatches.Fix fix = fixes.get(f.fingerprint());
            if (fix != null) {
                fixes(fix, patches).ifPresent(arr -> r.set("fixes", arr));
            }
            results.add(r);
        }
        return ContractJson.mapper().writer().with(ContractJson.prettyPrinter()).writeValueAsString(root) + "\n";
    }

    // ------------------------------------------------------------------------------------------------------------

    static String ruleId(Finding f) {
        List<String> cwe = f.secpipe().cwe();
        if (!cwe.isEmpty()) {
            return Fingerprints.cweFamily(cwe.getFirst());
        }
        return f.secpipe().category() == Finding.Category.DEPENDENCY ? RULE_DEPENDENCY : RULE_UNCLASSIFIED;
    }

    private static ObjectNode rule(String id) {
        ObjectNode rule = NODES.objectNode();
        rule.put("id", id);
        rule.put("name", id);
        String text;
        ArrayNode tags = NODES.arrayNode();
        tags.add("security");
        if (id.equals(RULE_DEPENDENCY)) {
            text = "Vulnerable dependency";
        } else if (id.equals(RULE_UNCLASSIFIED)) {
            text = "Security finding without a CWE classification";
        } else {
            text = id + " weakness family";
            tags.add("external/cwe/" + id.toLowerCase(Locale.ROOT));
        }
        rule.putObject("shortDescription").put("text", text);
        if (id.startsWith("CWE-")) {
            rule.put("helpUri", "https://cwe.mitre.org/data/definitions/" + id.substring("CWE-".length()) + ".html");
        }
        rule.putObject("properties").set("tags", tags);
        return rule;
    }

    private static ObjectNode result(Finding f, String ruleId, int ruleIndex) {
        ObjectNode r = NODES.objectNode();
        r.put("ruleId", ruleId);
        r.put("ruleIndex", ruleIndex);
        Optional<Score5> severity = severity(f);
        r.put("level", level(severity));
        r.putObject("message").put("text", f.title() + ": " + f.description());

        Finding.TraceStep sink = f.trace().getLast();
        r.putArray("locations").add(location(sink.file(), sink.line(), Optional.empty()));
        r.putObject("partialFingerprints").put(FINGERPRINT_KEY, f.fingerprint());

        ArrayNode flowLocations = NODES.arrayNode();
        for (Finding.TraceStep step : f.trace()) {
            ObjectNode wrapper = NODES.objectNode();
            wrapper.set("location", location(step.file(), step.line(),
                    Optional.of(step.kind().name().toLowerCase(Locale.ROOT) + ": " + step.description())));
            flowLocations.add(wrapper);
        }
        ObjectNode thread = NODES.objectNode();
        thread.set("locations", flowLocations);
        r.putArray("codeFlows").addObject().putArray("threadFlows").add(thread);

        if (f instanceof Finding.Rejected rejected) {
            ObjectNode s = r.putArray("suppressions").addObject();
            s.put("kind", "external");
            s.put("status", "accepted");
            s.put("justification", rejected.reason());
        }

        ObjectNode props = r.putObject("properties");
        props.put("verdict", f.verdict().name().toLowerCase(Locale.ROOT));
        props.put("category", f.secpipe().category().name().toLowerCase(Locale.ROOT));
        props.put("origin", f.secpipe().origin().name().toLowerCase(Locale.ROOT));
        severity.ifPresent(s -> {
            props.put("severity", s.name().toLowerCase(Locale.ROOT));
            props.put("security-severity", securitySeverity(s));
        });
        if (!f.secpipe().cwe().isEmpty()) {
            ArrayNode cwes = props.putArray("cwe");
            f.secpipe().cwe().forEach(cwes::add);
        }
        return r;
    }

    /** One SARIF fix per verified patch; empty when the patch cannot be read or described. */
    private static Optional<ArrayNode> fixes(FixedPatches.Fix fix, PatchSource patches) {
        Optional<List<DiffFiles.FileDiff>> diff = patches.read(fix.artifact()).flatMap(DiffFiles::parse);
        if (diff.isEmpty()) {
            return Optional.empty();
        }
        ArrayNode fixes = NODES.arrayNode();
        ObjectNode f = fixes.addObject();
        f.putObject("description").put("text", fix.attempt().strategy().filter(s -> !s.isBlank())
                .orElse("Verified fix (regression test, suite and rescan passed)"));
        ArrayNode changes = f.putArray("artifactChanges");
        for (DiffFiles.FileDiff file : diff.get()) {
            ObjectNode change = changes.addObject();
            ObjectNode artifact = change.putObject("artifactLocation");
            artifact.put("uri", uri(file.path()));
            artifact.put("uriBaseId", "%SRCROOT%");
            ArrayNode replacements = change.putArray("replacements");
            for (DiffFiles.Hunk h : file.hunks()) {
                ObjectNode rep = replacements.addObject();
                ObjectNode deleted = rep.putObject("deletedRegion");
                if (h.oldCount() == 0) {
                    deleted.put("startLine", Math.max(1, h.oldStart() + 1));
                    deleted.put("startColumn", 1);
                    deleted.put("endColumn", 1);
                } else {
                    deleted.put("startLine", Math.max(1, h.oldStart()));
                    deleted.put("endLine", Math.max(1, h.oldStart() + h.oldCount() - 1));
                }
                rep.putObject("insertedContent").put("text", h.newText());
            }
        }
        return Optional.of(fixes);
    }

    private static ObjectNode location(String file, int line, Optional<String> message) {
        ObjectNode loc = NODES.objectNode();
        ObjectNode physical = loc.putObject("physicalLocation");
        ObjectNode artifact = physical.putObject("artifactLocation");
        artifact.put("uri", uri(file));
        artifact.put("uriBaseId", "%SRCROOT%");
        physical.putObject("region").put("startLine", Math.max(1, line));
        message.ifPresent(m -> loc.putObject("message").put("text", m));
        return loc;
    }

    private static String uri(String repoRelativePath) {
        try {
            return new URI(null, null, repoRelativePath.replace('\\', '/'), null).getRawPath();
        } catch (URISyntaxException e) {
            return repoRelativePath;
        }
    }

    /** Confirmed: the overall severity. Others: the tool's hint, if any. */
    static Optional<Score5> severity(Finding f) {
        if (f instanceof Finding.Confirmed c) {
            return Optional.of(c.severity().overallSeverity());
        }
        return f.secpipe().toolSeverityHint();
    }

    private static String level(Optional<Score5> severity) {
        return severity.map(s -> switch (s) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFORMATIONAL -> "note";
        }).orElse("note");
    }

    private static String securitySeverity(Score5 s) {
        return switch (s) {
            case CRITICAL -> "9.5";
            case HIGH -> "8.0";
            case MEDIUM -> "5.5";
            case LOW -> "3.0";
            case INFORMATIONAL -> "0.0";
        };
    }
}
