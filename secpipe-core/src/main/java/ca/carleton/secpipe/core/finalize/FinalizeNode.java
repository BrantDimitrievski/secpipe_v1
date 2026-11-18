package ca.carleton.secpipe.core.finalize;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.core.ledger.LedgerInvariants;
import ca.carleton.secpipe.core.scoring.ScoringPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Graph node {@code finalize} (folded from WO-K.11): the last node before {@code report}. Runs the findings validator
 * (with {@link ScoringPolicy} as the F4 cap) and the ledger checks one last time (L2 final, L5 fingerprints, L6
 * terminal), sets the terminal state, and freezes {@code run-metadata.json} into {@code RunContext.outDir()}.
 *
 * <p>A validator failure becomes {@code incomplete_reason}, never a crash. The metadata is written once, and is
 * written even when validation fails. Its {@code incomplete_reason} follows the engine's rule "first reason wins": an
 * earlier node's reason is kept; finalize's own causes are added after it.
 */
public final class FinalizeNode implements PipelineNode {

    /** File name of the frozen metadata under {@code outDir}. */
    public static final String RUN_METADATA = "run-metadata.json";

    /** How many validator errors an incomplete reason quotes per validator. */
    static final int MAX_QUOTED_ERRORS = 3;

    private final ScoringPolicy policy;

    /** Used by the engine (FQCN table in PipelineGraph). */
    public FinalizeNode() {
        this(new ScoringPolicy());
    }

    public FinalizeNode(ScoringPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public String id() {
        return "finalize";
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
        List<Finding> records = state.records().values().stream()
                .sorted(Comparator.comparing(Finding::fingerprint)).toList();
        List<LedgerUnit> ledger = state.ledger();
        Path target = state.config().target();
        Optional<Path> root = Files.isDirectory(target) ? Optional.of(target) : Optional.empty();

        ValidationResult findings = FindingsValidator.validate(records, root, policy);

        Set<String> known = new HashSet<>(state.records().keySet());
        state.candidates().forEach(c -> c.fingerprint().ifPresent(known::add));
        ValidationResult ledgerResult = LedgerInvariants.checkFinal(ledger)
                .merge(LedgerInvariants.checkFingerprints(ledger, known));

        List<String> causes = new ArrayList<>();
        LedgerInvariants.incompleteReason(ledger).ifPresent(causes::add);
        if (!ledgerResult.ok()) {
            causes.add("ledger validator failed: " + quote(ledgerResult));
        }
        if (!findings.ok()) {
            causes.add("findings validator failed: " + quote(findings));
        }
        Optional<String> own = causes.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", causes));
        Optional<String> prior = state.incompleteReason().filter(r -> !r.isBlank());
        Optional<String> reason = prior.isPresent()
                ? Optional.of(own.map(o -> prior.get() + "; " + o).orElse(prior.get()))
                : own;

        RunMetadata.Validators validators = new RunMetadata.Validators(status(findings), status(ledgerResult));
        RunMetadata metadata = RunMetadataBuilder.build(state, ctx.clock().instant(), ctx.budget(),
                ctx.sandbox().isPresent(), reason, validators);
        write(ctx.outDir(), metadata);
        ctx.log().info("finalize: terminal state " + metadata.terminalState().name().toLowerCase(java.util.Locale.ROOT)
                + reason.map(r -> " (" + r + ")").orElse(""));

        return Result.next(new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(),
                Optional.empty(), List.of(), List.of(), Map.of(), List.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.of(), own));
    }

    private static String quote(ValidationResult result) {
        List<ValidationError> errors = result.errors();
        String head = errors.stream().limit(MAX_QUOTED_ERRORS).map(ValidationError::toString)
                .collect(Collectors.joining("; "));
        int more = errors.size() - MAX_QUOTED_ERRORS;
        return more > 0 ? head + " (+" + more + " more)" : head;
    }

    private static RunMetadata.ValidatorStatus status(ValidationResult r) {
        return r.ok() ? RunMetadata.ValidatorStatus.PASS : RunMetadata.ValidatorStatus.FAIL;
    }

    private static void write(Path outDir, RunMetadata metadata) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        RunMetadataCodec.write(outDir.resolve(RUN_METADATA), metadata);
    }
}
