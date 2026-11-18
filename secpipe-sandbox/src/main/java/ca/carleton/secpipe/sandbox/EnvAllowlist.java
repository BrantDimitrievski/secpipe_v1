package ca.carleton.secpipe.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The only environment variables a sandbox container may receive (control {@code ENV_ALLOWLIST}).
 *
 * <p>Variables reach the container only through a {@code --env-file} whose lines are all {@code KEY=VALUE}. Spike S.3
 * showed that a bare {@code KEY} line (or {@code -e KEY}) copies the host's value, so this class never writes one.
 * Files are UTF-8 without a BOM, with LF line endings, sorted by key.
 */
public final class EnvAllowlist {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** Names the runner accepts by default. Nothing secret-shaped: no tokens, keys, passwords or credentials. */
    public static final EnvAllowlist DEFAULT = new EnvAllowlist(Set.of("LANG", "LC_ALL", "TZ", "MAVEN_OPTS",
        "MAVEN_ARGS"));

    private final Set<String> names;

    public EnvAllowlist(Set<String> names) {
        for (String name : names) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid environment variable name: " + name);
            }
        }
        this.names = Set.copyOf(names);
    }

    /** A new allowlist with {@code extra} added to these names. */
    public EnvAllowlist with(String... extra) {
        Set<String> all = new TreeSet<>(names);
        all.addAll(List.of(extra));
        return new EnvAllowlist(all);
    }

    public Set<String> names() {
        return names;
    }

    /** Problems with {@code env}, sorted by key; empty when every entry may be passed. */
    public List<String> violations(Map<String, String> env) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(env).entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || !NAME.matcher(key).matches()) {
                problems.add("invalid name: " + key);
            } else if (!names.contains(key)) {
                problems.add("not on the allowlist: " + key);
            } else if (value == null) {
                problems.add("no value (a bare KEY would copy the host value): " + key);
            } else if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                problems.add("value contains a line break or NUL: " + key);
            }
        }
        return List.copyOf(problems);
    }

    /** The env-file lines for {@code env}, sorted by key, each {@code KEY=VALUE}. */
    public List<String> lines(Map<String, String> env) {
        List<String> problems = violations(env);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("environment refused: " + String.join("; ", problems));
        }
        List<String> lines = new ArrayList<>();
        new TreeMap<>(env).forEach((k, v) -> lines.add(k + "=" + v));
        return List.copyOf(lines);
    }

    /** Writes {@code env} as a docker env-file: UTF-8, no BOM, LF, {@code KEY=VALUE} lines only. */
    public void writeEnvFile(Map<String, String> env, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines(env)) {
            sb.append(line).append('\n');
        }
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
