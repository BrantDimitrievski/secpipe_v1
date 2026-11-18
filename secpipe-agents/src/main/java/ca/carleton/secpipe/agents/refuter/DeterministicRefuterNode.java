package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Graph node {@code refute} (WO-G.5). Runs after normalization and before the LLM disprover; for each fingerprinted
 * code candidate of CWE-22/78/89 without a record it asks {@link Refuter}. Refuted candidates get a {@code rejected}
 * record with {@code verification.disprover = {kind: deterministic, rule}} and the control location as evidence;
 * survivors are left untouched for the disprover. Needs no LLM and no sandbox; never confirms.
 */
public final class DeterministicRefuterNode implements PipelineNode {

    public static final String ID = "refute";

    private final Function<Path, SourceIndex> indexFactory;
    private final Refuter refuter = new Refuter();

    public DeterministicRefuterNode() {
        this(DiskSourceIndex::new);
    }

    DeterministicRefuterNode(Function<Path, SourceIndex> indexFactory) {
        this.indexFactory = indexFactory;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean requiresLlm() {
        return false;
    }

    @Override
    public boolean usesSandbox() {
        return false;
    }

    @Override
    public Result apply(PipelineState state, RunContext ctx) {
        Optional<Path> root = state.profile().map(ProjectProfile::root)
                .or(() -> Optional.ofNullable(state.config()).map(c -> c.target()));
        if (root.isEmpty()) {
            return Result.next(StateDelta.empty());
        }
        SourceIndex index = indexFactory.apply(root.get());
        Map<String, Optional<CompilationUnit>> units = new HashMap<>();
        List<Finding> rejected = new ArrayList<>();
        List<Candidate> sorted = state.candidates().stream()
                .filter(c -> c.fingerprint().isPresent())
                .sorted(Comparator.comparing(c -> c.fingerprint().orElseThrow()))
                .toList();
        for (Candidate c : sorted) {
            String fp = c.fingerprint().orElseThrow();
            if (state.records().containsKey(fp) || rejected.stream().anyMatch(r -> r.fingerprint().equals(fp))) {
                continue;
            }
            Optional<Finding.TraceStep> sink = Refuter.sinkStep(c);
            if (sink.isEmpty() || Refuter.family(c).isEmpty() || c.category() != Finding.Category.CODE) {
                continue;
            }
            Optional<CompilationUnit> unit = units.computeIfAbsent(sink.get().file(), f -> parse(index, f));
            unit.flatMap(u -> refuter.check(c, u)).ifPresent(r -> rejected.add(toRecord(c, r)));
        }
        ctx.log().info("refute: rejected " + rejected.size() + " of " + sorted.size() + " candidates");
        StateDelta e = StateDelta.empty();
        StateDelta delta = new StateDelta(e.environment(), e.profile(), e.diffScope(), e.newLedgerUnits(),
                e.ledgerUpdates(), e.replaceCandidates(), e.addCandidates(), List.copyOf(rejected),
                e.putSurvivorDrafts(), e.toolRuns(), e.timingsMs(), e.hunterWave(), e.criticPasses(),
                e.criticSaysStop(), e.fixAttemptsByFingerprint(), e.prior(), e.notes(), e.incompleteReason());
        return Result.next(delta);
    }

    /** The rejected record for a refuted candidate. */
    static Finding.Rejected toRecord(Candidate c, Refutation r) {
        Finding.VerifierRef verifier = new Finding.VerifierRef(Finding.VerifierRef.Kind.DETERMINISTIC,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(r.rule()));
        Finding.Verification verification = new Finding.Verification(Optional.of(verifier), Optional.empty(),
                Optional.empty(), false);
        Finding.SecPipeExt ext = new Finding.SecPipeExt(c.category(), c.origin(), c.toolSources(), c.cwe(), c.cve(),
                Optional.empty(), Optional.empty(), Optional.empty(), c.confidenceHint(), List.of(), List.of(),
                Optional.empty(), Optional.empty(), Optional.of(verification), c.ledgerUnits(), Optional.empty(),
                Optional.empty());
        Finding.Evidence evidence = new Finding.Evidence(r.controlLocation().file(), r.controlLocation().line(),
                "control (" + r.rule() + "): " + r.reason());
        return new Finding.Rejected(c.fingerprint().orElseThrow(), text(c.title(), "Refuted candidate"),
                text(c.message(), c.title()), text(c.claimedRootCause(), c.title()), c.trace(), List.of(evidence),
                r.reason(), ext);
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null || fallback.isBlank() ? "n/a" : fallback) : value;
    }

    private static Optional<CompilationUnit> parse(SourceIndex index, String file) {
        try {
            Optional<List<String>> lines = index.lines(file);
            if (lines.isEmpty()) {
                return Optional.empty();
            }
            JavaParser parser = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            ParseResult<CompilationUnit> result = parser.parse(String.join("\n", lines.get()));
            return result.isSuccessful() ? result.getResult() : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Minimal read-only {@link SourceIndex} over the scanned root; paths may not escape it. */
    private static final class DiskSourceIndex implements SourceIndex {
        private final Path root;

        DiskSourceIndex(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Override
        public List<String> files() {
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            try (Stream<Path> s = Files.walk(root)) {
                return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                        .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean exists(String path) {
            return resolve(path).isPresent();
        }

        @Override
        public Optional<List<String>> lines(String path) {
            Optional<Path> p = resolve(path);
            if (p.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(List.copyOf(Files.readAllLines(p.get(), StandardCharsets.UTF_8)));
            } catch (IOException | UncheckedIOException e) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<String> line(String path, int oneBasedLine) {
            return lines(path).filter(l -> oneBasedLine >= 1 && oneBasedLine <= l.size())
                    .map(l -> l.get(oneBasedLine - 1));
        }

        private Optional<Path> resolve(String path) {
            Path p = root.resolve(SourceIndex.requireRelativePath(path)).normalize();
            try {
                if (!Files.isRegularFile(p) || !p.toRealPath().startsWith(root.toRealPath())) {
                    return Optional.empty();
                }
            } catch (IOException e) {
                return Optional.empty();
            }
            return Optional.of(p);
        }
    }
}
