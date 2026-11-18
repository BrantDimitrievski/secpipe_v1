package ca.carleton.secpipe.core.normalize;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import ca.carleton.secpipe.core.knowledge.KnowledgeBase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic normalization (feature G): assigns every candidate its {@link Fingerprints} value and merges
 * candidates that share one. Output is sorted by fingerprint and independent of input order. No LLM calls.
 */
public final class CandidateMerger {

    /** Normalized candidates plus run notes for candidates that could not be fingerprinted (they are dropped). */
    public record Outcome(List<Candidate> candidates, List<String> notes) {
        public Outcome {
            candidates = List.copyOf(candidates);
            notes = List.copyOf(notes);
        }
    }

    /** Total, content-based order: longest trace first, tool origin first, then the full record text. */
    private static final Comparator<Candidate> REPRESENTATIVE = Comparator
            .comparingInt((Candidate c) -> -c.trace().size())
            .thenComparing(Candidate::origin)
            .thenComparing(Candidate::toString);

    private static final Comparator<Finding.ToolSource> TOOL_SOURCE_ORDER = Comparator
            .comparing(Finding.ToolSource::tool)
            .thenComparing(Finding.ToolSource::ruleId)
            .thenComparing(Finding.ToolSource::toString);

    private static final Comparator<Finding.Evidence> EVIDENCE_ORDER = Comparator
            .comparing(Finding.Evidence::file)
            .thenComparingInt(Finding.Evidence::line)
            .thenComparing(Finding.Evidence::description);

    private final KnowledgeBase knowledge;

    public CandidateMerger() {
        this(KnowledgeBase.load());
    }

    public CandidateMerger(KnowledgeBase knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
    }

    /** Fingerprints and merges; candidates that cannot be fingerprinted are dropped (see {@link #normalize}). */
    public List<Candidate> merge(List<Candidate> candidates) {
        return normalize(candidates).candidates();
    }

    public Outcome normalize(List<Candidate> input) {
        Objects.requireNonNull(input, "candidates");
        List<Candidate> ordered = new ArrayList<>(input);
        ordered.sort(Comparator.comparing(Candidate::toString));
        List<String> notes = new ArrayList<>();

        // Occurrence index: distinct sink lines per (family, file, scope, sink), ascending.
        Map<Candidate, CodeKey> keys = new HashMap<>();
        Map<CodeKey.Site, TreeSet<Integer>> linesBySite = new HashMap<>();
        for (Candidate c : ordered) {
            if (c.fingerprint().isPresent() || c.category() != Finding.Category.CODE) {
                continue;
            }
            Optional<CodeKey> key = codeKey(c);
            if (key.isEmpty()) {
                notes.add("normalize: dropped code candidate without CWE or sink file: " + describe(c));
                continue;
            }
            keys.put(c, key.get());
            linesBySite.computeIfAbsent(key.get().site(), k -> new TreeSet<>()).add(key.get().line());
        }

        Map<String, List<Candidate>> byFingerprint = new TreeMap<>();
        for (Candidate c : ordered) {
            Optional<String> fp;
            String primaryCwe = null;
            if (c.fingerprint().isPresent()) {
                fp = c.fingerprint();
            } else if (c.category() == Finding.Category.CODE) {
                CodeKey key = keys.get(c);
                if (key == null) {
                    continue;
                }
                int occurrence = linesBySite.get(key.site()).headSet(key.line()).size();
                CodeKey.Site s = key.site();
                fp = Optional.of(Fingerprints.code(s.family(), s.file(), s.scope(), s.sink(), occurrence));
                primaryCwe = key.cwe();
            } else {
                fp = dependencyFingerprint(c);
                if (fp.isEmpty()) {
                    notes.add("normalize: dropped dependency candidate without purl or vulnerability id: "
                            + describe(c));
                    continue;
                }
            }
            byFingerprint.computeIfAbsent(fp.get(), k -> new ArrayList<>())
                    .add(withFingerprint(c, fp.get(), primaryCwe));
        }

        List<Candidate> out = new ArrayList<>(byFingerprint.size());
        byFingerprint.forEach((fp, group) -> out.add(mergeGroup(group)));
        return new Outcome(out, notes);
    }

    private record CodeKey(Site site, int line, String cwe) {
        record Site(String family, String file, String scope, String sink) { }
    }

