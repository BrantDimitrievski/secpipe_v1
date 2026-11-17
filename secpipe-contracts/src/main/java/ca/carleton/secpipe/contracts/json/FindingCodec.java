package ca.carleton.secpipe.contracts.json;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Adequacy;
import ca.carleton.secpipe.contracts.finding.Finding.CodeChange;
import ca.carleton.secpipe.contracts.finding.Finding.Condition;
import ca.carleton.secpipe.contracts.finding.Finding.Confidence;
import ca.carleton.secpipe.contracts.finding.Finding.DependencyRef;
import ca.carleton.secpipe.contracts.finding.Finding.Evidence;
import ca.carleton.secpipe.contracts.finding.Finding.Execution;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.Remediation;
import ca.carleton.secpipe.contracts.finding.Finding.Scored;
import ca.carleton.secpipe.contracts.finding.Finding.SecPipeExt;
import ca.carleton.secpipe.contracts.finding.Finding.Severity;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.finding.Finding.ToolSource;
import ca.carleton.secpipe.contracts.finding.Finding.TraceStep;
import ca.carleton.secpipe.contracts.finding.Finding.ValidationPlan;
import ca.carleton.secpipe.contracts.finding.Finding.Verification;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hand-written mapping between the annotation-free contract records ({@link Finding}, {@link Candidate} and all
 * nested records) and exactly the JSON shape of {@code schemas/findings.schema.json}.
 *
 * <p>Rules (WO-C.1):
 * <ul>
 *   <li>Property names are snake_case; properties are written in the schema's property order.</li>
 *   <li>Enums are written as lower-case names ({@code NEEDS_VALIDATION} → {@code needs_validation}) and read
 *       only in that exact spelling.</li>
 *   <li>An empty {@code Optional} is omitted. An empty list is omitted, except for lists the schema requires
 *       ({@code trace}, {@code evidence}, {@code conditions}, {@code blockers}, {@code payloads},
 *       {@code instructions}, {@code secpipe.tool_sources}, {@code secpipe.cwe}), which are always written.</li>
 *   <li>Two non-{@code Optional} components are optional in the schema. They are always written and get a
 *       default when absent on read: {@code verification.guardrail_applied} ({@code false}) and
 *       {@code dependency.reachable} ({@code unknown}).</li>
 *   <li>{@link Finding} is polymorphic by its {@code verdict} property; there is no type annotation on the
 *       records.</li>
 *   <li>Reading is strict: unknown properties, missing required properties, JSON {@code null}, wrong JSON types
 *       and unknown enum spellings are errors ({@link ContractJsonException}, with a JSON pointer). Nothing is
 *       repaired.</li>
 * </ul>
 *
 * <p>Value constraints the schema expresses (patterns, {@code minItems}, ...) are not checked here; validate the
 * written output against the schema (validators module) for that.
 */
public final class FindingCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Types that {@link #write(Object)} and {@link #read(JsonNode, Class)} handle, in registration order. */
    private static final Map<Class<?>, Mapping<?>> MAPPINGS = new LinkedHashMap<>();

    static {
        register(Finding.class, FindingCodec::writeFinding, FindingCodec::readFinding);
        register(Finding.Confirmed.class, FindingCodec::writeFinding,
                (in, path) -> cast(readFinding(in, path), Finding.Confirmed.class, path));
        register(Finding.NeedsValidation.class, FindingCodec::writeFinding,
                (in, path) -> cast(readFinding(in, path), Finding.NeedsValidation.class, path));
        register(Finding.Rejected.class, FindingCodec::writeFinding,
                (in, path) -> cast(readFinding(in, path), Finding.Rejected.class, path));
        register(Candidate.class, FindingCodec::writeCandidate, FindingCodec::readCandidate);
        register(TraceStep.class, FindingCodec::writeTraceStep, FindingCodec::readTraceStep);
        register(Evidence.class, FindingCodec::writeEvidence, FindingCodec::readEvidence);
        register(Condition.class, FindingCodec::writeCondition, FindingCodec::readCondition);
        register(Execution.class, FindingCodec::writeExecution, FindingCodec::readExecution);
        register(CodeChange.class, FindingCodec::writeCodeChange, FindingCodec::readCodeChange);
        register(Remediation.class, FindingCodec::writeRemediation, FindingCodec::readRemediation);
        register(Scored.class, FindingCodec::writeScored, FindingCodec::readScored);
        register(Severity.class, FindingCodec::writeSeverity, FindingCodec::readSeverity);
        register(Confidence.class, FindingCodec::writeConfidence, FindingCodec::readConfidence);
        register(ValidationPlan.class, FindingCodec::writeValidationPlan, FindingCodec::readValidationPlan);
        register(ToolSource.class, FindingCodec::writeToolSource, FindingCodec::readToolSource);
        register(DependencyRef.class, FindingCodec::writeDependency, FindingCodec::readDependency);
        register(TestEvidence.class, FindingCodec::writeTestEvidence, FindingCodec::readTestEvidence);
        register(Adequacy.class, FindingCodec::writeAdequacy, FindingCodec::readAdequacy);
        register(FixAttempt.class, FindingCodec::writeFixAttempt, FindingCodec::readFixAttempt);
        register(VerifierRef.class, FindingCodec::writeVerifierRef, FindingCodec::readVerifierRef);
        register(Verification.class, FindingCodec::writeVerification, FindingCodec::readVerification);
        register(SecPipeExt.class, FindingCodec::writeSecPipe, FindingCodec::readSecPipe);
    }

