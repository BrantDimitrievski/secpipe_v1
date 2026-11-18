package ca.carleton.secpipe.agents.remediation;

import ca.carleton.secpipe.agents.remediation.RemediationAgent.Input;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.PreviousAttempt;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.RegressionTest;
import ca.carleton.secpipe.agents.remediation.RemediationAgent.Snippet;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic context builder for {@link RemediationAgent} (WO-G.10, PLAN Part 5 A10). For every location the
 * confirmed record cites (trace and evidence) it cuts the <em>enclosing method</em> (method, constructor or
 * initializer, found with JavaParser); when the file does not parse or the line is outside any method it falls back
 * to a window of {@link #FALLBACK_RADIUS} lines. Slices are merged per file. Previous attempts keep only the last
 * {@link #MAX_PREVIOUS} attempts and the tail of each log ({@link #MAX_LOG_LINES} lines, {@link #MAX_LOG_CHARS}
 * characters). Agents never read files themselves.
 */
public final class RemediationContextBuilder {

    public static final int FALLBACK_RADIUS = 15;
    public static final int MAX_FILES = 5;
    public static final int MAX_PREVIOUS = 3;
    public static final int MAX_LOG_LINES = 60;
    public static final int MAX_LOG_CHARS = 4000;

    private final SourceIndex sources;

    public RemediationContextBuilder(SourceIndex sources) {
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** The bounded input for one remediation attempt. */
    public Input build(Finding.Confirmed record, RegressionTest regressionTest, List<PreviousAttempt> previous) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(regressionTest, "regressionTest");
        Objects.requireNonNull(previous, "previous");
        TreeMap<String, List<Integer>> linesByFile = new TreeMap<>();
        Optional<String> sinkFile = Optional.empty();
        for (Finding.TraceStep s : record.trace()) {
            if (add(linesByFile, s.file(), s.line()) && s.kind() == Finding.TraceKind.SINK) {
                sinkFile = Optional.of(s.file());
            }
        }
        for (Finding.Evidence e : record.evidence()) {
            add(linesByFile, e.file(), e.line());
        }
        List<String> files = new ArrayList<>();
        sinkFile.ifPresent(files::add);
        for (String f : linesByFile.keySet()) {
            if (!files.contains(f)) {
                files.add(f);
            }
        }
        List<Snippet> snippets = new ArrayList<>();
        for (String f : files.subList(0, Math.min(MAX_FILES, files.size())).stream().sorted().toList()) {
            snippets.addAll(slices(f, linesByFile.get(f)));
        }
        List<PreviousAttempt> kept = previous.subList(Math.max(0, previous.size() - MAX_PREVIOUS), previous.size())
                .stream().map(p -> new PreviousAttempt(p.attempt(), p.patch(), p.failingStep(), trimLog(p.log())))
                .toList();
        return new Input(record, regressionTest, snippets, kept);
    }

    /** The last {@link #MAX_LOG_LINES} lines of {@code log}, at most {@link #MAX_LOG_CHARS} characters. */
    static String trimLog(String log) {
        String norm = log.replace("\r\n", "\n");
        String[] lines = norm.split("\n", -1);
        int from = Math.max(0, lines.length - MAX_LOG_LINES);
        String tail = String.join("\n", List.of(lines).subList(from, lines.length));
        if (tail.length() > MAX_LOG_CHARS) {
            tail = tail.substring(tail.length() - MAX_LOG_CHARS);
        }
        return (from > 0 || tail.length() < norm.length()) ? "[log trimmed]\n" + tail : tail;
    }

    private boolean add(Map<String, List<Integer>> linesByFile, String file, int line) {
        boolean known;
        try {
            known = sources.exists(file) && sources.line(file, line).isPresent();
        } catch (IllegalArgumentException e) {
            known = false;
        }
        if (known) {
            linesByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(line);
        }
        return known;
    }

    private List<Snippet> slices(String file, List<Integer> lines) {
        List<String> text = sources.lines(file).orElse(List.of());
        Optional<CompilationUnit> cu = file.endsWith(".java") ? parse(String.join("\n", text)) : Optional.empty();
        List<int[]> ranges = new ArrayList<>();
        for (int line : lines.stream().sorted().distinct().toList()) {
            int[] r = cu.flatMap(u -> enclosing(u, line))
                    .orElse(new int[] {Math.max(1, line - FALLBACK_RADIUS),
                        Math.min(text.size(), line + FALLBACK_RADIUS)});
            ranges.add(r);
        }
        ranges.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        List<Snippet> out = new ArrayList<>();
        int from = -1;
        int to = -1;
        for (int[] r : ranges) {
            if (from > 0 && r[0] <= to + 1) {
                to = Math.max(to, r[1]);
            } else {
                if (from > 0) {
                    out.add(slice(file, text, from, to));
                }
                from = r[0];
                to = r[1];
            }
        }
        if (from > 0) {
            out.add(slice(file, text, from, to));
        }
        return out;
    }

    /** The innermost method, constructor or initializer containing {@code line}, with its annotations/Javadoc. */
    private static Optional<int[]> enclosing(CompilationUnit cu, int line) {
        int[] best = null;
        List<Node> nodes = new ArrayList<>();
        nodes.addAll(cu.findAll(CallableDeclaration.class));
        nodes.addAll(cu.findAll(InitializerDeclaration.class));
        for (Node n : nodes) {
            Optional<Range> r = n.getRange();
            if (r.isEmpty()) {
                continue;
            }
            int a = r.get().begin.line;
            int b = r.get().end.line;
            if (a <= line && line <= b && (best == null || b - a < best[1] - best[0])) {
                int start = n.getComment().flatMap(Node::getRange).map(c -> c.begin.line).orElse(a);
                best = new int[] {Math.min(start, a), b};
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<CompilationUnit> parse(String source) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        ParseResult<CompilationUnit> r = new JavaParser(config).parse(source);
        return r.isSuccessful() ? r.getResult() : Optional.empty();
    }

    private static Snippet slice(String file, List<String> text, int from, int to) {
        return new Snippet(file, from, to, String.join("\n", text.subList(from - 1, to)));
    }
}
