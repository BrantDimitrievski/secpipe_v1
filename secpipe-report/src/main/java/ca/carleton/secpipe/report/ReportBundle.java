package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.config.SecPipeConfig.OutputFormat;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.report.patch.FixedPatches;
import ca.carleton.secpipe.report.patch.PatchSource;
import ca.carleton.secpipe.validators.CapPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.SchemaRegistry;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rendered report files, by file name, or the reason nothing could be rendered. The three JSON files are
 * schema-validated before anything is returned; a failure produces no files at all.
 */
public record ReportBundle(Map<String, String> files, Optional<String> incompleteReason) {

    public static final String FINDINGS = "findings.json";
    public static final String LEDGER = "coverage-ledger.json";
    public static final String METADATA = "run-metadata.json";
    public static final String SARIF = "secpipe.sarif";
    public static final String MARKDOWN = "report.md";
    /** Only the {@code fixed} patches, concatenated; absent when nothing is fixed. */
    public static final String FIXES_PATCH = FixedPatches.FILE_NAME;

    public ReportBundle {
        files = Map.copyOf(new TreeMap<>(files));
    }

    /** True when the files may be written. */
    public boolean ok() {
        return incompleteReason.isEmpty();
    }

    /**
     * Renders and validates. The three JSON files are always produced; SARIF and Markdown follow {@code formats}
     * (HTML and metrics are not produced here). Validation covers findings.json (schema plus rules F1-F9), and the
     * ledger and run-metadata schemas.
     */
    public static ReportBundle render(ReportInputs inputs, ReportRenderer renderer, Set<OutputFormat> formats) {
        return render(inputs, renderer, formats, PatchSource.NONE);
    }

    /**
     * As {@link #render(ReportInputs, ReportRenderer, Set)}, with the patch files readable through
     * {@code patches}: SARIF results of {@code fixed} records then carry {@code fixes[]} (with the standard renderer)
     * and {@value #FIXES_PATCH} is part of the bundle when at least one fixed patch can be read.
     */
    public static ReportBundle render(ReportInputs inputs, ReportRenderer renderer, Set<OutputFormat> formats,
            PatchSource patches) {
        String findings = renderer.json(inputs);
        ValidationResult findingsResult = FindingsValidator.validateJson(findings, Optional.empty(), CapPolicy.NONE);
        if (!findingsResult.ok()) {
            return failed("findings.json", findingsResult);
        }
        String ledger = LedgerCodec.write(inputs.ledger());
        ValidationResult ledgerResult = SchemaRegistry.shared().validate("ledger.schema.json", ledger);
        if (!ledgerResult.ok()) {
            return failed("coverage-ledger.json", ledgerResult);
        }
        String metadata = RunMetadataCodec.write(inputs.metadata());
        ValidationResult metadataResult = SchemaRegistry.shared().validate("run-metadata.schema.json", metadata);
        if (!metadataResult.ok()) {
            return failed("run-metadata.json", metadataResult);
        }
        Map<String, String> files = new TreeMap<>();
        files.put(FINDINGS, findings);
        files.put(LEDGER, ledger);
        files.put(METADATA, metadata);
        if (formats.contains(OutputFormat.SARIF)) {
            files.put(SARIF, renderer instanceof DefaultReportRenderer
                    ? SarifWriter.render(inputs, patches) : renderer.sarif(inputs));
        }
        if (formats.contains(OutputFormat.MARKDOWN)) {
            files.put(MARKDOWN, renderer.markdown(inputs));
        }
        FixedPatches.combine(inputs.findings(), patches).ifPresent(p -> files.put(FIXES_PATCH, p));
        return new ReportBundle(files, Optional.empty());
    }

    /** Writes every file into {@code dir} (created if needed) as UTF-8. Writes nothing unless {@link #ok()}. */
    public void write(Path dir) {
        if (!ok()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, String> e : files.entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
            }
            if (!files.containsKey(FIXES_PATCH) && files.containsKey(FINDINGS)) {
                // the bundle was rendered without patch access: build the combined patch from the files on disk
                Optional<String> combined = FixedPatches.combine(ContractJson.readFindings(files.get(FINDINGS)),
                        PatchSource.fromDir(dir));
                if (combined.isPresent()) {
                    Files.writeString(dir.resolve(FIXES_PATCH), combined.get(), StandardCharsets.UTF_8);
                } else {
                    Files.deleteIfExists(dir.resolve(FIXES_PATCH));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write report files to " + dir, e);
        }
    }

    private static ReportBundle failed(String file, ValidationResult result) {
        ValidationError first = result.errors().getFirst();
        int more = result.errors().size() - 1;
        String reason = "report validation failed for " + file + ": " + first
                + (more > 0 ? " (and " + more + " more)" : "");
        return new ReportBundle(Map.of(), Optional.of(reason));
    }
}
