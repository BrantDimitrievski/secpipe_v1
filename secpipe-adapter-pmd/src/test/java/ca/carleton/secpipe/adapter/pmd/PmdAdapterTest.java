package ca.carleton.secpipe.adapter.pmd;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RuleSet;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

/** Ported-from: capstone-mvp-1@2905635:src/main/java/ca/carleton/secpipe/tools/PmdAdapter.java (behaviour). */
class PmdAdapterTest {

    @Test
    void everyRulesetRuleHasCweMappingOrIsIgnored() {
        RuleSet ruleSet = RuleSetLoader.fromPmdConfig(new PMDConfiguration()).loadFromResource(PmdAdapter.RULESET);
        List<String> names = new ArrayList<>();
        for (Rule rule : ruleSet.getRules()) names.add(rule.getName());
        assertThat(names).isNotEmpty();
        for (String name : names) {
            assertThat(PmdRules.BY_RULE.containsKey(name) || PmdRules.IGNORED.contains(name))
                    .as("rule %s needs a CWE mapping or an IGNORED entry", name).isTrue();
        }
    }

    @Test
    void registeredByServiceLoader() {
        assertThat(ServiceLoader.load(AnalyzerAdapter.class).stream().map(p -> p.get().id())).contains("pmd");
    }

    @Test
    void vulnerableJavaAppYieldsSinkOnlyCandidates() throws Exception {
        Path root = findFixture();
        AnalyzerAdapter.Result result = new PmdAdapter().analyze(request(root));
        assertThat(result.candidates()).isNotEmpty();
        for (Candidate c : result.candidates()) {
            assertThat(c.cwe()).hasSize(1);
            assertThat(c.trace()).hasSize(1);
            assertThat(c.trace().get(0).kind()).isEqualTo(Finding.TraceKind.SINK);
            assertThat(c.sinkStatement()).isPresent();
            assertThat(c.sinkScope().orElseThrow()).contains("#");
            assertThat(c.fingerprint()).isEmpty();
        }
        assertThat(result.coverage()).isNotEmpty();
        assertThat(result.run().id()).isEqualTo("pmd");
    }

    private static Path findFixture() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("fixtures/mvp-samples/vulnerable-java-app");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixtures/mvp-samples/vulnerable-java-app not found");
    }

    private static AnalyzerAdapter.Request request(Path root) throws IOException {
        List<String> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        ProjectProfile profile = new ProjectProfile(root, "java", ProjectProfile.BuildTool.NONE, Set.of(), files,
                List.of(), List.of(), 0);
        return new AnalyzerAdapter.Request(root, profile, Set.of(), root, Duration.ofMinutes(1), true, Optional.empty());
    }
}
