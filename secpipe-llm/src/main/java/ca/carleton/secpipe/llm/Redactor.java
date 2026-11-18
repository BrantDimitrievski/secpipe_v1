package ca.carleton.secpipe.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scrubs secrets from text before it reaches an exception message, a usage record or a log line.
 *
 * <p>Two passes: (1) the values of secret-looking environment variables, looked up in the environment map at
 * redaction time (key values are never copied into a field), and (2) well-known key shapes
 * ({@code sk-...}, {@code Bearer ...}, GitHub tokens). Every match becomes {@value #MASK}.
 */
public final class Redactor {

    public static final String MASK = "[REDACTED]";

    /** API-key variables named by the CLI contract (SecPipeConfig javadoc). */
    public static final Set<String> KEY_VARIABLES =
            Set.of("SECPIPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "SECPIPE_LLM_KEY");

    private static final List<String> SECRET_NAME_SUFFIXES = List.of("_KEY", "_TOKEN", "_SECRET", "_PASSWORD");
    private static final int MIN_SECRET_LENGTH = 6;
    private static final List<Pattern> SHAPES = List.of(
            Pattern.compile("sk-[A-Za-z0-9_\\-]{12,}"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=\\-]{8,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"));

    private final Map<String, String> env;

    private Redactor(Map<String, String> env) {
        this.env = env;
    }

    /** A redactor over the given environment (normally {@code System.getenv()}). */
    public static Redactor forEnv(Map<String, String> env) {
        return new Redactor(env);
    }

    /** A redactor that only applies the key-shape patterns. */
    public static Redactor shapesOnly() {
        return new Redactor(Map.of());
    }

    /** Returns {@code text} with every secret replaced by {@value #MASK}; an absent text becomes "". */
    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        for (String secret : secretValues()) {
            out = out.replace(secret, MASK);
        }
        for (Pattern p : SHAPES) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return out;
    }

    private List<String> secretValues() {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String value = e.getValue();
            if (value != null && value.length() >= MIN_SECRET_LENGTH && isSecretName(e.getKey())) {
                values.add(value);
            }
        }
        values.sort(Comparator.comparingInt(String::length).reversed());
        return values;
    }

    static boolean isSecretName(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (KEY_VARIABLES.contains(upper)) {
            return true;
        }
        for (String suffix : SECRET_NAME_SUFFIXES) {
            if (upper.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
