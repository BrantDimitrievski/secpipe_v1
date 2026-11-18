package ca.carleton.secpipe.eval.benchmark;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scores a scan against the OWASP Benchmark v1.2 answer key for CWEs 22, 78, 89, 327, 328.
 *
 * <p>Usage: {@code BenchmarkScorecard --expected CSV [--findings FILE] [--sarif FILE] --out DIR}. Writes
 * {@code scorecard.json}, {@code scorecard.md} and {@code per-case.csv}. The average is the unweighted mean of the
 * per-CWE rates (the Benchmark's own convention); the score is TPR minus FPR.
 */
public final class BenchmarkScorecard {

    /** The CWEs scored, in report order. */
    public static final List<String> CWES = List.of("22", "78", "89", "327", "328");

    /** Oct-2026 MVP baseline (docs/eval/baseline-2026-10.json), in percent. */
    public static final Rates BASELINE = new Rates(82.4, 46.0, 23.8);

    public record Rates(double tpr, double fpr, double score) { }

    /** One scored answer-key row. */
    public record Case(String test, String category, String cwe, boolean vulnerable, boolean reported, String result) { }

    public record Result(Map<String, CweCounts> perCwe, Rates average, Rates baselineDelta, List<Case> cases) { }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BenchmarkScorecard() {
    }

    public static Result score(List<ExpectedResults.Row> expected, ReportedSet reported) {
        Map<String, int[]> counts = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
        CWES.forEach(c -> counts.put(c, new int[4]));
        List<Case> cases = new ArrayList<>();
        for (ExpectedResults.Row row : expected) {
            int[] c = counts.get(row.cwe());
            if (c == null) {
                continue;
            }
            boolean hit = reported.contains(row.test(), "CWE-" + row.cwe());
            String result = row.vulnerable() ? (hit ? "TP" : "FN") : (hit ? "FP" : "TN");
            c[switch (result) { case "TP" -> 0; case "FP" -> 1; case "TN" -> 2; default -> 3; }]++;
            cases.add(new Case(row.test(), row.category(), row.cwe(), row.vulnerable(), hit, result));
        }
        cases.sort(Comparator.comparing(Case::test).thenComparing(Case::cwe));
        Map<String, CweCounts> perCwe = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
        counts.forEach((cwe, c) -> perCwe.put(cwe, new CweCounts(c[0], c[1], c[2], c[3])));
        double tpr = mean(perCwe, true);
        double fpr = mean(perCwe, false);
        Rates average = new Rates(tpr, fpr, CweCounts.round1(tpr - fpr));
        Rates delta = new Rates(CweCounts.round1(average.tpr() - BASELINE.tpr()),
                CweCounts.round1(average.fpr() - BASELINE.fpr()),
                CweCounts.round1(average.score() - BASELINE.score()));
        return new Result(perCwe, average, delta, List.copyOf(cases));
    }

    private static double mean(Map<String, CweCounts> perCwe, boolean tpr) {
        double sum = 0;
        for (CweCounts c : perCwe.values()) {
            sum += tpr ? c.tpr() : c.fpr();
        }
        return CweCounts.round1(sum / perCwe.size());
    }

    public static String toJson(Result r) {
        Map<String, Object> perCwe = new TreeMap<>();
        r.perCwe().forEach((cwe, c) -> perCwe.put(cwe, counts(c)));
        int tp = 0;
        int fp = 0;
        int tn = 0;
        int fn = 0;
        for (CweCounts c : r.perCwe().values()) {
            tp += c.tp();
            fp += c.fp();
            tn += c.tn();
            fn += c.fn();
        }
        Map<String, Object> average = new TreeMap<>(rates(r.average()));
        average.put("tp", tp);
        average.put("fp", fp);
        average.put("tn", tn);
        average.put("fn", fn);
        Map<String, Object> root = new TreeMap<>();
        root.put("per_cwe", perCwe);
        root.put("average", average);
        root.put("baseline_delta", new TreeMap<>(rates(r.baselineDelta())));
        return JSON.writer().with(ContractJson.prettyPrinter()).writeValueAsString(root) + "\n";
    }

    private static Map<String, Object> counts(CweCounts c) {
        Map<String, Object> m = new TreeMap<>();
        m.put("tp", c.tp());
        m.put("fp", c.fp());
        m.put("tn", c.tn());
        m.put("fn", c.fn());
        m.put("tpr", c.tpr());
        m.put("fpr", c.fpr());
        m.put("score", c.score());
        return m;
    }

    private static Map<String, Object> rates(Rates r) {
        Map<String, Object> m = new TreeMap<>();
        m.put("tpr", r.tpr());
        m.put("fpr", r.fpr());
        m.put("score", r.score());
        return m;
    }

    public static String toMarkdown(Result r) {
        StringBuilder sb = new StringBuilder("# OWASP Benchmark v1.2 scorecard\n\n");
        sb.append("| CWE | TP | FP | TN | FN | TPR % | FPR % | Score |\n|---|---|---|---|---|---|---|---|\n");
        int[] t = new int[4];
        r.perCwe().forEach((cwe, c) -> {
            sb.append("| CWE-%s | %d | %d | %d | %d | %.1f | %.1f | %.1f |\n"
                    .formatted(cwe, c.tp(), c.fp(), c.tn(), c.fn(), c.tpr(), c.fpr(), c.score()));
            t[0] += c.tp();
            t[1] += c.fp();
            t[2] += c.tn();
            t[3] += c.fn();
        });
        Rates a = r.average();
        sb.append("| **Average** | %d | %d | %d | %d | %.1f | %.1f | %.1f |\n"
                .formatted(t[0], t[1], t[2], t[3], a.tpr(), a.fpr(), a.score()));
        sb.append("\nBaseline (Oct 2026): TPR %.1f, FPR %.1f, score %.1f. Delta: TPR %+.1f, FPR %+.1f, score %+.1f.\n"
                .formatted(BASELINE.tpr(), BASELINE.fpr(), BASELINE.score(),
                        r.baselineDelta().tpr(), r.baselineDelta().fpr(), r.baselineDelta().score()));
        sb.append("\nAverage rates are the unweighted mean of the per-CWE rates; score = TPR - FPR.\n");
        return sb.toString();
    }

    public static String toCsv(Result r) {
        StringBuilder sb = new StringBuilder("test,category,cwe,vulnerable,reported,result\n");
        for (Case c : r.cases()) {
            sb.append("%s,%s,%s,%s,%s,%s\n".formatted(c.test(), c.category(), c.cwe(), c.vulnerable(), c.reported(),
                    c.result()));
        }
        return sb.toString();
    }

    /** CLI entry point; exit 0 ok, 2 usage or input error (one-line problem plus a {@code hint:} line). */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path expected = null;
        Path findings = null;
        Path sarif = null;
        Path out = null;
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (i + 1 >= args.length) {
                    return usage("option " + a + " needs a value");
                }
                Path v = Path.of(args[++i]);
                switch (a) {
                    case "--expected" -> expected = v;
                    case "--findings" -> findings = v;
                    case "--sarif" -> sarif = v;
                    case "--out" -> out = v;
                    default -> {
                        return usage("unknown option " + a);
                    }
                }
            }
            if (expected == null || out == null || (findings == null && sarif == null)) {
                return usage("--expected, --out and at least one of --findings/--sarif are required");
            }
            ReportedSet reported = ReportedSet.empty();
            if (findings != null) {
                List<Finding> list = ContractJson.readFindings(findings);
                reported = reported.union(ReportedSet.fromFindings(list));
            }
            if (sarif != null) {
                reported = reported.union(ReportedSet.fromSarif(sarif));
            }
            Result result = score(ExpectedResults.read(expected), reported);
            Files.createDirectories(out);
            write(out.resolve("scorecard.json"), toJson(result));
            write(out.resolve("scorecard.md"), toMarkdown(result));
            write(out.resolve("per-case.csv"), toCsv(result));
            return 0;
        } catch (RuntimeException | IOException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println("hint: check the input files exist and were produced by secpipe scan");
            return 2;
        }
    }

    private static int usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("hint: BenchmarkScorecard --expected CSV [--findings FILE] [--sarif FILE] --out DIR");
        return 2;
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
