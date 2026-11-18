package ca.carleton.secpipe.agents.testgen;

import ca.carleton.secpipe.agents.testgen.TestGenInput.Snippet;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Citation;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.ControlExamined;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic context builder for {@link TestGenAgent}: given a survivor draft, it cuts a window of
 * {@code radius} lines around every location the draft cites (trace, evidence, examined controls), merges
 * overlapping windows per file, and keeps at most {@code maxFiles} files (sink file first, then by path). Agents
 * never read files themselves.
 */
public final class TestGenContextBuilder {

    public static final int DEFAULT_RADIUS = 20;
    public static final int DEFAULT_MAX_FILES = 8;

    private final SourceIndex sources;
    private final Payloads payloads;
    private final int radius;
    private final int maxFiles;

    public TestGenContextBuilder(SourceIndex sources) {
        this(sources, Payloads.bundled(), DEFAULT_RADIUS, DEFAULT_MAX_FILES);
    }

    public TestGenContextBuilder(SourceIndex sources, Payloads payloads, int radius, int maxFiles) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        if (radius < 0 || maxFiles < 1) {
            throw new IllegalArgumentException("radius must be >= 0 and maxFiles >= 1");
        }
        this.radius = radius;
        this.maxFiles = maxFiles;
    }

    /** The bounded input for one survivor of weakness {@code family} ({@code CWE-<n>}). */
    public TestGenInput build(String family, DisproverResult draft, List<String> testDependencies) {
        Objects.requireNonNull(draft, "draft");
        TreeMap<String, List<Integer>> linesByFile = new TreeMap<>();
        Optional<String> sinkFile = Optional.empty();
        for (Step s : draft.trace()) {
            add(linesByFile, s.file(), s.line());
            if (s.kind() == Finding.TraceKind.SINK) {
                sinkFile = Optional.of(s.file());
            }
        }
        for (Citation c : draft.evidence()) {
            add(linesByFile, c.file(), c.line());
        }
        for (ControlExamined c : draft.controlsExamined()) {
            add(linesByFile, c.file(), c.line());
        }
        List<String> files = new ArrayList<>();
        sinkFile.filter(linesByFile::containsKey).ifPresent(files::add);
        for (String f : linesByFile.keySet()) {
            if (!files.contains(f)) {
                files.add(f);
            }
        }
        List<String> kept = files.subList(0, Math.min(maxFiles, files.size())).stream().sorted().toList();
        List<Snippet> snippets = new ArrayList<>();
        for (String f : kept) {
            snippets.addAll(windows(f, linesByFile.get(f)));
        }
        return new TestGenInput(family, draft, payloads.forFamily(family), testDependencies, snippets);
    }

    private void add(Map<String, List<Integer>> linesByFile, String file, int line) {
        boolean known;
        try {
            known = sources.exists(file);
        } catch (IllegalArgumentException e) {
            known = false;
        }
        if (known && sources.line(file, line).isPresent()) {
            linesByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(line);
        }
    }

    private List<Snippet> windows(String file, List<Integer> lines) {
        List<String> text = sources.lines(file).orElse(List.of());
        List<Snippet> out = new ArrayList<>();
        int from = -1;
        int to = -1;
        for (int line : lines.stream().sorted().distinct().toList()) {
            int a = Math.max(1, line - radius);
            int b = Math.min(text.size(), line + radius);
            if (from > 0 && a <= to + 1) {
                to = Math.max(to, b);
            } else {
                if (from > 0) {
                    out.add(slice(file, text, from, to));
                }
                from = a;
                to = b;
            }
        }
        if (from > 0) {
            out.add(slice(file, text, from, to));
        }
        return out;
    }

    private static Snippet slice(String file, List<String> text, int from, int to) {
        return new Snippet(file, from, to, String.join("\n", text.subList(from - 1, to)));
    }
}
