package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.config.ProviderMode;
import ca.carleton.secpipe.contracts.config.RunProfile;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.finding.Finding;
import ca.carleton.secpipe.contracts.json.ContractJson;
import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.json.RunMetadataCodec;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Hand-made inputs for the report tests: three findings (one per verdict), one ledger unit, complete metadata. */
final class Fixtures {

    static final String BASE = "/ca/carleton/secpipe/report/";
    static final String RUN_ID = "run-20261117T143005Z-a1b2c3";

    private Fixtures() {
    }

    static String resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream(BASE + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + BASE + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Finding> findings() {
        return ContractJson.readFindings(resource("inputs/findings.json"));
    }

    static RunMetadata metadata() {
        return RunMetadataCodec.read(resource("inputs/run-metadata.json"));
    }

    static SecPipeConfig config(Set<SecPipeConfig.OutputFormat> formats) {
        return new SecPipeConfig(Path.of("/work/vulnlab"), Path.of("/work/out"), RunProfile.STANDARD,
                SecPipeConfig.ScopeMode.FULL, Optional.empty(), Optional.empty(), ProviderMode.NONE, Optional.empty(),
                Map.<AgentRole, String>of(), Map.of(), Optional.empty(), Set.of(), Set.of(), 40, 3, 10,
                SecPipeConfig.FixMode.FALSE, false, true, Optional.empty(), false, false, formats, Optional.empty(),
                false, List.of(), 80, 6, Set.of(), false, false, false, SecPipeConfig.GraphEngine.PLAIN,
                Duration.ofMinutes(5), SecPipeConfig.Verbosity.NORMAL);
    }

    static Set<SecPipeConfig.OutputFormat> allFormats() {
        return Set.of(SecPipeConfig.OutputFormat.JSON, SecPipeConfig.OutputFormat.SARIF,
                SecPipeConfig.OutputFormat.MARKDOWN);
    }

    static PipelineState state() {
        return state(findings(), Optional.empty(), List.of("Fixture run; analyzers were not executed."));
    }

    /** The same state with the records inserted in reverse order (determinism checks). */
    static PipelineState reversedState() {
        List<Finding> reversed = new ArrayList<>(findings());
        Collections.reverse(reversed);
        return state(reversed, Optional.empty(), List.of("Fixture run; analyzers were not executed."));
    }

    static PipelineState state(List<Finding> findings, Optional<String> incompleteReason, List<String> notes) {
        Map<String, Finding> records = new LinkedHashMap<>();
        findings.forEach(f -> records.put(f.fingerprint(), f));
        return new PipelineState(RUN_ID, config(allFormats()), Optional.empty(), Optional.empty(), List.of(),
                LedgerCodec.read(resource("inputs/ledger.json")), List.of(), records, Map.of(), List.of(), Map.of(),
                0, 0, false, Map.of(), Optional.empty(), notes, incompleteReason);
    }

    static ReportInputs inputs() {
        return new ReportInputs(state(), metadata());
    }
}
