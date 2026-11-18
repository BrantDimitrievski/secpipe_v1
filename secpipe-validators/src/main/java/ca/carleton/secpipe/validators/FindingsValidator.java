package ca.carleton.secpipe.validators;

import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.finding.Finding.Confirmed;
import ca.carleton.secpipe.contracts.finding.Finding.FixAttempt;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.finding.Finding.SecPipeExt;
import ca.carleton.secpipe.contracts.finding.Finding.TestEvidence;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.json.ContractJson.ContractJsonException;
import ca.carleton.secpipe.contracts.json.FindingCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Validates {@code findings.json}: the JSON Schema ({@code findings.schema.json}, draft 2020-12, via
 * {@link SchemaRegistry}) plus the deterministic rules F1–F9 of {@code docs/PLAN.md} §4.1 that the schema cannot
 * express.
 *
 * <p>Errors carry a JSON pointer into the findings array ({@code /2/trace/0/file}), the rule code ({@code F1} …
 * {@code F9}, {@code schema.*}, {@code json.*}, {@code limit.*}) and a message naming the fingerprint. A result
 * keeps at most {@value ValidationResult#MAX_ERRORS} errors. Input is capped at {@value #MAX_BYTES} bytes of JSON and
 * {@value #MAX_RECORDS} records. The engine treats any error as a rejected delta.
 *
 * <p>Everything here is deterministic (CLAUDE.md §8): errors are ordered by record index, then by rule.
 */
public final class FindingsValidator {

    /** Largest {@code findings.json} accepted by {@link #validateJson(String)}: 20 MiB of UTF-8. */
    public static final int MAX_BYTES = 20 * 1024 * 1024;

    /** Most records accepted in one findings array. */
    public static final int MAX_RECORDS = 10_000;

    /** The schema name, relative to {@code schemas/}. */
    public static final String SCHEMA = "findings.schema.json";

    private static final Pattern CODE_FINGERPRINT = Pattern.compile("^code:CWE-([0-9]+):[0-9a-f]{24}$");
    private static final Pattern DEP_FINGERPRINT =
            Pattern.compile("^dep:pkg:maven/[A-Za-z0-9._/@+-]+(?::[A-Za-z0-9._/@+-]+)+$");
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final String RESOLVED = "resolved:";

    private FindingsValidator() {
    }

    // ------------------------------------------------------------------------------------------------------------
    // JSON entry points
    // ------------------------------------------------------------------------------------------------------------

    /** Validates {@code findings.json} text without a scanned tree (F7 existence skipped) and with no cap. */
    public static ValidationResult validateJson(String json) {
        return validateJson(json, Optional.empty(), CapPolicy.NONE);
    }

    /**
     * Validates {@code findings.json} text: size limit, JSON syntax, record limit, schema, then (when the schema
     * passes) rules F1–F9. A {@code needs_validation} record that carries {@code severity} is also reported as F5.
     */
    public static ValidationResult validateJson(String json, Optional<Path> root, CapPolicy capPolicy) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(capPolicy, "capPolicy");
        long bytes = utf8Length(json);
        if (bytes > MAX_BYTES) {
            return single("limit.size", "", "findings.json is " + bytes + " bytes; the limit is " + MAX_BYTES);
        }
        JsonNode tree;
        try {
            tree = ContractJson.mapper().readTree(json);
        } catch (JacksonException e) {
            return single("json.malformed", "", "malformed JSON: " + e.getOriginalMessage());
        }
        if (tree == null || tree.isMissingNode()) {
            return single("json.malformed", "", "malformed JSON: empty document");
        }
        if (tree.isArray() && tree.size() > MAX_RECORDS) {
            return single("limit.records", "", tooManyRecords(tree.size()));
        }
        ValidationResult schema = SchemaRegistry.shared().validate(SCHEMA, tree);
        ValidationResult severityOnNv = severityOnNeedsValidation(tree);
        if (!schema.ok() || !severityOnNv.ok()) {
            return schema.merge(severityOnNv);
        }
        List<Finding> findings = new ArrayList<>(tree.size());
        for (int i = 0; i < tree.size(); i++) {
            try {
                findings.add(FindingCodec.findingFromTree(tree.get(i)));
            } catch (ContractJsonException e) {
                return ValidationResult.of(List.of(new ValidationError("json.contract", "/" + i + e.path(),
                        e.problem(), textAt(tree.get(i), "fingerprint"))));
            }
        }
        return validate(findings, root, capPolicy);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Record entry point
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Checks rules F1–F9 on decoded findings. Pointers use the list index. F7 checks file existence only when
     * {@code root} is present; F4 consults {@code capPolicy} for each confirmed record.
     */
    public static ValidationResult validate(List<Finding> findings, Optional<Path> root, CapPolicy capPolicy) {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(capPolicy, "capPolicy");
        if (findings.size() > MAX_RECORDS) {
            return single("limit.records", "", tooManyRecords(findings.size()));
        }
        Errors errors = new Errors();
        Optional<FileTree> tree = root.map(r -> FileTree.open(r, errors));
        Map<String, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < findings.size() && !errors.full(); i++) {
            Finding f = Objects.requireNonNull(findings.get(i), "findings[" + i + "]");
            Record r = new Record(i, f, errors);
            checkUnique(r, firstIndex);
            checkTrace(r);
            checkEvidence(r);
            checkSeverity(r, capPolicy);
            checkSeverityHint(r);
            checkRejectedVerification(r);
            checkFiles(r, tree);
            checkRemediation(r);
            checkFingerprintShape(r);
        }
        return errors.result();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------------------------------------------------

    /** F1. Fingerprints are unique across the array. */
    private static void checkUnique(Record r, Map<String, Integer> firstIndex) {
        Integer first = firstIndex.putIfAbsent(r.fingerprint(), r.index());
        if (first != null) {
            r.error("F1", "/fingerprint", "duplicate fingerprint (first at /" + first + ")");
        }
    }

    /** F2. confirmed: trace starts at an entrypoint and ends at a sink; others end at a sink. */
    private static void checkTrace(Record r) {
        List<Finding.TraceStep> trace = r.finding().trace();
        if (trace.isEmpty()) {
            r.error("F2", "/trace", "trace is empty");
            return;
        }
        if (r.finding() instanceof Confirmed && trace.getFirst().kind() != Finding.TraceKind.ENTRYPOINT) {
            r.error("F2", "/trace/0/kind", "confirmed trace must begin with an entrypoint step, found "
                    + lower(trace.getFirst().kind()));
        }
        int last = trace.size() - 1;
        if (trace.get(last).kind() != Finding.TraceKind.SINK) {
            r.error("F2", "/trace/" + last + "/kind", "trace must end with a sink step, found "
                    + lower(trace.get(last).kind()));
        }
    }

    /** F3. Evidence behind a confirmed verdict: a failing pre-fix test (code) or a resolved dependency. */
    private static void checkEvidence(Record r) {
        if (!(r.finding() instanceof Confirmed c)) {
            return;
        }
        SecPipeExt ext = c.secpipe();
        String observed = c.execution().observedResult();
        if (ext.category() == Finding.Category.DEPENDENCY) {
            if (ext.dependency().isEmpty()) {
                r.error("F3", "/secpipe", "confirmed dependency finding needs secpipe.dependency");
            }
            if (!observed.startsWith(RESOLVED)) {
                r.error("F3", "/execution/observed_result",
                        "confirmed dependency finding needs an observed_result beginning '" + RESOLVED + "'");
            }
            return;
        }
        List<String> ids = ext.testEvidence().stream()
                .filter(t -> t.phase() == TestEvidence.Phase.PRE_FIX
                        && t.outcome() == TestEvidence.Outcome.FAILED_AS_EXPECTED)
                .map(TestEvidence::id)
                .toList();
        if (ids.isEmpty()) {
            r.error("F3", "/secpipe/test_evidence",
                    "confirmed code finding needs test evidence with phase=pre_fix and outcome=failed_as_expected");
        } else if (ids.stream().noneMatch(observed::startsWith)) {
            r.error("F3", "/execution/observed_result", "observed_result must start with the id of its pre-fix "
                    + "test evidence (" + String.join(" or ", ids) + ")");
        }
    }

    /** F4. overall_severity &le; max(likelihood, impact) and &le; the policy cap. */
    private static void checkSeverity(Record r, CapPolicy capPolicy) {
        if (!(r.finding() instanceof Confirmed c)) {
            return;
        }
        Finding.Severity s = c.severity();
        Score5 overall = s.overallSeverity();
        Score5 max = s.likelihood().score().compareTo(s.impact().score()) >= 0
                ? s.likelihood().score() : s.impact().score();
        if (overall.compareTo(max) > 0) {
            r.error("F4", "/severity/overall_severity", "overall_severity " + lower(overall)
                    + " exceeds max(likelihood, impact) = " + lower(max));
        }
        Optional<Score5> cap = Objects.requireNonNull(capPolicy.cap(c), "CapPolicy.cap returned null");
        if (cap.isPresent() && overall.compareTo(cap.get()) > 0) {
            r.error("F4", "/severity/overall_severity", "overall_severity " + lower(overall)
                    + " exceeds the scoring cap " + lower(cap.get()));
        }
    }

    /**
     * F5. tool_severity_hint only on needs_validation. (A needs_validation record cannot carry severity in the
     * record model; in JSON that case is caught by {@link #severityOnNeedsValidation(JsonNode)}.)
     */
    private static void checkSeverityHint(Record r) {
        if (r.finding().verdict() != Finding.Verdict.NEEDS_VALIDATION
                && r.finding().secpipe().toolSeverityHint().isPresent()) {
            r.error("F5", "/secpipe/tool_severity_hint", "tool_severity_hint is allowed only on needs_validation, not "
                    + lower(r.finding().verdict()));
        }
    }

    /** F6. A tool-origin rejection needs a disprover; a small-tier LLM disprover is never enough on its own. */
    private static void checkRejectedVerification(Record r) {
        if (!(r.finding() instanceof Finding.Rejected rej) || rej.secpipe().origin() != Finding.Origin.TOOL) {
            return;
        }
        Optional<Finding.Verification> v = rej.secpipe().verification();
        Optional<VerifierRef> disprover = v.flatMap(Finding.Verification::disprover);
        if (disprover.isEmpty()) {
            r.error("F6", "/secpipe/verification",
                    "rejected tool finding needs secpipe.verification.disprover");
            return;
        }
        if (!isSmallLlm(disprover.get())) {
            return;
        }
        boolean strongSecond = v.get().secondVerifier()
                .filter(s -> s.kind() == VerifierRef.Kind.DETERMINISTIC || isStrongOrStandard(s)).isPresent();
        boolean deterministicRecordVerifier = v.get().recordVerifier()
                .filter(s -> s.kind() == VerifierRef.Kind.DETERMINISTIC).isPresent();
        if (!strongSecond && !deterministicRecordVerifier) {
            r.error("F6", "/secpipe/verification/disprover", "a small-tier LLM disprover cannot be the sole reason "
                    + "a tool finding is rejected; add a strong/standard second_verifier or a deterministic verifier");
        }
    }

    /** F7. Every file is repo-relative without '..' and, with a root, exists in the scanned tree. */
    private static void checkFiles(Record r, Optional<FileTree> tree) {
        List<Finding.TraceStep> trace = r.finding().trace();
        for (int j = 0; j < trace.size(); j++) {
            checkFile(r, tree, "/trace/" + j + "/file", trace.get(j).file());
        }
        List<Finding.Evidence> evidence = r.finding().evidence();
        for (int j = 0; j < evidence.size(); j++) {
            checkFile(r, tree, "/evidence/" + j + "/file", evidence.get(j).file());
        }
        List<Finding.ToolSource> sources = r.finding().secpipe().toolSources();
        for (int j = 0; j < sources.size(); j++) {
            int k = j;
            sources.get(j).file().ifPresent(file ->
                    checkFile(r, tree, "/secpipe/tool_sources/" + k + "/file", file));
        }
    }

    private static void checkFile(Record r, Optional<FileTree> tree, String pointer, String file) {
        Optional<String> problem = relativePathProblem(file);
        if (problem.isPresent()) {
            r.error("F7", pointer, "file '" + file + "' " + problem.get());
        } else if (tree.isPresent() && !tree.get().exists(file)) {
            r.error("F7", pointer, "file '" + file + "' does not exist in the scanned tree");
        }
    }

    private static Optional<String> relativePathProblem(String file) {
        if (file.isEmpty()) {
            return Optional.of("is empty");
        }
        if (file.indexOf('\0') >= 0) {
            return Optional.of("contains a NUL character");
        }
        if (file.startsWith("/") || file.startsWith("\\") || DRIVE.matcher(file).matches()) {
            return Optional.of("is not repo-relative");
        }
        for (String segment : file.split("[/\\\\]", -1)) {
            if (segment.equals("..")) {
                return Optional.of("contains a '..' segment");
            }
        }
        return Optional.empty();
    }

    /** F8. remediation_status=fixed needs a last fix attempt that is fixed and fully verified. */
    private static void checkRemediation(Record r) {
        SecPipeExt ext = r.finding().secpipe();
        if (ext.remediationStatus().orElse(null) != Finding.RemediationStatus.FIXED) {
            return;
        }
        List<FixAttempt> attempts = ext.fixAttempts();
        if (attempts.isEmpty()) {
            r.error("F8", "/secpipe/remediation_status", "remediation_status=fixed needs at least one fix attempt");
            return;
        }
        int lastIndex = attempts.size() - 1;
        FixAttempt last = attempts.get(lastIndex);
        String pointer = "/secpipe/fix_attempts/" + lastIndex;
        List<String> missing = new ArrayList<>();
        if (last.outcome() != FixAttempt.Outcome.FIXED) {
            missing.add("outcome=fixed (was " + lower(last.outcome()) + ")");
        }
        if (!last.regressionTestPassed().orElse(false)) {
            missing.add("regression_test_passed=true");
        }
        if (!last.suitePassed().orElse(false)) {
            missing.add("suite_passed=true");
        }
        if (!last.rescanClean().orElse(false)) {
            missing.add("rescan_clean=true");
        }
        if (!missing.isEmpty()) {
            r.error("F8", pointer, "remediation_status=fixed but the last fix attempt lacks "
                    + String.join(", ", missing));
        }
    }

    /** F9. The fingerprint matches the ADR-06 shape for the record's category. */
    private static void checkFingerprintShape(Record r) {
        String fp = r.fingerprint();
        if (r.finding().secpipe().category() == Finding.Category.DEPENDENCY) {
            if (!DEP_FINGERPRINT.matcher(fp).matches()) {
                r.error("F9", "/fingerprint", "dependency fingerprint must look like dep:pkg:maven/<group>/"
                        + "<artifact>@<version>:<vuln-id>");
            }
            return;
        }
        Matcher m = CODE_FINGERPRINT.matcher(fp);
        if (!m.matches()) {
            r.error("F9", "/fingerprint", "code fingerprint must look like code:CWE-<family>:<24 lower-case hex>");
            return;
        }
        String cwe = "CWE-" + m.group(1);
        String family = Fingerprints.cweFamily(cwe);
        if (!family.equals(cwe)) {
            r.error("F9", "/fingerprint", "code fingerprint must use the CWE family root " + family
                    + ", not " + cwe);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // JSON-level F5
    // ------------------------------------------------------------------------------------------------------------

    private static ValidationResult severityOnNeedsValidation(JsonNode tree) {
        if (!tree.isArray()) {
            return ValidationResult.valid();
        }
        List<ValidationError> errors = new ArrayList<>();
        for (int i = 0; i < tree.size() && errors.size() < ValidationResult.MAX_ERRORS; i++) {
            JsonNode item = tree.get(i);
            if (item.isObject() && "needs_validation".equals(textAt(item, "verdict").orElse(null))
                    && item.has("severity")) {
                Optional<String> fp = textAt(item, "fingerprint");
                errors.add(new ValidationError("F5", "/" + i + "/severity",
                        withFingerprint(fp, "needs_validation must not carry severity"), fp));
            }
        }
        return ValidationResult.of(errors);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private static boolean isSmallLlm(VerifierRef ref) {
        return ref.kind() == VerifierRef.Kind.LLM && ref.tier().orElse(null) == VerifierRef.CapabilityTier.SMALL;
    }

    private static boolean isStrongOrStandard(VerifierRef ref) {
        return ref.kind() == VerifierRef.Kind.LLM && ref.tier()
                .filter(t -> t == VerifierRef.CapabilityTier.STRONG || t == VerifierRef.CapabilityTier.STANDARD)
                .isPresent();
    }

    private static Optional<String> textAt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? Optional.of(value.stringValue()) : Optional.empty();
    }

    private static String withFingerprint(Optional<String> fingerprint, String message) {
        return fingerprint.map(fp -> fp + ": " + message).orElse(message);
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String tooManyRecords(int n) {
        return "findings.json has " + n + " records; the limit is " + MAX_RECORDS;
    }

    private static ValidationResult single(String code, String path, String message) {
        return ValidationResult.of(List.of(ValidationError.of(code, path, message)));
    }

    /** UTF-8 byte length without copying the string. */
    private static long utf8Length(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /** Collects errors up to the cap. */
    private static final class Errors {
        private final List<ValidationError> list = new ArrayList<>();

        void add(ValidationError error) {
            if (!full()) {
                list.add(error);
            }
        }

        boolean full() {
            return list.size() >= ValidationResult.MAX_ERRORS;
        }

        ValidationResult result() {
            return ValidationResult.of(list);
        }
    }

    /** One record under validation: reports errors with its pointer prefix and fingerprint. */
    private record Record(int index, Finding finding, Errors errors) {
        String fingerprint() {
            return finding.fingerprint();
        }

        void error(String rule, String pointer, String message) {
            errors.add(new ValidationError(rule, "/" + index + pointer, fingerprint() + ": " + message,
                    Optional.of(fingerprint())));
        }
    }

    /** The scanned tree for F7 existence checks; symlinks may not escape the root. */
    private static final class FileTree {
        private final Optional<Path> realRoot;
        private final Map<String, Boolean> cache = new HashMap<>();

        private FileTree(Optional<Path> realRoot) {
            this.realRoot = realRoot;
        }

        static FileTree open(Path root, Errors errors) {
            try {
                Path real = root.toRealPath();
                if (Files.isDirectory(real)) {
                    return new FileTree(Optional.of(real));
                }
            } catch (IOException e) {
                // Reported below.
            }
            errors.add(ValidationError.of("F7", "", "scan root '" + root + "' is not a readable directory"));
            return new FileTree(Optional.empty());
        }

        boolean exists(String file) {
            if (realRoot.isEmpty()) {
                return true; // Root problem already reported once.
            }
            return cache.computeIfAbsent(file, this::check);
        }

        private boolean check(String file) {
            Path root = realRoot.get();
            Path candidate = root.resolve(file.replace('\\', '/')).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return false;
            }
            try {
                return candidate.toRealPath().startsWith(root);
            } catch (IOException e) {
                return false;
            }
        }
    }
}
