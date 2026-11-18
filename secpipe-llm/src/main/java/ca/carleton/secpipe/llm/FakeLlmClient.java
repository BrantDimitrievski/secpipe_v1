package ca.carleton.secpipe.llm;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.finding.Finding.VerifierRef.CapabilityTier;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import ca.carleton.secpipe.contracts.spi.Budget;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Deterministic, network-free {@code LlmClient} for CI and every agent test ({@code --llm-provider fake}).
 *
 * <h2>Fixture layout</h2>
 * <pre>
 * &lt;dir&gt;/&lt;role&gt;/&lt;case&gt;.json      role = AgentRole in lower_snake_case, e.g. hunter, record_verifier
 * </pre>
 * Sub-directories that are not role names are ignored, so one {@code llm-fixtures/} tree can hold other folders.
 *
 * <h2>Case file</h2>
 * <pre>{@code
 * {
 *   "description": "optional, free text",
 *   "match": {                                  // optional; all given conditions must hold; absent = match all
 *     "promptTemplateId": "hunter.v1",          // exact match on Request.promptTemplateId
 *     "userPromptContains": ["UserDao.java"],   // every string must occur in Request.userPrompt
 *     "systemPromptContains": ["hunter"]        // every string must occur in Request.systemPrompt
 *   },
 *   "responses": [                              // required, non-empty; one per call, the last one repeats
 *     {"json": {"candidates": []}},             // a JSON value, returned compactly serialized
 *     {"text": "not json at all"}               // raw text, returned verbatim (use it to test discards)
 *   ],
 *   "error": "simulated outage",                // optional; instead of responses: complete() throws
 *                                               //   LlmUnavailableException with this message
 *   "inputTokens": 120, "outputTokens": 40      // optional, default 0
 * }
 * }</pre>
 * Cases for a role are tried in file-name order; the first match wins (name a catch-all {@code zz-default.json}).
 * No match throws {@code LlmUnavailableException} listing the cases loaded for the role. Unknown keys in a case
 * file are rejected when loading, so typos fail fast.
 *
 * <p>With {@link #withBudget} each call first takes one call from the budget, like a real provider attempt.
 * {@link #calls()} records every request for assertions.
 */
public final class FakeLlmClient implements SecPipeLlmClient {

    /** One recorded call. {@code caseName} is the file name without {@code .json}, or empty if nothing matched. */
    public record Call(AgentRole role, Request request, Optional<String> caseName) { }

    private record Reply(String text) { }

    private record Case(String name, Optional<String> templateId, List<String> userContains,
                        List<String> systemContains, List<Reply> responses, Optional<String> error,
                        long inputTokens, long outputTokens) {

        boolean matches(Request r) {
            if (templateId.isPresent() && !templateId.get().equals(r.promptTemplateId())) {
                return false;
            }
            for (String s : userContains) {
                if (r.userPrompt() == null || !r.userPrompt().contains(s)) {
                    return false;
                }
            }
            for (String s : systemContains) {
                if (r.systemPrompt() == null || !r.systemPrompt().contains(s)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Set<String> CASE_KEYS =
            Set.of("description", "match", "responses", "error", "inputTokens", "outputTokens");
    private static final Set<String> MATCH_KEYS = Set.of("promptTemplateId", "userPromptContains",
            "systemPromptContains");
    private static final Set<String> REPLY_KEYS = Set.of("json", "text");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<AgentRole, List<Case>> cases;
    private final Optional<Budget> budget;
    private final Map<String, Integer> served;
    private final List<Call> calls;
    private final UsageLedger usage;

    private FakeLlmClient(Map<AgentRole, List<Case>> cases, Optional<Budget> budget, Map<String, Integer> served,
                          List<Call> calls, UsageLedger usage) {
        this.cases = cases;
        this.budget = budget;
        this.served = served;
        this.calls = calls;
        this.usage = usage;
    }

    /**
     * Loads every {@code <role>/<case>.json} under {@code dir}. A missing directory gives a client with no cases.
     *
     * @throws IllegalArgumentException if a case file is malformed
     * @throws UncheckedIOException if the directory cannot be read
     */
    public static FakeLlmClient fromDir(Path dir) {
        Map<AgentRole, List<Case>> byRole = new EnumMap<>(AgentRole.class);
        for (AgentRole role : AgentRole.values()) {
            Path roleDir = dir.resolve(roleDir(role));
            List<Case> list = new ArrayList<>();
            if (Files.isDirectory(roleDir)) {
                try (Stream<Path> files = Files.list(roleDir)) {
                    for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        list.add(parse(f));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read fake LLM fixtures in " + roleDir, e);
                }
            }
            byRole.put(role, List.copyOf(list));
        }
        return new FakeLlmClient(Collections.unmodifiableMap(byRole), Optional.empty(),
                Collections.synchronizedMap(new HashMap<>()), Collections.synchronizedList(new ArrayList<>()),
                new UsageLedger(Redactor.shapesOnly()));
    }

    /** A copy that takes one budget call per {@link #complete}; fixtures, call log and usage are shared. */
    public FakeLlmClient withBudget(Budget b) {
        return new FakeLlmClient(cases, Optional.of(b), served, calls, usage);
    }

    /** The fixture directory name for a role (lower_snake_case). */
    public static String roleDir(AgentRole role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    /** Case names loaded for a role, in matching order. */
    public List<String> cases(AgentRole role) {
        return cases.get(role).stream().map(Case::name).toList();
    }

    /** Every call so far, in order. */
    public List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    @Override
    public ProviderMode mode() {
        return ProviderMode.FAKE;
    }

    /** {@code fake-<role>}, STRONG for strong-by-default roles, else STANDARD. */
    @Override
    public ModelInfo modelFor(AgentRole role) {
        return new ModelInfo(ProviderMode.FAKE, "fake-" + roleDir(role),
                role.strongByDefault() ? CapabilityTier.STRONG : CapabilityTier.STANDARD);
    }

    @Override
    public Response complete(AgentRole role, Request request) throws LlmUnavailableException, BudgetExhaustedException {
        if (budget.isPresent() && !budget.get().tryAcquire(role)) {
            throw new BudgetExhaustedException("agent-call budget exhausted (" + budget.get().used() + "/"
                    + budget.get().max() + " calls); hint: raise --max-agent-calls");
        }
        usage.recordCall(role, false);
        Optional<Case> hit = cases.get(role).stream().filter(c -> c.matches(request)).findFirst();
        calls.add(new Call(role, request, hit.map(Case::name)));
        ModelInfo model = modelFor(role);
        if (hit.isEmpty()) {
            String msg = "fake LLM: no fixture matched for role " + roleDir(role) + " (template "
                    + request.promptTemplateId() + "); loaded cases: " + cases(role);
            usage.recordFailure(role, model.modelId(), 1, UsageLedger.Outcome.FATAL_ERROR, msg);
            throw new LlmUnavailableException(msg, null);
        }
        Case c = hit.get();
        if (c.error().isPresent()) {
            usage.recordFailure(role, model.modelId(), 1, UsageLedger.Outcome.FATAL_ERROR, c.error().get());
            throw new LlmUnavailableException(c.error().get(), null);
        }
        String key = roleDir(role) + "/" + c.name();
        int index;
        synchronized (served) {
            index = served.merge(key, 1, Integer::sum) - 1;
        }
        Reply reply = c.responses().get(Math.min(index, c.responses().size() - 1));
        usage.recordSuccess(role, model.modelId(), 1, c.inputTokens(), c.outputTokens());
        return new Response(reply.text(), model, c.inputTokens(), c.outputTokens(), 1, Optional.empty());
    }

    @Override
    public ProviderMode requestedMode() {
        return ProviderMode.FAKE;
    }

    @Override
    public List<RunMetadata.Fallback> fallbacks() {
        return List.of();
    }

    @Override
    public UsageLedger usage() {
        return usage;
    }

    private static Case parse(Path file) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".json".length());
        JsonNode root;
        try {
            root = JSON.readTree(file);
        } catch (JacksonException e) {
            throw bad(file, "not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw bad(file, "top level must be a JSON object");
        }
        checkKeys(file, root, CASE_KEYS, "case");
        Optional<String> templateId = Optional.empty();
        List<String> user = List.of();
        List<String> system = List.of();
        Optional<JsonNode> match = root.optional("match");
        if (match.isPresent()) {
            JsonNode m = match.get();
            if (!m.isObject()) {
                throw bad(file, "\"match\" must be an object");
            }
            checkKeys(file, m, MATCH_KEYS, "match");
            templateId = m.optional("promptTemplateId").map(n -> string(file, n, "promptTemplateId"));
            user = strings(file, m.optional("userPromptContains"), "userPromptContains");
            system = strings(file, m.optional("systemPromptContains"), "systemPromptContains");
        }
        Optional<String> error = root.optional("error").map(n -> string(file, n, "error"));
        List<Reply> replies = new ArrayList<>();
        Optional<JsonNode> responses = root.optional("responses");
        if (responses.isPresent()) {
            if (!responses.get().isArray()) {
                throw bad(file, "\"responses\" must be an array");
            }
            for (JsonNode r : responses.get()) {
                replies.add(reply(file, r));
            }
        }
        if (replies.isEmpty() && error.isEmpty()) {
            throw bad(file, "needs a non-empty \"responses\" array or an \"error\"");
        }
        return new Case(name, templateId, user, system, List.copyOf(replies), error,
                tokens(file, root, "inputTokens"), tokens(file, root, "outputTokens"));
    }

    private static Reply reply(Path file, JsonNode r) {
        if (!r.isObject() || r.propertyNames().size() != 1) {
            throw bad(file, "each response must be {\"json\": ...} or {\"text\": \"...\"}");
        }
        checkKeys(file, r, REPLY_KEYS, "response");
        Optional<JsonNode> text = r.optional("text");
        if (text.isPresent()) {
            return new Reply(string(file, text.get(), "text"));
        }
        return new Reply(JSON.writeValueAsString(r.get("json")));
    }

    private static void checkKeys(Path file, JsonNode node, Set<String> allowed, String what) {
        for (String k : node.propertyNames()) {
            if (!allowed.contains(k)) {
                throw bad(file, "unknown " + what + " key \"" + k + "\" (allowed: " + allowed + ")");
            }
        }
    }

    private static String string(Path file, JsonNode n, String field) {
        if (!n.isString()) {
            throw bad(file, "\"" + field + "\" must be a string");
        }
        return n.stringValue();
    }

    private static List<String> strings(Path file, Optional<JsonNode> n, String field) {
        if (n.isEmpty()) {
            return List.of();
        }
        if (!n.get().isArray()) {
            throw bad(file, "\"" + field + "\" must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode e : n.get()) {
            out.add(string(file, e, field));
        }
        return List.copyOf(out);
    }

    private static long tokens(Path file, JsonNode root, String field) {
        Optional<JsonNode> n = root.optional(field);
        if (n.isEmpty()) {
            return 0;
        }
        if (!n.get().isIntegralNumber() || n.get().longValue() < 0) {
            throw bad(file, "\"" + field + "\" must be a non-negative integer");
        }
        return n.get().longValue();
    }

    private static IllegalArgumentException bad(Path file, String problem) {
        return new IllegalArgumentException("bad fake LLM fixture " + file + ": " + problem);
    }
}
