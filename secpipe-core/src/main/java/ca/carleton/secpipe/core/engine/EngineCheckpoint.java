package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.json.AgentOutputCodec;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.json.FindingCodec;
import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Writes {@code state.json}, a debugging and resume snapshot of {@link PipelineState}, after every node. Contract
 * records (ledger units, candidates, records, survivor drafts, tool runs) use the contract codecs; everything else
 * goes through the contract mapper. Keys follow the {@code PipelineState} field order (snake_case); maps are sorted
 * by key, so the same state always gives the same bytes.
 *
 * <p>The file is a debugging artifact, not a contract: it has no schema and is never read by the report.
 */
public final class EngineCheckpoint {

    /** The checkpoint file name inside the target directory. */
    public static final String FILE_NAME = "state.json";

    private static final JsonMapper MAPPER = ContractJson.mapper(LedgerCodec.module(), RunMetadataCodec.module());

    private EngineCheckpoint() { }

    /**
     * Writes {@code state} to {@code dir/state.json} (creating {@code dir}), replacing any earlier checkpoint
     * atomically where the file system allows it.
     *
     * @return the written file
     */
    public static Path write(PipelineState state, Path dir) throws IOException {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(dir, "dir");
        String json = toJson(state);
        Files.createDirectories(dir);
        Path target = dir.resolve(FILE_NAME);
        Path tmp = dir.resolve(FILE_NAME + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** The checkpoint text: two-space indent, LF line ends, final newline. */
    public static String toJson(PipelineState s) {
        Objects.requireNonNull(s, "state");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", s.runId());
        root.set("config", MAPPER.valueToTree(s.config()));
        s.environment().ifPresent(e -> root.set("environment", MAPPER.valueToTree(e)));
        s.profile().ifPresent(p -> root.set("profile", MAPPER.valueToTree(p)));
        ArrayNode diffScope = root.putArray("diff_scope");
        s.diffScope().forEach(diffScope::add);
        ArrayNode ledger = root.putArray("ledger");
        s.ledger().forEach(u -> ledger.add(LedgerCodec.toTree(u)));
        ArrayNode candidates = root.putArray("candidates");
        s.candidates().forEach(c -> candidates.add(FindingCodec.toTree(c)));
        ObjectNode records = root.putObject("records");
        sorted(s.records()).forEach((fp, f) -> records.set(fp, FindingCodec.toTree(f)));
        ObjectNode drafts = root.putObject("survivor_drafts");
        sorted(s.survivorDrafts()).forEach((fp, d) -> drafts.set(fp, AgentOutputCodec.toTree(d)));
        ArrayNode toolRuns = root.putArray("tool_runs");
        s.toolRuns().forEach(t -> toolRuns.add(MAPPER.<JsonNode>valueToTree(t)));
        ObjectNode timings = root.putObject("stage_timings_ms");
        sorted(s.stageTimingsMs()).forEach(timings::put);
        root.put("hunter_wave", s.hunterWave());
        root.put("critic_passes", s.criticPasses());
        root.put("critic_says_stop", s.criticSaysStop());
        ObjectNode fixAttempts = root.putObject("fix_attempts_by_fingerprint");
        sorted(s.fixAttemptsByFingerprint()).forEach(fixAttempts::put);
        s.prior().ifPresent(p -> root.set("prior", prior(p)));
        ArrayNode notes = root.putArray("notes");
        s.notes().forEach(notes::add);
        s.incompleteReason().ifPresent(r -> root.put("incomplete_reason", r));
        return MAPPER.writer().with(ContractJson.prettyPrinter()).writeValueAsString(root) + "\n";
    }

    private static ObjectNode prior(PipelineState.PriorRun p) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("run_id", p.runId());
        ArrayNode ledger = node.putArray("ledger");
        p.ledger().forEach(u -> ledger.add(LedgerCodec.toTree(u)));
        ArrayNode findings = node.putArray("findings");
        p.findings().forEach(f -> findings.add(FindingCodec.toTree(f)));
        ObjectNode hashes = node.putObject("file_hashes");
        sorted(p.fileHashes()).forEach(hashes::put);
        return node;
    }

    private static <V> Map<String, V> sorted(Map<String, V> m) {
        return new TreeMap<>(m);
    }
}