    private FindingCodec() {
    }

    /** Converts one finding to its JSON object. */
    public static ObjectNode toTree(Finding finding) {
        return writeFinding(finding, "");
    }

    /** Converts one candidate to its JSON object. */
    public static ObjectNode toTree(Candidate candidate) {
        return writeCandidate(candidate, "");
    }

    /** Reads one finding, dispatching on {@code verdict}. */
    public static Finding findingFromTree(JsonNode node) {
        return readFinding(node, "");
    }

    /** Reads one candidate. */
    public static Candidate candidateFromTree(JsonNode node) {
        return readCandidate(node, "");
    }

    /** The contract types this codec maps (used by {@link ContractJson} to register Jackson adapters). */
    static Set<Class<?>> supportedTypes() {
        return MAPPINGS.keySet();
    }

    /** Writes any supported contract value. */
    static JsonNode write(Object value) {
        if (value == null) {
            throw new ContractJsonException("", "value is null");
        }
        Mapping<?> mapping = mappingFor(value.getClass());
        return mapping.writeUnchecked(value);
    }

    /** Reads any supported contract type. */
    static <T> T read(JsonNode node, Class<T> type) {
        Mapping<?> mapping = MAPPINGS.get(type);
        if (mapping == null) {
            throw new IllegalArgumentException("not a contract type handled by FindingCodec: " + type.getName());
        }
        return type.cast(mapping.reader().apply(node, ""));
    }

