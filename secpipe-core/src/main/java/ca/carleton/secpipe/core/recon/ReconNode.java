package ca.carleton.secpipe.core.recon;

import ca.carleton.secpipe.contracts.json.ReconFactsCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.pipeline.RunContext;
import ca.carleton.secpipe.contracts.pipeline.StateDelta;
import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.recon.ReconFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The {@code recon} node (WO-K.4). Deterministic: no LLM, no target-code execution (sources are only parsed).
 *
 * <p>It indexes the target ({@link FileSourceIndex}), walks the Java sources with JavaParser to find entry surfaces
 * and sink call sites for {@link SinkCatalogue#FAMILIES}, writes {@code <work>/recon-facts.json} with
 * {@link ReconFactsCodec#write(Path, ReconFacts)}, and seeds {@code planned} ledger units (sorted by coverage id;
 * units already in the ledger are not re-added). Files that do not parse become run notes.
 */
public final class ReconNode implements PipelineNode {

    /** What one recon pass produced. */
    public record Outcome(FileSourceIndex index, ReconFacts facts, List<LedgerUnit> units, List<String> notes) { }

    @Override
    public String id() {
        return "recon";
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
    public Result apply(PipelineState state, RunContext ctx) throws Exception {
        Path root = state.profile().map(ProjectProfile::root).orElse(state.config().target());
        Outcome outcome = run(root, state.config().includeTests());
        Files.createDirectories(ctx.workDir());
        ReconFactsCodec.write(ctx.workDir().resolve(ReconFactsCodec.FILE_NAME), outcome.facts());

        Set<String> known = state.ledger().stream().map(LedgerUnit::coverageId).collect(Collectors.toSet());
        List<LedgerUnit> fresh = outcome.units().stream().filter(u -> !known.contains(u.coverageId())).toList();
        List<String> notes = new ArrayList<>(outcome.notes());
        int sinks = outcome.facts().sinksByFamily().values().stream().mapToInt(List::size).sum();
        String summary = "recon: " + outcome.facts().entrySurfaces().size() + " entry surfaces, " + sinks
                + " sink sites, " + fresh.size() + " ledger units seeded";
        ctx.log().info(summary);
        notes.forEach(n -> ctx.log().warn(n));

        StateDelta delta = new StateDelta(Optional.empty(), Optional.empty(), Optional.empty(), fresh, List.of(),
                Optional.empty(), List.of(), List.of(), Map.of(), List.of(), Map.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), List.copyOf(notes), Optional.empty());
        return Result.next(delta);
    }

    /** Runs recon on {@code root} without touching any pipeline state. */
    public static Outcome run(Path root, boolean includeTests) {
        FileSourceIndex index = FileSourceIndex.scan(root);
        List<String> javaFiles = index.files().stream()
                .filter(f -> f.endsWith(".java"))
                .filter(f -> includeTests || !isTestSource(f))
                .toList();
        List<String> notes = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            notes.add("recon: target " + root + " is not a directory; nothing to inventory");
        }
        JavaRecon.Result walk = new JavaRecon(SinkCatalogue.load()).scan(index, javaFiles);
        notes.addAll(walk.notes());

        Map<String, List<Location>> sinks = new TreeMap<>();
        SinkCatalogue.FAMILIES.forEach(f -> sinks.put(f, walk.sinksByFamily().getOrDefault(f, List.of())));
        List<Location> entries = walk.entrySurfaces().stream().map(JavaRecon.EntrySurface::location).toList();
        ReconFacts facts = new ReconFacts(entries, sinks);

        List<JavaRecon.EntrySurface> surfaces = new ArrayList<>(walk.entrySurfaces());
        surfaces.sort((a, b) -> a.location().compareTo(b.location()));
        List<LedgerUnit> units = LedgerSeeder.seed(surfaces, facts, SinkCatalogue.FAMILIES);
        return new Outcome(index, facts, units, List.copyOf(notes));
    }

    private static boolean isTestSource(String file) {
        return file.startsWith("src/test/") || file.contains("/src/test/");
    }
}
