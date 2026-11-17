package ca.carleton.secpipe.contracts.json;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.json.LedgerCodec.In;
import ca.carleton.secpipe.contracts.json.LedgerCodec.Out;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.run.RunMetadata.Ai;
import ca.carleton.secpipe.contracts.run.RunMetadata.BudgetSnapshot;
import ca.carleton.secpipe.contracts.run.RunMetadata.Counts;
import ca.carleton.secpipe.contracts.run.RunMetadata.Fallback;
import ca.carleton.secpipe.contracts.run.RunMetadata.ModelAssignment;
import ca.carleton.secpipe.contracts.run.RunMetadata.PriorRun;
import ca.carleton.secpipe.contracts.run.RunMetadata.Sandbox;
import ca.carleton.secpipe.contracts.run.RunMetadata.Scope;
import ca.carleton.secpipe.contracts.run.RunMetadata.Target;
import ca.carleton.secpipe.contracts.run.RunMetadata.ToolRun;
import ca.carleton.secpipe.contracts.run.RunMetadata.Usage;
import ca.carleton.secpipe.contracts.run.RunMetadata.Validators;
import java.nio.file.Path;
import java.util.Objects;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

/**
 * Codec for {@code run-metadata.json} (schemas/run-metadata.schema.json).
 *
 * <ul>
 *   <li>Reading is strict (unknown, missing or mistyped properties, duplicate keys and trailing tokens are a
 *       {@link ContractJsonException} with a JSON pointer). Schema-optional integers that the record holds as
 *       primitives ({@code discarded_outputs}, {@code guardrail_downgrades}, {@code max_sandbox_runs},
 *       {@code sandbox_runs_used}, {@code prior_run} counters) read as {@code 0} when absent and are always
 *       written.</li>
 *   <li>{@link #write(RunMetadata)} is byte-stable: properties in schema order, map-valued properties
 *       ({@code stage_timings_ms}, {@code budget.reserved}, {@code counts.ledger_units},
 *       {@code counts.by_severity}) with sorted keys, two-space indent, LF line ends, final newline.</li>
 *   <li>Provider modes use their CLI names ({@code openai-compatible}); other enums are lower snake case.</li>
 * </ul>
 * The complete/incomplete rule ({@code incomplete_reason} iff incomplete) is the schema's job; this codec
 * writes what the record holds.
 */
public final class RunMetadataCodec {

    private RunMetadataCodec() {
    }

    /** A Jackson module with a serializer and deserializer for {@link RunMetadata}. */
    public static JacksonModule module() {
        SimpleModule module = new SimpleModule("secpipe-run-metadata");
        LedgerCodec.adapt(module, RunMetadata.class, RunMetadataCodec::toTree, RunMetadataCodec::fromTree);
        return module;
    }

    /** Reads a {@code run-metadata.json} file (UTF-8). */
    public static RunMetadata read(Path file) {
        return read(LedgerCodec.readFile(file));
    }

    /** Reads {@code run-metadata.json} content (one JSON object). */
    public static RunMetadata read(String json) {
        return fromTree(LedgerCodec.parse(json));
    }

    /** Writes run metadata: pretty, schema property order, sorted map keys, LF, final newline. */
    public static String write(RunMetadata metadata) {
        return LedgerCodec.pretty(toTree(metadata));
    }

    /** Writes run metadata to {@code file} as UTF-8 (see {@link #write(RunMetadata)}). */
    public static void write(Path file, RunMetadata metadata) {
        LedgerCodec.writeFile(file, write(metadata));
    }

    /** The JSON object {@link #write(RunMetadata)} prints. */
    public static ObjectNode toTree(RunMetadata metadata) {
        return writeMetadata(Objects.requireNonNull(metadata, "metadata"), "");
    }

