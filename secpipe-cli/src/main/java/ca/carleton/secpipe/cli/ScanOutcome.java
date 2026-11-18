package ca.carleton.secpipe.cli;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.run.RunMetadata;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The result of one scan as the CLI reports it: the console summary and the frozen exit code (PLAN §4.3).
 *
 * @param incompleteReason empty when the run is {@code complete}
 */
record ScanOutcome(SecPipeConfig config, List<Finding> findings, boolean incomplete,
                   Optional<String> incompleteReason) {

    /** How many findings the console summary lists. */
    static final int TOP = 5;

    ScanOutcome {
        findings = findings.stream().sorted(Comparator.comparing(Finding::fingerprint)).toList();
    }

    /** Reads the terminal state from the frozen {@code run-metadata.json}; a missing file means incomplete. */
    static ScanOutcome of(SecPipeConfig config, PipelineState end) {
        List<Finding> findings = List.copyOf(end.records().values());
        Path metadata = config.outDir().resolve("run-metadata.json");
        if (Files.isRegularFile(metadata)) {
            try {
                RunMetadata meta = RunMetadataCodec.read(metadata);
                boolean incomplete = meta.terminalState() == RunMetadata.TerminalState.INCOMPLETE;
                return new ScanOutcome(config, findings, incomplete,
                        incomplete ? end.incompleteReason().or(() -> Optional.of("see run-metadata.json"))
                                : Optional.empty());
            } catch (RuntimeException e) {
                return new ScanOutcome(config, findings, true,
                        Optional.of("run-metadata.json could not be read: " + e.getMessage()));
            }
        }
        return new ScanOutcome(config, findings, true,
                Optional.of(end.incompleteReason().orElse("run-metadata.json was not written")));
    }

    long count(Finding.Verdict verdict) {
        return findings.stream().filter(f -> f.verdict() == verdict).count();
    }

    /**
     * 3 when the run is incomplete and {@code --fail-on-incomplete} is set (an incomplete run's verdicts cannot be
     * trusted, so this wins); else 1 for a confirmed finding at or above {@code --fail-on}, or any
     * needs_validation finding with {@code --fail-on-needs-validation}; else 0.
     */
    int exitCode() {
        if (incomplete && config.failOnIncomplete()) {
            return SecPipeCli.EXIT_INCOMPLETE;
        }
        Optional<Finding.Score5> threshold = config.failOnSeverity();
        boolean severe = threshold.isPresent() && findings.stream()
                .filter(f -> f instanceof Finding.Confirmed)
                .map(f -> ((Finding.Confirmed) f).severity())
                .anyMatch(s -> s != null && s.overallSeverity().compareTo(threshold.get()) >= 0);
        boolean unverified = config.failOnNeedsValidation() && count(Finding.Verdict.NEEDS_VALIDATION) > 0;
        return severe || unverified ? SecPipeCli.EXIT_FINDINGS : SecPipeCli.EXIT_OK;
    }

    void print(PrintStream out) {
        out.println("secpipe: scan of " + config.target() + " "
                + (incomplete ? "incomplete (" + incompleteReason.orElse("unknown reason") + ")" : "complete"));
        out.println("findings: " + count(Finding.Verdict.CONFIRMED) + " confirmed, "
                + count(Finding.Verdict.NEEDS_VALIDATION) + " needs_validation, "
                + count(Finding.Verdict.REJECTED) + " rejected");
        printFile(out, "findings.json: ", "findings.json");
        printFile(out, "report.md:     ", "report.md");
        printFile(out, "secpipe.sarif: ", "secpipe.sarif");
        List<Finding> shown = findings.stream().filter(f -> f.verdict() != Finding.Verdict.REJECTED)
                .sorted(Comparator.comparing(Finding::verdict).thenComparing(Finding::fingerprint)).toList();
        if (!shown.isEmpty()) {
            out.println("top findings:");
        }
        for (int i = 0; i < Math.min(TOP, shown.size()); i++) {
            Finding f = shown.get(i);
            String cwe = f.secpipe().cwe().isEmpty() ? "" : String.join("/", f.secpipe().cwe()) + " ";
            out.println("  " + (i + 1) + ". " + f.verdict().name().toLowerCase(Locale.ROOT) + " " + cwe + f.title()
                    + location(f).map(l -> " at " + l).orElse(""));
        }
        if (shown.size() > TOP) {
            out.println("  ... and " + (shown.size() - TOP) + " more (see report.md)");
        }
    }

    private void printFile(PrintStream out, String label, String name) {
        Path p = config.outDir().resolve(name);
        if (Files.isRegularFile(p)) {
            out.println(label + p);
        }
    }

    /** The sink step of the trace, else the first evidence item. */
    static Optional<String> location(Finding f) {
        if (!f.trace().isEmpty()) {
            Finding.TraceStep last = f.trace().get(f.trace().size() - 1);
            return Optional.of(last.file() + ":" + last.line());
        }
        if (!f.evidence().isEmpty()) {
            return Optional.of(f.evidence().get(0).file() + ":" + f.evidence().get(0).line());
        }
        return Optional.empty();
    }
}