    private Optional<CodeKey> codeKey(Candidate c) {
        Optional<String> cwe = c.cwe().stream().map(KnowledgeBase::normalizeCwe)
                .flatMap(Optional::stream).findFirst();
        if (cwe.isEmpty()) {
            return Optional.empty();
        }
        Optional<Finding.TraceStep> sink = c.trace().isEmpty()
                ? Optional.empty() : Optional.of(c.trace().get(c.trace().size() - 1));
        Optional<Finding.ToolSource> located = c.toolSources().stream().filter(t -> t.file().isPresent())
                .sorted(TOOL_SOURCE_ORDER).findFirst();
        Optional<String> file = sink.map(Finding.TraceStep::file).filter(f -> !f.isBlank())
                .or(() -> located.flatMap(Finding.ToolSource::file));
        if (file.isEmpty()) {
            return Optional.empty();
        }
        int line = sink.filter(s -> !s.file().isBlank()).map(Finding.TraceStep::line)
                .or(() -> located.flatMap(Finding.ToolSource::line)).orElse(0);
        String scope = Scopes.canonical(c.sinkScope()
                .or(() -> sink.map(Finding.TraceStep::scope)).orElse(""));
        String sinkText = Fingerprints.normalizeStatement(c.sinkStatement().orElse(""));
        // Bare canonical family id ("CWE-22"); Fingerprints.cweFamily does not parse "CWE-22: text".
        String family = knowledge.family(cwe.get());
        return Optional.of(new CodeKey(new CodeKey.Site(family, file.get(), scope, sinkText), line, cwe.get()));
    }

    private static Optional<String> dependencyFingerprint(Candidate c) {
        if (c.dependency().isEmpty() || c.dependency().get().purl().isBlank()) {
            return Optional.empty();
        }
        List<String> ids = new ArrayList<>(new TreeSet<>(c.cve()));
        c.toolSources().stream().map(Finding.ToolSource::ruleId).filter(id -> !id.isBlank())
                .sorted().distinct().forEach(ids::add);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Fingerprints.dependency(c.dependency().get().purl(), ids));
    }

    /** Keeps the representative's fields; unions tools, rule ids, CWEs, CVEs, evidence and ledger units. */
    private static Candidate mergeGroup(List<Candidate> group) {
        List<Candidate> sorted = new ArrayList<>(group);
        sorted.sort(REPRESENTATIVE);
        Candidate rep = sorted.get(0);
        if (sorted.size() == 1) {
            return rep;
        }
        List<Finding.ToolSource> tools = sorted.stream().flatMap(c -> c.toolSources().stream()).distinct()
                .sorted(TOOL_SOURCE_ORDER).toList();
        Set<String> cwe = new LinkedHashSet<>();
        if (!rep.cwe().isEmpty()) {
            cwe.add(rep.cwe().get(0));
        }
        sorted.stream().flatMap(c -> c.cwe().stream()).sorted().forEach(cwe::add);
        List<String> cve = sorted.stream().flatMap(c -> c.cve().stream()).distinct().sorted().toList();
        List<Finding.Evidence> evidence = sorted.stream().flatMap(c -> c.evidence().stream()).distinct()
                .sorted(EVIDENCE_ORDER).toList();
        List<String> units = sorted.stream().flatMap(c -> c.ledgerUnits().stream()).distinct().sorted().toList();
        Optional<Finding.Score5> severity = sorted.stream().map(Candidate::toolSeverityHint)
                .flatMap(Optional::stream).max(Comparator.naturalOrder());
        return new Candidate(rep.fingerprint(), rep.category(), rep.origin(), tools, List.copyOf(cwe), cve,
                rep.title(), rep.message(), rep.claimedRootCause(), rep.trace(), evidence, rep.sinkStatement(),
                rep.sinkScope(), rep.dependency(), severity, rep.confidenceHint(), rep.strongestControl(),
                rep.smallestFix(), rep.regressionCase(), units);
    }

    /** Sets the fingerprint and, for code candidates, moves the CWE that drove it to the front (canonical form). */
    private static Candidate withFingerprint(Candidate c, String fingerprint, String primaryCwe) {
        List<String> cwe = c.cwe();
        if (primaryCwe != null) {
            Set<String> ordered = new LinkedHashSet<>();
            ordered.add(primaryCwe);
            c.cwe().stream().map(raw -> KnowledgeBase.normalizeCwe(raw).orElse(raw)).forEach(ordered::add);
            cwe = List.copyOf(ordered);
        }
        return new Candidate(Optional.of(fingerprint), c.category(), c.origin(), c.toolSources(), cwe, c.cve(),
                c.title(), c.message(), c.claimedRootCause(), c.trace(), c.evidence(), c.sinkStatement(),
                c.sinkScope(), c.dependency(), c.toolSeverityHint(), c.confidenceHint(), c.strongestControl(),
                c.smallestFix(), c.regressionCase(), c.ledgerUnits());
    }

    private static String describe(Candidate c) {
        String tools = c.toolSources().stream().map(Finding.ToolSource::tool).distinct().sorted()
                .reduce((a, b) -> a + "," + b).orElse(c.origin().name().toLowerCase(Locale.ROOT));
        return "'" + c.title() + "' (" + tools + ")";
    }
}