    /** Reads run metadata from a JSON object. */
    public static RunMetadata fromTree(JsonNode node) {
        return readMetadata(node, "");
    }

    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeMetadata(RunMetadata m, String path) {
        Out out = new Out(path);
        out.integer("schema_version", m.schemaVersion());
        out.str("run_id", m.runId());
        out.str("secpipe_version", m.secpipeVersion());
        out.instant("started_at", m.startedAt());
        out.instant("finished_at", m.finishedAt());
        out.enumValue("terminal_state", m.terminalState());
        out.optStr("incomplete_reason", m.incompleteReason());
        out.enumValue("profile", m.profile());
        out.obj("scope", m.scope(), RunMetadataCodec::writeScope);
        out.obj("target", m.target(), RunMetadataCodec::writeTarget);
        out.obj("ai", m.ai(), RunMetadataCodec::writeAi);
        out.obj("budget", m.budget(), RunMetadataCodec::writeBudget);
        out.list("tools", m.tools(), RunMetadataCodec::writeTool, true);
        out.numberMap("stage_timings_ms", m.stageTimingsMs(), true);
        out.obj("sandbox", m.sandbox(), RunMetadataCodec::writeSandbox);
        out.optObj("prior_run", m.priorRun(), RunMetadataCodec::writePriorRun);
        out.obj("counts", m.counts(), RunMetadataCodec::writeCounts);
        out.obj("validators", m.validators(), RunMetadataCodec::writeValidators);
        return out.node();
    }

    private static RunMetadata readMetadata(JsonNode node, String path) {
        In in = new In(node, path);
        RunMetadata m = new RunMetadata(
                in.integer("schema_version"),
                in.str("run_id"),
                in.str("secpipe_version"),
                in.instant("started_at"),
                in.instant("finished_at"),
                in.enumValue("terminal_state", RunMetadata.TerminalState.class),
                in.optStr("incomplete_reason"),
                in.enumValue("profile", RunProfile.class),
                in.obj("scope", RunMetadataCodec::readScope),
                in.obj("target", RunMetadataCodec::readTarget),
                in.obj("ai", RunMetadataCodec::readAi),
                in.obj("budget", RunMetadataCodec::readBudget),
                in.list("tools", RunMetadataCodec::readTool, true),
                in.longMap("stage_timings_ms", true),
                in.obj("sandbox", RunMetadataCodec::readSandbox),
                in.optObj("prior_run", RunMetadataCodec::readPriorRun),
                in.obj("counts", RunMetadataCodec::readCounts),
                in.obj("validators", RunMetadataCodec::readValidators));
        in.done();
        return m;
    }

    private static ObjectNode writeScope(Scope s, String path) {
        Out out = new Out(path);
        out.enumValue("mode", s.mode());
        out.optStr("base_ref", s.baseRef());
        out.optStr("head_ref", s.headRef());
        out.strList("changed_files", s.changedFiles(), false);
        return out.node();
    }

    private static Scope readScope(JsonNode node, String path) {
        In in = new In(node, path);
        Scope s = new Scope(in.enumValue("mode", Scope.ScopeMode.class), in.optStr("base_ref"),
                in.optStr("head_ref"), in.strList("changed_files", false));
        in.done();
        return s;
    }

    private static ObjectNode writeTarget(Target t, String path) {
        Out out = new Out(path);
        out.str("name", t.name());
        out.str("build_tool", t.buildTool());
        out.optStr("jdk", t.jdk());
        out.optStr("maven_version", t.mavenVersion());
        out.longValue("loc", t.loc());
        out.integer("source_files", t.sourceFiles());
        out.strList("frameworks", t.frameworks(), false);
        out.optStr("git_commit", t.gitCommit());
        return out.node();
    }

    private static Target readTarget(JsonNode node, String path) {
        In in = new In(node, path);
        Target t = new Target(in.str("name"), in.str("build_tool"), in.optStr("jdk"), in.optStr("maven_version"),
                in.longValue("loc"), in.integer("source_files"), in.strList("frameworks", false),
                in.optStr("git_commit"));
        in.done();
        return t;
    }

