package ca.carleton.secpipe.agents.disprover;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds the bounded {@link DisproverInput} for one normalized candidate (PLAN Part 5 §A7). Deterministic.
 *
 * <p>Slices, in priority order: the enclosing method of the sink step (the last trace step), then of each earlier
 * step from the sink back to the entry point (the caller chain), each widened to at least ±{@link #RADIUS} lines
 * around the cited line; then ±{@link #RADIUS} lines around each evidence line. A method longer than
 * {@link #MAX_METHOD_LINES} is cut to a window around the cited line. Ranges are accepted in that order until
 * {@link #MAX_CONTEXT_CHARS} characters are used (the last one is cut to fit), then merged per file and sorted by
 * path and line. Files missing from the index and out-of-range lines are skipped. Tool names, confidence and other
 * verifiers' output are never copied into the input.
 */
public final class DisproverContextBuilder {

    /** Lines shown before and after every cited line. */
    public static final int RADIUS = 15;
    /** Longest enclosing method shown whole. */
    public static final int MAX_METHOD_LINES = 120;
    /** Cap on the total characters of all slices. */
    public static final int MAX_CONTEXT_CHARS = 24_000;

    private final SourceIndex sources;
    private final ReconFacts facts;
    private final boolean haveFacts;
    private final JavaParser parser;
    private final Map<String, List<int[]>> methodCache = new HashMap<>();

    /** A builder with recon facts; pass {@link Optional#empty()} when recon wrote none. */
    public DisproverContextBuilder(SourceIndex sources, Optional<ReconFacts> facts) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.haveFacts = facts.isPresent();
        this.facts = facts.orElse(ReconFacts.empty());
        this.parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    /**
     * The input for {@code c}.
     *
     * @throws IllegalArgumentException if {@code c} has no fingerprint or no trace
     */
    public DisproverInput build(Candidate c) {
        String fp = c.fingerprint().orElseThrow(() -> new IllegalArgumentException("candidate has no fingerprint"));
        if (c.trace().isEmpty()) {
            throw new IllegalArgumentException("candidate " + fp + " has no trace");
        }
        List<Finding.TraceStep> trace = c.trace();
        List<int[]> wanted = new ArrayList<>();
        List<String> wantedFiles = new ArrayList<>();
        for (int i = trace.size() - 1; i >= 0; i--) {
            Finding.TraceStep s = trace.get(i);
            addRange(wanted, wantedFiles, s.file(), stepRange(s.file(), s.line()));
        }
        for (Finding.Evidence e : c.evidence()) {
            addRange(wanted, wantedFiles, e.file(), window(e.file(), e.line()));
        }

        TreeMap<String, List<int[]>> accepted = new TreeMap<>();
        int used = 0;
        boolean truncated = false;
        for (int i = 0; i < wanted.size() && !truncated; i++) {
            String file = wantedFiles.get(i);
            int[] r = wanted.get(i);
            List<String> lines = sources.lines(file).orElseThrow();
            int to = r[0] - 1;
            for (int ln = r[0]; ln <= r[1]; ln++) {
                int cost = lines.get(ln - 1).length() + 1;
                if (used + cost > MAX_CONTEXT_CHARS) {
                    truncated = true;
                    break;
                }
                used += cost;
                to = ln;
            }
            if (to >= r[0]) {
                accepted.computeIfAbsent(file, k -> new ArrayList<>()).add(new int[] {r[0], to});
            }
        }

        List<SourceSlice> slices = new ArrayList<>();
        accepted.forEach((file, ranges) -> {
            List<String> lines = sources.lines(file).orElseThrow();
            for (int[] r : merge(ranges)) {
                slices.add(new SourceSlice(file, r[0], r[1], String.join("\n", lines.subList(r[0] - 1, r[1]))));
            }
        });

        String title = nonBlank(c.title(), "candidate " + fp);
        String rootCause = nonBlank(c.claimedRootCause(), nonBlank(c.message(), title));
        return new DisproverInput(fp, title, c.cwe(), rootCause, trace, c.evidence(), slices,
                controlCatalogue(c.cwe()), entryFact(trace.get(0)), sinkFact(trace.get(trace.size() - 1)), truncated);
    }

    /** The trusted control catalogue for the first CWE that has one, else the generic catalogue. */
    public static String controlCatalogue(List<String> cwe) {
        for (String id : cwe) {
            if (id == null || !id.matches("CWE-[1-9][0-9]*")) {
                continue;
            }
            Optional<String> text = resource("disprover/controls/" + id + ".txt");
            if (text.isPresent()) {
                return text.get();
            }
        }
        return resource("disprover/controls/default.txt")
                .orElseThrow(() -> new IllegalStateException("missing disprover/controls/default.txt"));
    }

    private Optional<Boolean> entryFact(Finding.TraceStep first) {
        if (!haveFacts) {
            return Optional.empty();
        }
        return Optional.of(facts.entrySurfaces().stream().anyMatch(l -> matches(l, first)));
    }

    private Optional<Boolean> sinkFact(Finding.TraceStep last) {
        if (!haveFacts) {
            return Optional.empty();
        }
        return Optional.of(facts.sinksByFamily().values().stream().flatMap(List::stream)
                .anyMatch(l -> l.file().equals(last.file()) && l.line() == last.line()));
    }

    private static boolean matches(Location l, Finding.TraceStep s) {
        return l.file().equals(s.file())
                && (l.line() == s.line() || l.scope().filter(sc -> sc.equals(s.scope())).isPresent());
    }

    private void addRange(List<int[]> ranges, List<String> files, String file, Optional<int[]> r) {
        r.ifPresent(range -> {
            ranges.add(range);
            files.add(file);
        });
    }

    private Optional<int[]> window(String file, int line) {
        Optional<List<String>> lines = lines(file);
        if (lines.isEmpty() || line < 1 || line > lines.get().size()) {
            return Optional.empty();
        }
        int n = lines.get().size();
        return Optional.of(new int[] {Math.max(1, line - RADIUS), Math.min(n, line + RADIUS)});
    }

    private Optional<int[]> stepRange(String file, int line) {
        Optional<int[]> w = window(file, line);
        if (w.isEmpty()) {
            return w;
        }
        int from = w.get()[0];
        int to = w.get()[1];
        for (int[] m : methods(file)) {
            if (m[0] <= line && line <= m[1]) {
                int mFrom = m[0];
                int mTo = m[1];
                if (mTo - mFrom + 1 > MAX_METHOD_LINES) {
                    mFrom = Math.max(mFrom, line - MAX_METHOD_LINES / 2);
                    mTo = Math.min(mTo, mFrom + MAX_METHOD_LINES - 1);
                }
                from = Math.min(from, mFrom);
                to = Math.max(to, mTo);
                break;
            }
        }
        return Optional.of(new int[] {from, to});
    }

    /** Line ranges of methods and constructors in {@code file}, innermost first; empty if it does not parse. */
    private List<int[]> methods(String file) {
        return methodCache.computeIfAbsent(file, f -> {
            if (!f.endsWith(".java")) {
                return List.of();
            }
            List<String> lines = lines(f).orElse(List.of());
            ParseResult<CompilationUnit> parsed = parser.parse(String.join("\n", lines));
            if (parsed.getResult().isEmpty()) {
                return List.of();
            }
            List<int[]> out = new ArrayList<>();
            for (CallableDeclaration<?> d : parsed.getResult().get().findAll(CallableDeclaration.class)) {
                Optional<Range> r = d.getRange();
                r.ifPresent(range -> out.add(new int[] {range.begin.line, range.end.line}));
            }
            out.sort(Comparator.comparingInt((int[] a) -> a[1] - a[0]));
            return List.copyOf(out);
        });
    }

    private Optional<List<String>> lines(String file) {
        try {
            return sources.lines(file);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<int[]> merge(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt((int[] a) -> a[0]).thenComparingInt(a -> a[1]));
        List<int[]> out = new ArrayList<>();
        for (int[] r : sorted) {
            if (!out.isEmpty() && r[0] <= out.get(out.size() - 1)[1] + 1) {
                int[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], r[1]);
            } else {
                out.add(new int[] {r[0], r[1]});
            }
        }
        return out;
    }

    private static String nonBlank(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static Optional<String> resource(String name) {
        try (InputStream in = DisproverContextBuilder.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + name, e);
        }
    }
}
