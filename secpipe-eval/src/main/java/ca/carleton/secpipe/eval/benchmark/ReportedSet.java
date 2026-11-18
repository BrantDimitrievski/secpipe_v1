package ca.carleton.secpipe.eval.benchmark;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The (Benchmark test, CWE family) pairs a scan reported. A pair is reported when a finding sits in
 * {@code BenchmarkTestNNNNN.java} with a CWE in the same family (ADR-05: confirmed and needs_validation are reported,
 * rejected is not; a SARIF result with an accepted suppression is not reported).
 */
public final class ReportedSet {

    private static final Pattern TEST = Pattern.compile("BenchmarkTest\\d{5}");
    private static final Pattern CWE = Pattern.compile("(?i)cwe[-_/ ]?0*(\\d+)");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Set<String> pairs;

    private ReportedSet(Set<String> pairs) {
        this.pairs = Set.copyOf(pairs);
    }

    public static ReportedSet empty() {
        return new ReportedSet(Set.of());
    }

    public boolean contains(String test, String cweFamily) {
        return pairs.contains(key(test, cweFamily));
    }

    public int size() {
        return pairs.size();
    }

    public ReportedSet union(ReportedSet other) {
        Set<String> all = new HashSet<>(pairs);
        all.addAll(other.pairs);
        return new ReportedSet(all);
    }

    private static String key(String test, String family) {
        return test + "|" + Fingerprints.cweFamily(family);
    }

    public static ReportedSet fromFindings(List<Finding> findings) {
        Set<String> out = new HashSet<>();
        for (Finding f : findings) {
            if (f.verdict() == Finding.Verdict.REJECTED) {
                continue;
            }
            List<String> files = new ArrayList<>();
            f.trace().forEach(t -> files.add(t.file()));
            f.evidence().forEach(e -> files.add(e.file()));
            f.secpipe().toolSources().forEach(s -> s.file().ifPresent(files::add));
            for (String file : files) {
                tests(file).forEach(test -> f.secpipe().cwe().forEach(cwe -> out.add(key(test, cwe))));
            }
        }
        return new ReportedSet(out);
    }

    public static ReportedSet fromSarif(Path file) {
        try {
            return fromSarif(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public static ReportedSet fromSarif(String sarifJson) {
        JsonNode root = JSON.readTree(sarifJson);
        Set<String> out = new HashSet<>();
        for (JsonNode run : root.path("runs")) {
            JsonNode rules = run.path("tool").path("driver").path("rules");
            for (JsonNode result : run.path("results")) {
                if (suppressed(result)) {
                    continue;
                }
                Set<String> cwes = cwes(result, rules);
                for (JsonNode loc : result.path("locations")) {
                    String uri = loc.path("physicalLocation").path("artifactLocation").path("uri").asString("");
                    tests(uri).forEach(test -> cwes.forEach(cwe -> out.add(key(test, cwe))));
                }
            }
        }
        return new ReportedSet(out);
    }

    private static boolean suppressed(JsonNode result) {
        for (JsonNode s : result.path("suppressions")) {
            String status = s.path("status").asString("accepted");
            if (status.equals("accepted")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> cwes(JsonNode result, JsonNode rules) {
        Set<String> out = new HashSet<>();
        JsonNode explicit = result.path("properties").path("secpipe").path("cwe");
        if (explicit.isArray() && !explicit.isEmpty()) {
            explicit.forEach(n -> addCwes(out, n.asString("")));
            return out;
        }
        addCwes(out, result.path("properties").toString());
        addCwes(out, result.path("ruleId").asString(""));
        JsonNode rule = result.has("ruleIndex") && result.path("ruleIndex").asInt(-1) >= 0
                ? rules.path(result.path("ruleIndex").asInt()) : null;
        if (rule == null || rule.isMissingNode()) {
            for (JsonNode r : rules) {
                if (r.path("id").asString("").equals(result.path("ruleId").asString(""))) {
                    rule = r;
                    break;
                }
            }
        }
        if (rule != null && !rule.isMissingNode()) {
            addCwes(out, rule.toString());
        }
        return out;
    }

    private static void addCwes(Set<String> out, String text) {
        Matcher m = CWE.matcher(text);
        while (m.find()) {
            out.add("CWE-" + m.group(1));
        }
        if (text.strip().matches("\\d+")) {
            out.add("CWE-" + text.strip());
        }
    }

    private static List<String> tests(String path) {
        List<String> out = new ArrayList<>();
        Matcher m = TEST.matcher(path.replace('\\', '/'));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