    private static ObjectNode writeAi(Ai a, String path) {
        Out out = new Out(path);
        out.enumValue("requested_mode", a.requestedMode(), ProviderMode::cliName);
        out.enumValue("effective_mode", a.effectiveMode(), ProviderMode::cliName);
        out.list("fallbacks", a.fallbacks(), RunMetadataCodec::writeFallback, true);
        out.list("models", a.models(), RunMetadataCodec::writeModel, true);
        out.list("usage", a.usage(), RunMetadataCodec::writeUsage, true);
        out.integer("guardrail_downgrades", a.guardrailDowngrades());
        out.optStr("prompts_hash", a.promptsHash());
        return out.node();
    }

    private static Ai readAi(JsonNode node, String path) {
        In in = new In(node, path);
        Ai a = new Ai(in.enumValue("requested_mode", ProviderMode.class, ProviderMode::cliName),
                in.enumValue("effective_mode", ProviderMode.class, ProviderMode::cliName),
                in.list("fallbacks", RunMetadataCodec::readFallback, true),
                in.list("models", RunMetadataCodec::readModel, true),
                in.list("usage", RunMetadataCodec::readUsage, true),
                in.intOrZero("guardrail_downgrades"), in.optStr("prompts_hash"));
        in.done();
        return a;
    }

    private static ObjectNode writeFallback(Fallback f, String path) {
        Out out = new Out(path);
        out.enumValue("from", f.from(), ProviderMode::cliName);
        out.enumValue("to", f.to(), ProviderMode::cliName);
        out.str("reason", f.reason());
        return out.node();
    }

    private static Fallback readFallback(JsonNode node, String path) {
        In in = new In(node, path);
        Fallback f = new Fallback(in.enumValue("from", ProviderMode.class, ProviderMode::cliName),
                in.enumValue("to", ProviderMode.class, ProviderMode::cliName), in.str("reason"));
        in.done();
        return f;
    }

    private static ObjectNode writeModel(ModelAssignment m, String path) {
        Out out = new Out(path);
        out.enumValue("role", m.role());
        out.enumValue("provider", m.provider(), ProviderMode::cliName);
        out.str("model", m.model());
        out.enumValue("tier", m.tier());
        return out.node();
    }

    private static ModelAssignment readModel(JsonNode node, String path) {
        In in = new In(node, path);
        ModelAssignment m = new ModelAssignment(in.enumValue("role", AgentRole.class),
                in.enumValue("provider", ProviderMode.class, ProviderMode::cliName), in.str("model"),
                in.enumValue("tier", CapabilityTier.class));
        in.done();
        return m;
    }

    private static ObjectNode writeUsage(Usage u, String path) {
        Out out = new Out(path);
        out.enumValue("role", u.role());
        out.integer("calls", u.calls());
        out.integer("retries", u.retries());
        out.integer("discarded_outputs", u.discardedOutputs());
        out.longValue("input_tokens", u.inputTokens());
        out.longValue("output_tokens", u.outputTokens());
        out.optDouble("estimated_cost_usd", u.estimatedCostUsd());
        return out.node();
    }

    private static Usage readUsage(JsonNode node, String path) {
        In in = new In(node, path);
        Usage u = new Usage(in.enumValue("role", AgentRole.class), in.integer("calls"), in.integer("retries"),
                in.intOrZero("discarded_outputs"), in.longValue("input_tokens"), in.longValue("output_tokens"),
                in.optDouble("estimated_cost_usd"));
        in.done();
        return u;
    }

    private static ObjectNode writeBudget(BudgetSnapshot b, String path) {
        Out out = new Out(path);
        out.integer("max_agent_calls", b.maxAgentCalls());
        out.integer("used", b.used());
        out.numberMap("reserved", b.reserved(), true);
        out.bool("exhausted", b.exhausted());
        out.integer("max_sandbox_runs", b.maxSandboxRuns());
        out.integer("sandbox_runs_used", b.sandboxRunsUsed());
        return out.node();
    }

