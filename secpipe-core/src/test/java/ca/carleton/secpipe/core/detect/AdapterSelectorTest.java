package ca.carleton.secpipe.core.detect;

import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.EnvironmentInfo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterSelectorTest {

    /** A minimal adapter with a configurable id, default flag and availability. */
    private record Stub(String id, boolean byDefault, AnalyzerAdapter.Availability avail) implements AnalyzerAdapter {
        @Override
        public String displayName() {
            return id;
        }

        @Override
        public Kind kind() {
            return Kind.SAST;
        }

        @Override
        public Set<String> supportedCwes() {
            return Set.of("CWE-89");
        }

        @Override
        public Availability availability(EnvironmentInfo env) {
            return avail;
        }

        @Override
        public boolean defaultEnabled(RunProfile profile) {
            return byDefault;
        }

        @Override
        public String licenseNote() {
            return "";
        }

        @Override
        public boolean requiresSandbox() {
            return false;
        }

        @Override
        public Result analyze(Request request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final AnalyzerAdapter.Availability YES = AnalyzerAdapter.Availability.yes("1.0");

    private static Map<String, AdapterSelector.Selection> select(List<AnalyzerAdapter> adapters, Set<String> tools,
                                                                 Set<String> exclude) {
        return new AdapterSelector(adapters)
                .select(DetectFixtures.config(tools, exclude, Duration.ofSeconds(5)), DetectFixtures.environment())
                .stream().collect(Collectors.toMap(AdapterSelector.Selection::id, Function.identity()));
    }

    private final List<AnalyzerAdapter> three = List.of(new Stub("zeta", true, YES), new Stub("alpha", true, YES),
            new Stub("optin", false, YES));

    @Test
    void defaultsSelectDefaultEnabledAvailableAdaptersSortedById() {
        List<AdapterSelector.Selection> out = new AdapterSelector(three)
                .select(DetectFixtures.config(), DetectFixtures.environment());

        assertThat(out).extracting(AdapterSelector.Selection::id).containsExactly("alpha", "optin", "zeta");
        assertThat(out).extracting(AdapterSelector.Selection::selected).containsExactly(true, false, true);
        assertThat(out.get(1).reason()).isEqualTo("not enabled by default for profile standard");
        assertThat(out.get(0).version()).contains("1.0");
    }

    @Test
    void toolsFlagRestrictsAndCanEnableOptInAdapters() {
        Map<String, AdapterSelector.Selection> s = select(three, Set.of("optin", "zeta"), Set.of());

        assertThat(s.get("alpha").selected()).isFalse();
        assertThat(s.get("alpha").reason()).isEqualTo("not listed in --tools");
        assertThat(s.get("optin").selected()).isTrue();
        assertThat(s.get("zeta").selected()).isTrue();
    }

    @Test
    void excludeToolsWinsOverTools() {
        Map<String, AdapterSelector.Selection> s = select(three, Set.of("alpha", "zeta"), Set.of("zeta"));

        assertThat(s.get("alpha").selected()).isTrue();
        assertThat(s.get("zeta").selected()).isFalse();
        assertThat(s.get("zeta").reason()).isEqualTo("excluded with --exclude-tools");
    }

    @Test
    void unavailableAdapterIsSkippedWithItsReasonAndHint() {
        AnalyzerAdapter missing = new Stub("semgrep", true,
                AnalyzerAdapter.Availability.no("semgrep not on PATH", "pipx install semgrep"));

        AdapterSelector.Selection s = select(List.of(missing), Set.of(), Set.of()).get("semgrep");

        assertThat(s.selected()).isFalse();
        assertThat(s.reason()).isEqualTo("semgrep not on PATH (hint: pipx install semgrep)");
    }

    @Test
    void duplicateIdsKeepTheFirstAdapter() {
        Stub first = new Stub("dup", true, YES);
        Stub second = new Stub("dup", true, YES);

        List<AdapterSelector.Selection> out = new AdapterSelector(List.of(first, second))
                .select(DetectFixtures.config(), DetectFixtures.environment());

        assertThat(out).hasSize(2);
        assertThat(out.get(0).selected()).isTrue();
        assertThat(out.get(0).adapter()).isSameAs(first);
        assertThat(out.get(1).reason()).isEqualTo("duplicate adapter id dup");
    }

    @Test
    void discoversTheTwoTestAdaptersThroughServiceLoader() {
        assertThat(DetectFixtures.fakes()).extracting(AnalyzerAdapter::id)
                .containsExactlyInAnyOrder("fake-alpha", "fake-beta");
    }
}
