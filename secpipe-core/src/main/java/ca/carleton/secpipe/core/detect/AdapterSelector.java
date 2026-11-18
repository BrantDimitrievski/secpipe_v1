package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Decides which analyzer adapters run, and why the others do not. Rules, first match wins:
 * <ol>
 *   <li>{@code --tools} is non-empty and does not list the adapter: "not listed in --tools";</li>
 *   <li>{@code --exclude-tools} lists it: "excluded with --exclude-tools";</li>
 *   <li>{@code --tools} is empty and the adapter is not on by default for the profile;</li>
 *   <li>the adapter reports itself unavailable: its own reason;</li>
 *   <li>otherwise it is selected.</li>
 * </ol>
 * Output is sorted by adapter id. If two adapters share an id, the first discovered wins and the other is skipped.
 */
public final class AdapterSelector {

    /** An adapter and whether it runs; {@code reason} explains a skip ("selected" otherwise). */
    public record Selection(AnalyzerAdapter adapter, boolean selected, String reason, Optional<String> version) {
        public Selection {
            Objects.requireNonNull(adapter, "adapter");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(version, "version");
        }

        public String id() {
            return adapter.id();
        }

        Selection skip(String why) {
            return new Selection(adapter, false, why, version);
        }
    }

    private final List<AnalyzerAdapter> adapters;

    public AdapterSelector(List<AnalyzerAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    /** Adapters registered through {@code META-INF/services} visible to {@code loader}. */
    public static List<AnalyzerAdapter> discover(ClassLoader loader) {
        List<AnalyzerAdapter> out = new ArrayList<>();
        ServiceLoader.load(AnalyzerAdapter.class, loader).forEach(out::add);
        return List.copyOf(out);
    }

    public List<Selection> select(SecPipeConfig config, EnvironmentInfo env) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(env, "env");
        Map<String, AnalyzerAdapter> byId = new LinkedHashMap<>();
        List<Selection> out = new ArrayList<>();
        for (AnalyzerAdapter a : adapters) {
            if (byId.putIfAbsent(a.id(), a) != null) {
                out.add(new Selection(a, false, "duplicate adapter id " + a.id(), Optional.empty()));
            }
        }
        for (AnalyzerAdapter a : byId.values()) {
            out.add(decide(a, config, env));
        }
        out.sort(Comparator.comparing(Selection::id).thenComparing(s -> !s.selected()));
        return List.copyOf(out);
    }

    private static Selection decide(AnalyzerAdapter a, SecPipeConfig config, EnvironmentInfo env) {
        if (!config.tools().isEmpty() && !config.tools().contains(a.id())) {
            return new Selection(a, false, "not listed in --tools", Optional.empty());
        }
        if (config.excludeTools().contains(a.id())) {
            return new Selection(a, false, "excluded with --exclude-tools", Optional.empty());
        }
        if (config.tools().isEmpty() && !a.defaultEnabled(config.profile())) {
            return new Selection(a, false,
                    "not enabled by default for profile " + config.profile().name().toLowerCase(), Optional.empty());
        }
        AnalyzerAdapter.Availability availability;
        try {
            availability = a.availability(env);
        } catch (RuntimeException e) {
            return new Selection(a, false, "availability check failed: " + message(e), Optional.empty());
        }
        if (availability == null || !availability.available()) {
            String reason = availability == null ? "unavailable" : availability.reason().orElse("unavailable");
            String hint = availability == null ? "" : availability.installHint().map(h -> " (hint: " + h + ")")
                    .orElse("");
            return new Selection(a, false, reason + hint, Optional.empty());
        }
        return new Selection(a, true, "selected", availability.version());
    }

    static String message(Throwable t) {
        return t.getMessage() == null || t.getMessage().isBlank() ? t.getClass().getSimpleName() : t.getMessage();
    }
}