    private static Mapping<?> mappingFor(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Mapping<?> exact = MAPPINGS.get(c);
            if (exact != null) {
                return exact;
            }
        }
        for (Map.Entry<Class<?>, Mapping<?>> e : MAPPINGS.entrySet()) {
            if (e.getKey().isAssignableFrom(type)) {
                return e.getValue();
            }
        }
        throw new IllegalArgumentException("not a contract type handled by FindingCodec: " + type.getName());
    }

    private static <T> void register(Class<T> type, BiFunction<T, String, ? extends JsonNode> writer,
                                     BiFunction<JsonNode, String, ? extends T> reader) {
        MAPPINGS.put(type, new Mapping<>(type, writer, reader));
    }

    private static <T> T cast(Finding finding, Class<T> type, String path) {
        if (!type.isInstance(finding)) {
            throw new ContractJsonException(path + "/verdict",
                    "expected verdict '" + enumName(verdictOf(type)) + "' but found '"
                            + enumName(finding.verdict()) + "'");
        }
        return type.cast(finding);
    }

    private static Finding.Verdict verdictOf(Class<?> type) {
        if (type == Finding.Confirmed.class) {
            return Finding.Verdict.CONFIRMED;
        }
        return type == Finding.NeedsValidation.class ? Finding.Verdict.NEEDS_VALIDATION : Finding.Verdict.REJECTED;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Findings
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeFinding(Finding finding, String path) {
        if (finding == null) {
            throw new ContractJsonException(path, "finding is null");
        }
        Out out = new Out(path);
        out.enumValue("verdict", finding.verdict());
        out.str("fingerprint", finding.fingerprint());
        out.str("title", finding.title());
        out.str("description", finding.description());
        switch (finding) {
            case Finding.Confirmed c -> {
                out.str("root_cause", c.rootCause());
                out.str("intended_behavior", c.intendedBehavior());
                out.list("trace", c.trace(), FindingCodec::writeTraceStep, true);
                out.list("evidence", c.evidence(), FindingCodec::writeEvidence, true);
                out.list("conditions", c.conditions(), FindingCodec::writeCondition, true);
                out.obj("execution", c.execution(), FindingCodec::writeExecution);
                out.obj("remediation", c.remediation(), FindingCodec::writeRemediation);
                out.obj("severity", c.severity(), FindingCodec::writeSeverity);
                out.obj("confidence", c.confidence(), FindingCodec::writeConfidence);
            }
            case Finding.NeedsValidation n -> {
                out.str("claimed_root_cause", n.claimedRootCause());
                out.list("trace", n.trace(), FindingCodec::writeTraceStep, true);
                out.list("evidence", n.evidence(), FindingCodec::writeEvidence, true);
                out.strList("blockers", n.blockers(), true);
                out.obj("validation_plan", n.validationPlan(), FindingCodec::writeValidationPlan);
            }
            case Finding.Rejected r -> {
                out.str("claimed_root_cause", r.claimedRootCause());
                out.list("trace", r.trace(), FindingCodec::writeTraceStep, true);
                out.list("evidence", r.evidence(), FindingCodec::writeEvidence, true);
                out.str("reason", r.reason());
            }
        }
        out.obj("secpipe", finding.secpipe(), FindingCodec::writeSecPipe);
        return out.node;
    }

    private static Finding readFinding(JsonNode node, String path) {
        In in = new In(node, path);
        Finding.Verdict verdict = in.enumValue("verdict", Finding.Verdict.class);
        Finding finding = switch (verdict) {
            case CONFIRMED -> new Finding.Confirmed(
                    in.str("fingerprint"), in.str("title"), in.str("description"),
                    in.str("root_cause"), in.str("intended_behavior"),
                    in.list("trace", FindingCodec::readTraceStep, true),
                    in.list("evidence", FindingCodec::readEvidence, true),
                    in.list("conditions", FindingCodec::readCondition, true),
                    in.obj("execution", FindingCodec::readExecution),
                    in.obj("remediation", FindingCodec::readRemediation),
                    in.obj("severity", FindingCodec::readSeverity),
                    in.obj("confidence", FindingCodec::readConfidence),
                    in.obj("secpipe", FindingCodec::readSecPipe));
            case NEEDS_VALIDATION -> new Finding.NeedsValidation(
                    in.str("fingerprint"), in.str("title"), in.str("description"),
                    in.str("claimed_root_cause"),
                    in.list("trace", FindingCodec::readTraceStep, true),
                    in.list("evidence", FindingCodec::readEvidence, true),
                    in.strList("blockers", true),
                    in.obj("validation_plan", FindingCodec::readValidationPlan),
                    in.obj("secpipe", FindingCodec::readSecPipe));
            case REJECTED -> new Finding.Rejected(
                    in.str("fingerprint"), in.str("title"), in.str("description"),
                    in.str("claimed_root_cause"),
                    in.list("trace", FindingCodec::readTraceStep, true),
                    in.list("evidence", FindingCodec::readEvidence, true),
                    in.str("reason"),
                    in.obj("secpipe", FindingCodec::readSecPipe));
        };
        in.done();
        return finding;
    }

    private static ObjectNode writeCandidate(Candidate c, String path) {
        if (c == null) {
            throw new ContractJsonException(path, "candidate is null");
        }
        Out out = new Out(path);
        out.optStr("fingerprint", c.fingerprint());
        out.enumValue("category", c.category());
        out.enumValue("origin", c.origin());
        out.list("tool_sources", c.toolSources(), FindingCodec::writeToolSource, true);
        out.strList("cwe", c.cwe(), true);
        out.strList("cve", c.cve(), false);
        out.str("title", c.title());
        out.str("message", c.message());
        out.str("claimed_root_cause", c.claimedRootCause());
        out.list("trace", c.trace(), FindingCodec::writeTraceStep, true);
        out.list("evidence", c.evidence(), FindingCodec::writeEvidence, true);
        out.optStr("sink_statement", c.sinkStatement());
        out.optStr("sink_scope", c.sinkScope());
        out.optObj("dependency", c.dependency(), FindingCodec::writeDependency);
        out.optEnum("tool_severity_hint", c.toolSeverityHint());
        out.optEnum("confidence_hint", c.confidenceHint());
        out.optStr("strongest_control", c.strongestControl());
        out.optStr("smallest_fix", c.smallestFix());
        out.optStr("regression_case", c.regressionCase());
        out.strList("ledger_units", c.ledgerUnits(), false);
        return out.node;
    }

    private static Candidate readCandidate(JsonNode node, String path) {
        In in = new In(node, path);
        Candidate c = new Candidate(
                in.optStr("fingerprint"),
                in.enumValue("category", Finding.Category.class),
                in.enumValue("origin", Finding.Origin.class),
                in.list("tool_sources", FindingCodec::readToolSource, true),
                in.strList("cwe", true),
                in.strList("cve", false),
                in.str("title"),
                in.str("message"),
                in.str("claimed_root_cause"),
                in.list("trace", FindingCodec::readTraceStep, true),
                in.list("evidence", FindingCodec::readEvidence, true),
                in.optStr("sink_statement"),
                in.optStr("sink_scope"),
                in.optObj("dependency", FindingCodec::readDependency),
                in.optEnum("tool_severity_hint", Finding.Score5.class),
                in.optEnum("confidence_hint", Finding.Level3.class),
                in.optStr("strongest_control"),
                in.optStr("smallest_fix"),
                in.optStr("regression_case"),
                in.strList("ledger_units", false));
        in.done();
        return c;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Nested records (property order follows findings.schema.json)
    // ------------------------------------------------------------------------------------------------------------

    private static ObjectNode writeTraceStep(TraceStep v, String path) {
        Out out = new Out(path);
        out.enumValue("kind", v.kind());
        out.str("file", v.file());
        out.integer("line", v.line());
        out.str("scope", v.scope());
        out.str("description", v.description());
        return out.node;
    }

    private static TraceStep readTraceStep(JsonNode node, String path) {
        In in = new In(node, path);
        TraceStep v = new TraceStep(in.enumValue("kind", Finding.TraceKind.class), in.str("file"),
                in.integer("line"), in.str("scope"), in.str("description"));
        in.done();
        return v;
    }

    private static ObjectNode writeEvidence(Evidence v, String path) {
        Out out = new Out(path);
        out.str("file", v.file());
        out.integer("line", v.line());
        out.str("description", v.description());
        return out.node;
    }

    private static Evidence readEvidence(JsonNode node, String path) {
        In in = new In(node, path);
        Evidence v = new Evidence(in.str("file"), in.integer("line"), in.str("description"));
        in.done();
        return v;
    }

    private static ObjectNode writeCondition(Condition v, String path) {
        Out out = new Out(path);
        out.enumValue("kind", v.kind());
        out.str("description", v.description());
        return out.node;
    }

    private static Condition readCondition(JsonNode node, String path) {
        In in = new In(node, path);
        Condition v = new Condition(in.enumValue("kind", Finding.ConditionKind.class), in.str("description"));
        in.done();
        return v;
    }

    private static ObjectNode writeExecution(Execution v, String path) {
        Out out = new Out(path);
        out.str("attacker_perspective", v.attackerPerspective());
        out.strList("payloads", v.payloads(), true);
        out.strList("instructions", v.instructions(), true);
        out.str("observed_result", v.observedResult());
        return out.node;
    }

    private static Execution readExecution(JsonNode node, String path) {
        In in = new In(node, path);
        Execution v = new Execution(in.str("attacker_perspective"), in.strList("payloads", true),
                in.strList("instructions", true), in.str("observed_result"));
        in.done();
        return v;
    }

    private static ObjectNode writeCodeChange(CodeChange v, String path) {
        Out out = new Out(path);
        out.str("file_name", v.fileName());
        out.str("fixed_code", v.fixedCode());
        return out.node;
    }

    private static CodeChange readCodeChange(JsonNode node, String path) {
        In in = new In(node, path);
        CodeChange v = new CodeChange(in.str("file_name"), in.str("fixed_code"));
        in.done();
        return v;
    }

    private static ObjectNode writeRemediation(Remediation v, String path) {
        Out out = new Out(path);
        out.str("strategy", v.strategy());
        out.list("code_changes", v.codeChanges(), FindingCodec::writeCodeChange, false);
        return out.node;
    }

    private static Remediation readRemediation(JsonNode node, String path) {
        In in = new In(node, path);
        Remediation v = new Remediation(in.str("strategy"),
                in.list("code_changes", FindingCodec::readCodeChange, false));
        in.done();
        return v;
    }

    private static ObjectNode writeScored(Scored v, String path) {
        Out out = new Out(path);
        out.enumValue("score", v.score());
        out.str("reason", v.reason());
        return out.node;
    }

    private static Scored readScored(JsonNode node, String path) {
        In in = new In(node, path);
        Scored v = new Scored(in.enumValue("score", Finding.Score5.class), in.str("reason"));
        in.done();
        return v;
    }

    private static ObjectNode writeSeverity(Severity v, String path) {
        Out out = new Out(path);
        out.obj("likelihood", v.likelihood(), FindingCodec::writeScored);
        out.obj("impact", v.impact(), FindingCodec::writeScored);
        out.enumValue("overall_severity", v.overallSeverity());
        return out.node;
    }

    private static Severity readSeverity(JsonNode node, String path) {
        In in = new In(node, path);
        Severity v = new Severity(in.obj("likelihood", FindingCodec::readScored),
                in.obj("impact", FindingCodec::readScored),
                in.enumValue("overall_severity", Finding.Score5.class));
        in.done();
        return v;
    }

    private static ObjectNode writeConfidence(Confidence v, String path) {
        Out out = new Out(path);
        out.enumValue("score", v.score());
        out.str("reason", v.reason());
        return out.node;
    }

    private static Confidence readConfidence(JsonNode node, String path) {
        In in = new In(node, path);
        Confidence v = new Confidence(in.enumValue("score", Finding.Level3.class), in.str("reason"));
        in.done();
        return v;
    }

    private static ObjectNode writeValidationPlan(ValidationPlan v, String path) {
        Out out = new Out(path);
        out.optStr("local", v.local());
        out.optStr("deployment", v.deployment());
        return out.node;
    }

    private static ValidationPlan readValidationPlan(JsonNode node, String path) {
        In in = new In(node, path);
        ValidationPlan v = new ValidationPlan(in.optStr("local"), in.optStr("deployment"));
        in.done();
        return v;
    }

    private static ObjectNode writeToolSource(ToolSource v, String path) {
        Out out = new Out(path);
        out.str("tool", v.tool());
        out.str("rule_id", v.ruleId());
        out.optEnum("tool_severity", v.toolSeverity());
        out.optStr("file", v.file());
        out.optInt("line", v.line());
        return out.node;
    }

    private static ToolSource readToolSource(JsonNode node, String path) {
        In in = new In(node, path);
        ToolSource v = new ToolSource(in.str("tool"), in.str("rule_id"),
                in.optEnum("tool_severity", Finding.Score5.class), in.optStr("file"), in.optInt("line"));
        in.done();
        return v;
    }

    private static ObjectNode writeDependency(DependencyRef v, String path) {
        Out out = new Out(path);
        out.str("purl", v.purl());
        out.str("group", v.group());
        out.str("artifact", v.artifact());
        out.str("version", v.version());
        out.optStr("fixed_version", v.fixedVersion());
        out.optStr("scope", v.scope());
        out.strList("dependency_path", v.dependencyPath(), false);
        out.enumValue("reachable", v.reachable());
        return out.node;
    }

    private static DependencyRef readDependency(JsonNode node, String path) {
        In in = new In(node, path);
        DependencyRef v = new DependencyRef(in.str("purl"), in.str("group"), in.str("artifact"), in.str("version"),
                in.optStr("fixed_version"), in.optStr("scope"), in.strList("dependency_path", false),
                in.optEnum("reachable", DependencyRef.Reachability.class)
                        .orElse(DependencyRef.Reachability.UNKNOWN));
        in.done();
        return v;
    }

    private static ObjectNode writeTestEvidence(TestEvidence v, String path) {
        Out out = new Out(path);
        out.str("id", v.id());
        out.enumValue("phase", v.phase());
        out.optStr("test_file", v.testFile());
        out.optStr("test_class", v.testClass());
        out.enumValue("outcome", v.outcome());
        out.integer("runs", v.runs());
        out.optStr("observed_result", v.observedResult());
        out.optStr("blocker", v.blocker());
        out.optStr("log_artifact", v.logArtifact());
        return out.node;
    }

    private static TestEvidence readTestEvidence(JsonNode node, String path) {
        In in = new In(node, path);
        TestEvidence v = new TestEvidence(in.str("id"), in.enumValue("phase", TestEvidence.Phase.class),
                in.optStr("test_file"), in.optStr("test_class"), in.enumValue("outcome", TestEvidence.Outcome.class),
                in.integer("runs"), in.optStr("observed_result"), in.optStr("blocker"), in.optStr("log_artifact"));
        in.done();
        return v;
    }

    private static ObjectNode writeAdequacy(Adequacy v, String path) {
        Out out = new Out(path);
        out.integer("changed_lines_total", v.changedLinesTotal());
        out.integer("changed_lines_covered", v.changedLinesCovered());
        out.bool("adequate", v.adequate());
        out.optStr("note", v.note());
        return out.node;
    }

    private static Adequacy readAdequacy(JsonNode node, String path) {
        In in = new In(node, path);
        Adequacy v = new Adequacy(in.integer("changed_lines_total"), in.integer("changed_lines_covered"),
                in.bool("adequate"), in.optStr("note"));
        in.done();
        return v;
    }

    private static ObjectNode writeFixAttempt(FixAttempt v, String path) {
        Out out = new Out(path);
        out.integer("attempt", v.attempt());
        out.optStr("patch_artifact", v.patchArtifact());
        out.optStr("strategy", v.strategy());
        out.enumValue("outcome", v.outcome());
        out.optBool("regression_test_passed", v.regressionTestPassed());
        out.optBool("suite_passed", v.suitePassed());
        out.optBool("rescan_clean", v.rescanClean());
        out.strList("new_fingerprints", v.newFingerprints(), false);
        out.optObj("adequacy", v.adequacy(), FindingCodec::writeAdequacy);
        return out.node;
    }

    private static FixAttempt readFixAttempt(JsonNode node, String path) {
        In in = new In(node, path);
        FixAttempt v = new FixAttempt(in.integer("attempt"), in.optStr("patch_artifact"), in.optStr("strategy"),
                in.enumValue("outcome", FixAttempt.Outcome.class), in.optBool("regression_test_passed"),
                in.optBool("suite_passed"), in.optBool("rescan_clean"), in.strList("new_fingerprints", false),
                in.optObj("adequacy", FindingCodec::readAdequacy));
        in.done();
        return v;
    }

    private static ObjectNode writeVerifierRef(VerifierRef v, String path) {
        Out out = new Out(path);
        out.enumValue("kind", v.kind());
        out.optStr("agent_id", v.agentId());
        out.optStr("provider", v.provider());
        out.optStr("model", v.model());
        out.optEnum("tier", v.tier());
        out.optStr("rule", v.rule());
        return out.node;
    }

    private static VerifierRef readVerifierRef(JsonNode node, String path) {
        In in = new In(node, path);
        VerifierRef v = new VerifierRef(in.enumValue("kind", VerifierRef.Kind.class), in.optStr("agent_id"),
                in.optStr("provider"), in.optStr("model"), in.optEnum("tier", VerifierRef.CapabilityTier.class),
                in.optStr("rule"));
        in.done();
        return v;
    }

    private static ObjectNode writeVerification(Verification v, String path) {
        Out out = new Out(path);
        out.optObj("disprover", v.disprover(), FindingCodec::writeVerifierRef);
        out.optObj("record_verifier", v.recordVerifier(), FindingCodec::writeVerifierRef);
        out.optObj("second_verifier", v.secondVerifier(), FindingCodec::writeVerifierRef);
        out.bool("guardrail_applied", v.guardrailApplied());
        return out.node;
    }

    private static Verification readVerification(JsonNode node, String path) {
        In in = new In(node, path);
        Verification v = new Verification(in.optObj("disprover", FindingCodec::readVerifierRef),
                in.optObj("record_verifier", FindingCodec::readVerifierRef),
                in.optObj("second_verifier", FindingCodec::readVerifierRef),
                in.optBool("guardrail_applied").orElse(false));
        in.done();
        return v;
    }

    private static ObjectNode writeSecPipe(SecPipeExt v, String path) {
        Out out = new Out(path);
        out.enumValue("category", v.category());
        out.enumValue("origin", v.origin());
        out.list("tool_sources", v.toolSources(), FindingCodec::writeToolSource, true);
        out.strList("cwe", v.cwe(), true);
        out.strList("cve", v.cve(), false);
        out.optStr("owasp", v.owasp());
        out.optObj("dependency", v.dependency(), FindingCodec::writeDependency);
        out.optEnum("tool_severity_hint", v.toolSeverityHint());
        out.optEnum("confidence_hint", v.confidenceHint());
        out.list("test_evidence", v.testEvidence(), FindingCodec::writeTestEvidence, false);
        out.list("fix_attempts", v.fixAttempts(), FindingCodec::writeFixAttempt, false);
        out.optEnum("remediation_status", v.remediationStatus());
        out.optStr("recommended_remediation", v.recommendedRemediation());
        out.optObj("verification", v.verification(), FindingCodec::writeVerification);
        out.strList("ledger_units", v.ledgerUnits(), false);
        out.optStr("first_seen_run", v.firstSeenRun());
        out.optStr("carried_forward_from", v.carriedForwardFrom());
        return out.node;
    }

    private static SecPipeExt readSecPipe(JsonNode node, String path) {
        In in = new In(node, path);
        SecPipeExt v = new SecPipeExt(
                in.enumValue("category", Finding.Category.class),
                in.enumValue("origin", Finding.Origin.class),
                in.list("tool_sources", FindingCodec::readToolSource, true),
                in.strList("cwe", true),
                in.strList("cve", false),
                in.optStr("owasp"),
                in.optObj("dependency", FindingCodec::readDependency),
                in.optEnum("tool_severity_hint", Finding.Score5.class),
                in.optEnum("confidence_hint", Finding.Level3.class),
                in.list("test_evidence", FindingCodec::readTestEvidence, false),
                in.list("fix_attempts", FindingCodec::readFixAttempt, false),
                in.optEnum("remediation_status", Finding.RemediationStatus.class),
                in.optStr("recommended_remediation"),
                in.optObj("verification", FindingCodec::readVerification),
                in.strList("ledger_units", false),
                in.optStr("first_seen_run"),
                in.optStr("carried_forward_from"));
        in.done();
        return v;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private static String enumName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String describe(JsonNode node) {
        if (node.isObject()) {
            return "an object";
        }
        if (node.isArray()) {
            return "an array";
        }
        if (node.isString()) {
            return "a string";
        }
        if (node.isNumber()) {
            return "a number";
        }
        if (node.isBoolean()) {
            return "a boolean";
        }
        return node.isNull() ? "null" : node.getNodeType().toString().toLowerCase(Locale.ROOT);
    }

    /** A writer and reader for one contract type. */
    private record Mapping<T>(Class<T> type, BiFunction<T, String, ? extends JsonNode> writer,
                              BiFunction<JsonNode, String, ? extends T> reader) {
        JsonNode writeUnchecked(Object value) {
            return writer.apply(type.cast(value), "");
        }
    }

    /** Builds one JSON object; rejects {@code null} components with the JSON pointer of the property. */
    private static final class Out {
        private final ObjectNode node = NODES.objectNode();
        private final String path;

        Out(String path) {
            this.path = path;
        }

        private String at(String key) {
            return path + "/" + key;
        }

        private <T> T required(String key, T value) {
            if (value == null) {
                throw new ContractJsonException(at(key), "required property '" + key + "' is null");
            }
            return value;
        }

        void str(String key, String value) {
            node.put(key, required(key, value));
        }

        void optStr(String key, Optional<String> value) {
            required(key, value).ifPresent(v -> node.put(key, v));
        }

        void integer(String key, int value) {
            node.put(key, value);
        }

        void optInt(String key, Optional<Integer> value) {
            required(key, value).ifPresent(v -> node.put(key, v.intValue()));
        }

        void bool(String key, boolean value) {
            node.put(key, value);
        }

        void optBool(String key, Optional<Boolean> value) {
            required(key, value).ifPresent(v -> node.put(key, v.booleanValue()));
        }

        void enumValue(String key, Enum<?> value) {
            node.put(key, enumName(required(key, value)));
        }

        void optEnum(String key, Optional<? extends Enum<?>> value) {
            required(key, value).ifPresent(v -> node.put(key, enumName(v)));
        }

        <T> void obj(String key, T value, BiFunction<T, String, ObjectNode> writer) {
            node.set(key, writer.apply(required(key, value), at(key)));
        }

        <T> void optObj(String key, Optional<T> value, BiFunction<T, String, ObjectNode> writer) {
            required(key, value).ifPresent(v -> node.set(key, writer.apply(v, at(key))));
        }

        <T> void list(String key, List<T> values, BiFunction<T, String, ObjectNode> writer, boolean always) {
            required(key, values);
            if (values.isEmpty() && !always) {
                return;
            }
            ArrayNode array = node.putArray(key);
            for (int i = 0; i < values.size(); i++) {
                String itemPath = at(key) + "/" + i;
                T item = values.get(i);
                if (item == null) {
                    throw new ContractJsonException(itemPath, "list element is null");
                }
                array.add(writer.apply(item, itemPath));
            }
        }

        void strList(String key, List<String> values, boolean always) {
            required(key, values);
            if (values.isEmpty() && !always) {
                return;
            }
            ArrayNode array = node.putArray(key);
            for (int i = 0; i < values.size(); i++) {
                String item = values.get(i);
                if (item == null) {
                    throw new ContractJsonException(at(key) + "/" + i, "list element is null");
                }
                array.add(item);
            }
        }
    }

    /** Reads one JSON object strictly; {@link #done()} rejects properties no accessor asked for. */
    private static final class In {
        private final JsonNode node;
        private final String path;
        private final Set<String> seen = new TreeSet<>();

        In(JsonNode node, String path) {
            if (node == null || !node.isObject()) {
                throw new ContractJsonException(path,
                        "expected an object but found " + (node == null ? "nothing" : describe(node)));
            }
            this.node = node;
            this.path = path;
        }

        private String at(String key) {
            return path + "/" + key;
        }

        /** The value of {@code key}, or empty if absent; JSON {@code null} is an error. */
        private Optional<JsonNode> value(String key) {
            seen.add(key);
            JsonNode v = node.get(key);
            if (v == null) {
                return Optional.empty();
            }
            if (v.isNull()) {
                throw new ContractJsonException(at(key), "property '" + key + "' must not be null");
            }
            return Optional.of(v);
        }

        private JsonNode requiredValue(String key) {
            return value(key).orElseThrow(
                    () -> new ContractJsonException(at(key), "missing required property '" + key + "'"));
        }

        private static String asText(JsonNode v, String at) {
            if (!v.isString()) {
                throw new ContractJsonException(at, "expected a string but found " + describe(v));
            }
            return v.stringValue();
        }

        private static int asInt(JsonNode v, String at) {
            if (!v.isIntegralNumber() || !v.canConvertToInt()) {
                throw new ContractJsonException(at, "expected a 32-bit integer but found " + describe(v));
            }
            return v.intValue();
        }

        private static boolean asBool(JsonNode v, String at) {
            if (!v.isBoolean()) {
                throw new ContractJsonException(at, "expected a boolean but found " + describe(v));
            }
            return v.booleanValue();
        }

        private static <E extends Enum<E>> E asEnum(JsonNode v, String at, Class<E> type) {
            String text = asText(v, at);
            for (E constant : type.getEnumConstants()) {
                if (enumName(constant).equals(text)) {
                    return constant;
                }
            }
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                allowed.add(enumName(constant));
            }
            throw new ContractJsonException(at, "unknown value '" + text + "'; expected one of " + allowed);
        }

        String str(String key) {
            return asText(requiredValue(key), at(key));
        }

        Optional<String> optStr(String key) {
            return value(key).map(v -> asText(v, at(key)));
        }

        int integer(String key) {
            return asInt(requiredValue(key), at(key));
        }

        Optional<Integer> optInt(String key) {
            return value(key).map(v -> asInt(v, at(key)));
        }

        boolean bool(String key) {
            return asBool(requiredValue(key), at(key));
        }

        Optional<Boolean> optBool(String key) {
            return value(key).map(v -> asBool(v, at(key)));
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type) {
            return asEnum(requiredValue(key), at(key), type);
        }

        <E extends Enum<E>> Optional<E> optEnum(String key, Class<E> type) {
            return value(key).map(v -> asEnum(v, at(key), type));
        }

        <T> T obj(String key, BiFunction<JsonNode, String, T> reader) {
            return reader.apply(requiredValue(key), at(key));
        }

        <T> Optional<T> optObj(String key, BiFunction<JsonNode, String, T> reader) {
            return value(key).map(v -> reader.apply(v, at(key)));
        }

        private <T> List<T> elements(String key, boolean required, BiFunction<JsonNode, String, T> reader) {
            Optional<JsonNode> v = required ? Optional.of(requiredValue(key)) : value(key);
            if (v.isEmpty()) {
                return List.of();
            }
            JsonNode array = v.get();
            if (!array.isArray()) {
                throw new ContractJsonException(at(key), "expected an array but found " + describe(array));
            }
            List<T> out = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                String itemPath = at(key) + "/" + i;
                JsonNode item = array.get(i);
                if (item.isNull()) {
                    throw new ContractJsonException(itemPath, "list element must not be null");
                }
                out.add(reader.apply(item, itemPath));
            }
            return List.copyOf(out);
        }

        <T> List<T> list(String key, BiFunction<JsonNode, String, T> reader, boolean required) {
            return elements(key, required, reader);
        }

        List<String> strList(String key, boolean required) {
            return elements(key, required, In::asText);
        }

        void done() {
            Set<String> unknown = new TreeSet<>(node.propertyNames());
            unknown.removeAll(seen);
            if (!unknown.isEmpty()) {
                String first = unknown.iterator().next();
                throw new ContractJsonException(at(first), "unknown property '" + first + "'");
            }
        }
    }
}
