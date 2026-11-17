package ca.carleton.secpipe.contracts.config;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.SecPipeConfig.FixMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig.GraphEngine;
import ca.carleton.secpipe.contracts.config.SecPipeConfig.OutputFormat;
import ca.carleton.secpipe.contracts.config.SecPipeConfig.ScopeMode;
import ca.carleton.secpipe.contracts.config.SecPipeConfig.Verbosity;
import ca.carleton.secpipe.contracts.finding.Finding;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Builds the one {@link SecPipeConfig} for a run (WO-C.4, FR-2, NFR-1, NFR-5).
 *
 * <p>Layers, lowest to highest: built-in defaults &lt; {@code secpipe.yml} &lt; environment
 * ({@code SECPIPE_<UPPER_SNAKE>}) &lt; explicit overrides (the raw {@code --flag value} map the CLI passes).
 * Every layer uses the same keys: the kebab-case {@code secpipe scan} long option names of PLAN §4.3, listed in
 * {@link #KEYS}. A higher layer replaces a lower layer's value for the same key (lists are not merged).
 *
 * <p>Value syntax in the overrides map and in the environment:
 * <ul>
 *   <li>flags ({@code offline}, {@code no-ai}, ...): {@code ""} (flag present), {@code true|false|1|0|yes|no};</li>
 *   <li>repeatable options ({@code model-for}, {@code model-tier}, {@code deny-path}, {@code disable-node}): one
 *       value per occurrence, joined with {@link #REPEAT_SEPARATOR}; comma lists ({@code tools},
 *       {@code exclude-tools}, {@code format}) may also use commas. {@code deny-path} splits on newlines only,
 *       because globs may contain commas;</li>
 *   <li>everything else: the option's value as typed on the command line.</li>
 * </ul>
 *
 * <p>This class is the only place that computes defaults and implications: profile-dependent
 * {@code max-agent-calls} (40/200/800), {@code offline} &rArr; provider {@code none} unless {@code ollama},
 * {@code no-ai} &rArr; {@code none}, quick profile on a GitHub Actions {@code pull_request} &rArr; {@code diff}
 * scope, {@code enable-fixes=true} &rArr; {@code SUGGEST}, and {@code base-ref} from {@code GITHUB_BASE_REF}.
 * API keys are never configuration: a YAML key or flag that looks like one is rejected (ADR-10). The {@code fake}
 * provider is accepted only when {@code SECPIPE_ALLOW_FAKE_LLM=1}.
 */
public final class ConfigResolver {

    /** Joins the occurrences of a repeatable option in the overrides map and the environment. */
    public static final String REPEAT_SEPARATOR = "\n";

    /** The environment variable that unlocks {@link ProviderMode#FAKE} (CI and tests only). */
    public static final String ALLOW_FAKE_LLM_ENV = "SECPIPE_ALLOW_FAKE_LLM";

    /** Classpath location of the {@code secpipe.yml} schema. */
    public static final String SCHEMA_RESOURCE = "/schemas/config.schema.json";

    /** Every configuration key, in PLAN §4.3 order. {@code target} is the positional {@code PATH}. */
    public static final List<String> KEYS = List.of(
            "target", "out", "profile", "scope", "base-ref", "head-ref", "llm-provider", "model", "model-for",
            "model-tier", "llm-base-url", "tools", "exclude-tools", "max-agent-calls", "max-fix-attempts",
            "max-sandbox-runs", "enable-fixes", "no-sandbox", "offline", "no-ai", "fail-on",
            "fail-on-needs-validation", "fail-on-incomplete", "format", "prior-run", "print-prompts", "deny-path",
            "max-snippet-lines", "max-files-per-call", "disable-node", "include-tests", "allow-build-extensions",
            "accept-codeql-terms", "engine", "tool-timeout", "quiet", "verbose", "config");

    /** {@code config} names the file layer itself, so it is accepted only as an override. */
    private static final String CONFIG_KEY = "config";

    private static final Map<String, String> SHORT_FLAGS = Map.of("-o", "out", "-q", "quiet", "-v", "verbose");

    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(api[-_]?key|token|secret)");

    private static final String ENV_KEY_HINT = "set SECPIPE_API_KEY (or ANTHROPIC_API_KEY, OPENAI_API_KEY, "
            + "COPILOT_GITHUB_TOKEN) in the environment instead; API keys are never read from files or flags";

    private static final String DEFAULT_TARGET = ".";
    private static final String DEFAULT_OUT = "secpipe-out";
    private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern SIMPLE_DURATION = Pattern.compile("([0-9]{1,9})(ms|s|m|h)?");

    private ConfigResolver() {
    }

    /**
     * Resolves the run configuration.
     *
     * @param overrides explicit values from the CLI, keyed by long option name (with or without the leading
     *                  {@code --}; {@code -o}, {@code -q}, {@code -v} are accepted too). Unknown keys are rejected.
     * @param env       the process environment ({@code System.getenv()} in production). Only
     *                  {@code SECPIPE_<KEY>}, {@code SECPIPE_ALLOW_FAKE_LLM}, {@code GITHUB_ACTIONS},
     *                  {@code GITHUB_EVENT_NAME} and {@code GITHUB_BASE_REF} are read; others are ignored.
     * @param yaml      the {@code secpipe.yml} to read, if any. It must exist when given.
     * @return the fully resolved configuration
     * @throws ConfigException with a problem line and a {@code hint:} line for every invalid input
     */
    public static SecPipeConfig resolve(Map<String, String> overrides, Map<String, String> env, Optional<Path> yaml) {
        Map<String, String> environment = env == null ? Map.of() : env;
        Map<String, Value> layers = new LinkedHashMap<>();
        if (yaml != null && yaml.isPresent()) {
            layers.putAll(readYaml(yaml.get()));
        }
        layers.putAll(readEnvironment(environment));
        layers.putAll(readOverrides(overrides == null ? Map.of() : overrides));
        return new Resolution(layers, environment).build();
    }

    /** The environment variable that sets {@code key}, e.g. {@code max-agent-calls -> SECPIPE_MAX_AGENT_CALLS}. */
    public static String envName(String key) {
        return "SECPIPE_" + key.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    // ------------------------------------------------------------------ layers

    /** A raw value plus where it came from, for error messages. */
    private record Value(String raw, String source) {
    }

    private static Map<String, Value> readOverrides(Map<String, String> overrides) {
        Map<String, Value> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(overrides).entrySet()) {
            String given = e.getKey();
            String key = SHORT_FLAGS.getOrDefault(given, given.startsWith("--") ? given.substring(2) : given);
            if (SECRET_KEY.matcher(key).find()) {
                throw new ConfigException("option '--" + key + "' looks like an API key or token, which is never "
                        + "accepted as an option", ENV_KEY_HINT);
            }
            if (!KEYS.contains(key)) {
                throw new ConfigException("unknown option '" + given + "'" + suggestion(key),
                        "run 'secpipe scan --help' for the list of options");
            }
            if (CONFIG_KEY.equals(key)) {
                continue; // the CLI passes the file itself as the yaml argument
            }
            out.put(key, new Value(e.getValue() == null ? "" : e.getValue(), "--" + key));
        }
        return out;
    }

    private static Map<String, Value> readEnvironment(Map<String, String> env) {
        Map<String, Value> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            if (CONFIG_KEY.equals(key)) {
                continue;
            }
            String name = envName(key);
            String raw = env.get(name);
            if (raw != null && !raw.isBlank()) {
                out.put(key, new Value(raw.strip(), "environment variable " + name));
            }
        }
        return out;
    }

    private static Map<String, Value> readYaml(Path file) {
        String where = "config file " + file;
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(where + " does not exist or is not a file",
                    "check the --config path, or omit --config to use defaults");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException | UncheckedIOException e) {
            throw new ConfigException(where + " cannot be read: " + oneLine(e.getMessage()),
                    "check the file's permissions and that it is UTF-8 text");
        }
        JsonNode root;
        try {
            root = Yaml.MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new ConfigException(where + " is not valid YAML: " + oneLine(e.getOriginalMessage()),
                    "fix the YAML syntax (a mapping of option names to values, keys unique)");
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Map.of();
        }
        rejectSecretKeys(root, "", where);
        if (!root.isObject()) {
            throw new ConfigException(where + " must be a mapping of option names to values, found "
                    + root.getNodeType().toString().toLowerCase(Locale.ROOT),
                    "write one 'option-name: value' per line, e.g. 'profile: quick'");
        }
        for (String key : new TreeSet<>(root.propertyNames())) {
            if (!KEYS.contains(key) || CONFIG_KEY.equals(key)) {
                throw new ConfigException(where + ": unknown key '" + key + "'" + suggestion(key),
                        "keys are the kebab-case 'secpipe scan' option names (see schemas/config.schema.json)");
            }
        }
        List<String> errors = new ArrayList<>();
        for (com.networknt.schema.Error error : SchemaHolder.SCHEMA.validate(root)) {
            errors.add(oneLine(error.toString()));
        }
        if (!errors.isEmpty()) {
            Collections.sort(errors);
            String more = errors.size() > 1 ? " (and " + (errors.size() - 1) + " more)" : "";
            throw new ConfigException(where + ": invalid value at " + errors.get(0) + more,
                    "fix the value; allowed values and types are in schemas/config.schema.json");
        }
        Map<String, Value> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            if (!e.getValue().isNull()) {
                out.put(e.getKey(), new Value(flatten(e.getValue()), where + " key '" + e.getKey() + "'"));
            }
        }
        return out;
    }

    private static void rejectSecretKeys(JsonNode node, String path, String where) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (SECRET_KEY.matcher(e.getKey()).find()) {
                    throw new ConfigException(where + ": key '" + childPath + "' looks like an API key, token or "
                            + "secret, which is never accepted in secpipe.yml", ENV_KEY_HINT);
                }
                rejectSecretKeys(e.getValue(), childPath, where);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node.values()) {
                rejectSecretKeys(child, path, where);
            }
        }
    }

    /** Turns a schema-valid YAML value into the same string form the CLI and environment use. */
    private static String flatten(JsonNode node) {
        List<String> parts = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node.values()) {
                parts.add(item.asString());
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                parts.add(e.getKey() + "=" + e.getValue().asString());
            }
        } else {
            parts.add(node.asString());
        }
        return String.join(REPEAT_SEPARATOR, parts);
    }

    // ------------------------------------------------------------------ resolution

    /** One resolution: typed accessors over the merged layers. */
    private static final class Resolution {
        private final Map<String, Value> values;
        private final Map<String, String> env;

        Resolution(Map<String, Value> values, Map<String, String> env) {
            this.values = values;
            this.env = env;
        }

        SecPipeConfig build() {
            RunProfile profile = enumValue("profile", RunProfile.class).orElse(RunProfile.STANDARD);
            ProviderMode provider = provider();
            ScopeMode scope = enumValue("scope", ScopeMode.class).orElseGet(() -> defaultScope(profile));
            Optional<String> baseRef = text("base-ref").or(() -> envText("GITHUB_BASE_REF"));
            boolean quiet = flag("quiet");
            boolean verbose = flag("verbose");
            if (quiet && verbose) {
                throw new ConfigException("quiet and verbose output were both requested ("
                        + values.get("quiet").source() + ", " + values.get("verbose").source() + ")",
                        "use only one of -q and -v");
            }
            Verbosity verbosity = quiet ? Verbosity.QUIET : verbose ? Verbosity.VERBOSE : Verbosity.NORMAL;
            return new SecPipeConfig(
                    path("target").orElse(Path.of(DEFAULT_TARGET)),
                    path("out").orElse(Path.of(DEFAULT_OUT)),
                    profile,
                    scope,
                    baseRef,
                    text("head-ref"),
                    provider,
                    text("model"),
                    modelForRole(),
                    tierOverrides(),
                    baseUrl(),
                    idSet("tools"),
                    idSet("exclude-tools"),
                    integer("max-agent-calls", 0).orElse(defaultMaxAgentCalls(profile)),
                    integer("max-fix-attempts", 0).orElse(SecPipeConfig.DEFAULT_MAX_FIX_ATTEMPTS),
                    integer("max-sandbox-runs", 0).orElse(SecPipeConfig.DEFAULT_MAX_SANDBOX_RUNS),
                    fixMode(),
                    !flag("no-sandbox"),
                    flag("offline"),
                    failOn(),
                    flag("fail-on-needs-validation"),
                    flag("fail-on-incomplete"),
                    formats(),
                    path("prior-run"),
                    flag("print-prompts"),
                    List.copyOf(list("deny-path", false)),
                    integer("max-snippet-lines", 1).orElse(SecPipeConfig.DEFAULT_MAX_SNIPPET_LINES),
                    integer("max-files-per-call", 1).orElse(SecPipeConfig.DEFAULT_MAX_FILES_PER_CALL),
                    idSet("disable-node"),
                    flag("include-tests"),
                    flag("allow-build-extensions"),
                    flag("accept-codeql-terms"),
                    enumValue("engine", GraphEngine.class).orElse(GraphEngine.PLAIN),
                    duration("tool-timeout").orElse(DEFAULT_TOOL_TIMEOUT),
                    verbosity);
        }

        // ---- implications

        private ProviderMode provider() {
            ProviderMode requested = ProviderMode.NONE;
            Value v = values.get("llm-provider");
            if (v != null) {
                try {
                    requested = ProviderMode.fromCli(v.raw().strip().toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw invalid(v, "an LLM provider", "use one of none, copilot, anthropic, openai, "
                            + "openai-compatible, ollama");
                }
            }
            if (requested == ProviderMode.FAKE && !"1".equals(env.get(ALLOW_FAKE_LLM_ENV))) {
                throw new ConfigException("the 'fake' LLM provider (" + v.source() + ") is for tests and CI only",
                        "set " + ALLOW_FAKE_LLM_ENV + "=1 to allow it, or choose a real provider");
            }
            if (flag("no-ai")) {
                return ProviderMode.NONE;
            }
            if (flag("offline") && requested != ProviderMode.OLLAMA) {
                return ProviderMode.NONE;
            }
            return requested;
        }

        private ScopeMode defaultScope(RunProfile profile) {
            boolean pullRequestJob = "true".equalsIgnoreCase(env.getOrDefault("GITHUB_ACTIONS", "").strip())
                    && "pull_request".equals(env.getOrDefault("GITHUB_EVENT_NAME", "").strip());
            return profile == RunProfile.QUICK && pullRequestJob ? ScopeMode.DIFF : ScopeMode.FULL;
        }

        private static int defaultMaxAgentCalls(RunProfile profile) {
            return switch (profile) {
                case QUICK -> SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_QUICK;
                case STANDARD -> SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_STANDARD;
                case DEEP -> SecPipeConfig.DEFAULT_MAX_AGENT_CALLS_DEEP;
            };
        }

        private FixMode fixMode() {
            Value v = values.get("enable-fixes");
            if (v == null) {
                return FixMode.SUGGEST;
            }
            return switch (v.raw().strip().toLowerCase(Locale.ROOT)) {
                case "false" -> FixMode.FALSE;
                case "true", "suggest", "" -> FixMode.SUGGEST;
                case "pr" -> FixMode.PR;
                default -> throw invalid(v, "a fix mode", "use one of false, suggest, pr (true means suggest)");
            };
        }

        private Optional<Finding.Score5> failOn() {
            Value v = values.get("fail-on");
            if (v == null) {
                return Optional.of(Finding.Score5.HIGH);
            }
            String s = v.raw().strip().toUpperCase(Locale.ROOT);
            if ("NONE".equals(s)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Finding.Score5.valueOf(s));
            } catch (IllegalArgumentException e) {
                throw invalid(v, "a severity", "use one of critical, high, medium, low, informational, none");
            }
        }

        private Set<OutputFormat> formats() {
            Value v = values.get("format");
            if (v == null) {
                return Collections.unmodifiableSet(EnumSet.allOf(OutputFormat.class));
            }
            EnumSet<OutputFormat> out = EnumSet.noneOf(OutputFormat.class);
            for (String item : list("format", true)) {
                try {
                    out.add(OutputFormat.valueOf(item.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw invalid(v, "a list of output formats", "use a comma list of json, sarif, html, "
                            + "markdown, metrics");
                }
            }
            if (out.isEmpty()) {
                throw invalid(v, "a list of output formats", "name at least one of json, sarif, html, markdown, "
                        + "metrics");
            }
            return Collections.unmodifiableSet(out);
        }

        private Map<AgentRole, String> modelForRole() {
            Map<AgentRole, String> out = new EnumMap<>(AgentRole.class);
            Value v = values.get("model-for");
            for (String pair : list("model-for", true)) {
                int eq = pair.indexOf('=');
                String role = eq < 0 ? "" : pair.substring(0, eq).strip();
                String model = eq < 0 ? "" : pair.substring(eq + 1).strip();
                Optional<AgentRole> parsed = agentRole(role);
                if (parsed.isEmpty() || model.isEmpty()) {
                    throw invalid(v, "ROLE=MODEL pairs", "roles are recon_summary, hunter, critic, testgen, "
                            + "disprover, record_verifier, remediation; e.g. --model-for disprover=claude-opus-5");
                }
                out.put(parsed.get(), model);
            }
            return Collections.unmodifiableMap(out);
        }

        private static Optional<AgentRole> agentRole(String name) {
            String normalized = name.toUpperCase(Locale.ROOT).replace('-', '_');
            for (AgentRole role : AgentRole.values()) {
                if (role.name().equals(normalized)) {
                    return Optional.of(role);
                }
            }
            return Optional.empty();
        }

        private Map<String, Finding.VerifierRef.CapabilityTier> tierOverrides() {
            Map<String, Finding.VerifierRef.CapabilityTier> out = new TreeMap<>();
            Value v = values.get("model-tier");
            for (String pair : list("model-tier", true)) {
                int eq = pair.lastIndexOf('=');
                String model = eq < 0 ? "" : pair.substring(0, eq).strip();
                String tier = eq < 0 ? "" : pair.substring(eq + 1).strip().toUpperCase(Locale.ROOT);
                Finding.VerifierRef.CapabilityTier parsed = null;
                for (Finding.VerifierRef.CapabilityTier t : Finding.VerifierRef.CapabilityTier.values()) {
                    if (t.name().equals(tier)) {
                        parsed = t;
                    }
                }
                if (parsed == null || model.isEmpty()) {
                    throw invalid(v, "MODEL=TIER pairs", "tiers are strong, standard, small; "
                            + "e.g. --model-tier my-model=small");
                }
                out.put(model, parsed);
            }
            return Collections.unmodifiableMap(out);
        }

        private Optional<String> baseUrl() {
            Optional<String> raw = text("llm-base-url");
            if (raw.isEmpty()) {
                return raw;
            }
            try {
                URI uri = new URI(raw.get());
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                    return raw;
                }
            } catch (URISyntaxException e) {
                // reported below
            }
            throw invalid(values.get("llm-base-url"), "an http(s) URL", "e.g. --llm-base-url http://localhost:11434");
        }

        // ---- primitive accessors

        private Optional<String> text(String key) {
            Value v = values.get(key);
            if (v == null) {
                return Optional.empty();
            }
            String s = v.raw().strip();
            if (s.isEmpty()) {
                throw invalid(v, "a non-empty value", "give a value or remove the option");
            }
            return Optional.of(s);
        }

        private Optional<String> envText(String name) {
            String s = env.get(name);
            return s == null || s.isBlank() ? Optional.empty() : Optional.of(s.strip());
        }

        private Optional<Path> path(String key) {
            Optional<String> s = text(key);
            if (s.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Path.of(s.get()));
            } catch (InvalidPathException e) {
                throw invalid(values.get(key), "a file system path", "check the path for illegal characters");
            }
        }

        private boolean flag(String key) {
            Value v = values.get(key);
            if (v == null) {
                return false;
            }
            return switch (v.raw().strip().toLowerCase(Locale.ROOT)) {
                case "", "true", "1", "yes" -> true;
                case "false", "0", "no" -> false;
                default -> throw invalid(v, "true or false", "use true or false (or pass the flag without a value)");
            };
        }

        private Optional<Integer> integer(String key, int min) {
            Value v = values.get(key);
            if (v == null) {
                return Optional.empty();
            }
            int n;
            try {
                n = Integer.parseInt(v.raw().strip());
            } catch (NumberFormatException e) {
                throw invalid(v, "a whole number", "use a whole number >= " + min);
            }
            if (n < min) {
                throw invalid(v, "a whole number >= " + min, "use a whole number >= " + min);
            }
            return Optional.of(n);
        }

        private <E extends Enum<E>> Optional<E> enumValue(String key, Class<E> type) {
            Value v = values.get(key);
            if (v == null) {
                return Optional.empty();
            }
            String s = v.raw().strip().toUpperCase(Locale.ROOT).replace('-', '_');
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(s)) {
                    return Optional.of(constant);
                }
            }
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                allowed.add(constant.name().toLowerCase(Locale.ROOT));
            }
            throw invalid(v, "one of " + String.join(", ", allowed), "use one of " + String.join(", ", allowed));
        }

        private Optional<Duration> duration(String key) {
            Value v = values.get(key);
            if (v == null) {
                return Optional.empty();
            }
            String s = v.raw().strip();
            Duration d = null;
            Matcher m = SIMPLE_DURATION.matcher(s);
            if (m.matches()) {
                long n = Long.parseLong(m.group(1));
                String unit = m.group(2) == null ? "s" : m.group(2);
                d = switch (unit) {
                    case "ms" -> Duration.ofMillis(n);
                    case "m" -> Duration.ofMinutes(n);
                    case "h" -> Duration.ofHours(n);
                    default -> Duration.ofSeconds(n);
                };
            } else {
                try {
                    d = Duration.parse(s);
                } catch (DateTimeParseException e) {
                    // reported below
                }
            }
            if (d == null || d.isNegative() || d.isZero()) {
                throw invalid(v, "a positive duration", "use e.g. 120s, 500ms, 2m, 1h or PT2M");
            }
            return Optional.of(d);
        }

        /** Splits a list value on {@link #REPEAT_SEPARATOR} and, if {@code commas}, also on commas. */
        private List<String> list(String key, boolean commas) {
            Value v = values.get(key);
            if (v == null) {
                return List.of();
            }
            String regex = commas ? "[\\n,]" : "\\n";
            List<String> out = new ArrayList<>();
            for (String item : v.raw().split(regex)) {
                String s = item.strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }

        private Set<String> idSet(String key) {
            return Collections.unmodifiableSortedSet(new TreeSet<>(list(key, true)));
        }

        private static ConfigException invalid(Value v, String expected, String hint) {
            return new ConfigException(v.source() + ": expected " + expected + ", got '"
                    + oneLine(v.raw().replace(REPEAT_SEPARATOR, ",")) + "'", hint);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String suggestion(String key) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : KEYS) {
            int d = editDistance(key.toLowerCase(Locale.ROOT), candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        return best != null && bestDistance <= 3 && !CONFIG_KEY.equals(best) ? " (did you mean '" + best + "'?)" : "";
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String line = s.strip();
        int nl = line.indexOf('\n');
        return nl < 0 ? line : line.substring(0, nl).strip();
    }

    private static final class Yaml {
        static final YAMLMapper MAPPER = YAMLMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build();
    }

    private static final class SchemaHolder {
        static final Schema SCHEMA = load();

        private static Schema load() {
            try (InputStream in = ConfigResolver.class.getResourceAsStream(SCHEMA_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from secpipe-contracts");
                }
                return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