    private static BudgetSnapshot readBudget(JsonNode node, String path) {
        In in = new In(node, path);
        BudgetSnapshot b = new BudgetSnapshot(in.integer("max_agent_calls"), in.integer("used"),
                in.intMap("reserved", true), in.bool("exhausted"), in.intOrZero("max_sandbox_runs"),
                in.intOrZero("sandbox_runs_used"));
        in.done();
        return b;
    }

    private static ObjectNode writeTool(ToolRun t, String path) {
        Out out = new Out(path);
        out.str("id", t.id());
        out.optStr("version", t.version());
        out.enumValue("status", t.status());
        out.optStr("reason", t.reason());
        out.longValue("duration_ms", t.durationMs());
        out.integer("candidates", t.candidates());
        return out.node();
    }

    private static ToolRun readTool(JsonNode node, String path) {
        In in = new In(node, path);
        ToolRun t = new ToolRun(in.str("id"), in.optStr("version"), in.enumValue("status", ToolRun.Status.class),
                in.optStr("reason"), in.longValue("duration_ms"), in.integer("candidates"));
        in.done();
        return t;
    }

    private static ObjectNode writeSandbox(Sandbox s, String path) {
        Out out = new Out(path);
        out.bool("available", s.available());
        out.optStr("image", s.image());
        out.strList("enforced_controls", s.enforcedControls(), false);
        out.strList("unenforceable_controls", s.unenforceableControls(), false);
        out.optStr("reason", s.reason());
        return out.node();
    }

    private static Sandbox readSandbox(JsonNode node, String path) {
        In in = new In(node, path);
        Sandbox s = new Sandbox(in.bool("available"), in.optStr("image"), in.strList("enforced_controls", false),
                in.strList("unenforceable_controls", false), in.optStr("reason"));
        in.done();
        return s;
    }

    private static ObjectNode writePriorRun(PriorRun p, String path) {
        Out out = new Out(path);
        out.optStr("run_id", p.runId());
        out.integer("carried_forward", p.carriedForward());
        out.integer("revalidated", p.revalidated());
        out.integer("suppressed_rejections", p.suppressedRejections());
        out.optStr("incompatible_reason", p.incompatibleReason());
        return out.node();
    }

    private static PriorRun readPriorRun(JsonNode node, String path) {
        In in = new In(node, path);
        PriorRun p = new PriorRun(in.optStr("run_id"), in.intOrZero("carried_forward"), in.intOrZero("revalidated"),
                in.intOrZero("suppressed_rejections"), in.optStr("incompatible_reason"));
        in.done();
        return p;
    }

    private static ObjectNode writeCounts(Counts c, String path) {
        Out out = new Out(path);
        out.integer("confirmed", c.confirmed());
        out.integer("needs_validation", c.needsValidation());
        out.integer("rejected", c.rejected());
        out.numberMap("ledger_units", c.ledgerUnits(), true);
        out.numberMap("by_severity", c.bySeverity(), false);
        return out.node();
    }

    private static Counts readCounts(JsonNode node, String path) {
        In in = new In(node, path);
        Counts c = new Counts(in.integer("confirmed"), in.integer("needs_validation"), in.integer("rejected"),
                in.intMap("ledger_units", true), in.intMap("by_severity", false));
        in.done();
        return c;
    }

    private static ObjectNode writeValidators(Validators v, String path) {
        Out out = new Out(path);
        out.enumValue("findings", v.findings());
        out.enumValue("ledger", v.ledger());
        return out.node();
    }

    private static Validators readValidators(JsonNode node, String path) {
        In in = new In(node, path);
        Validators v = new Validators(in.enumValue("findings", RunMetadata.ValidatorStatus.class),
                in.enumValue("ledger", RunMetadata.ValidatorStatus.class));
        in.done();
        return v;
    }
}
