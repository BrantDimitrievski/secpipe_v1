package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.contracts.pipeline.PipelineState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineCheckpointTest {

    @Test
    void sameStateGivesTheSameBytesAndCoversEveryField() throws Exception {
        DeltaApplier applier = new DeltaApplier(EngineValidators.acceptAll());
        PipelineState state = applier.apply(DeltaApplier.freeze(EngineFixtures.emptyState()), EngineFixtures.delta()
                .environment(EngineFixtures.environment())
                .profile(EngineFixtures.profile())
                .newUnits(EngineFixtures.unit("U-2", LedgerUnit.Status.PLANNED),
                        EngineFixtures.unit("U-1", LedgerUnit.Status.PLANNED))
                .addCandidates(EngineFixtures.candidate("sql injection"))
                .putRecords(EngineFixtures.rejected("fp-b", "fp"), EngineFixtures.rejected("fp-a", "fp"))
                .putDraft("fp-c", EngineFixtures.draft("fp-c"))
                .toolRuns(EngineFixtures.toolRun("semgrep"))
                .timing("setup", 3)
                .fixAttempts("fp-a", 1)
                .notes("a note")
                .incomplete("stopped")
                .build());

        String json = EngineCheckpoint.toJson(state);

        assertThat(EngineCheckpoint.toJson(DeltaApplier.freeze(state))).isEqualTo(json);
        assertThat(json.endsWith("}\n")).isTrue();
        for (String key : new String[] {"\"run_id\": \"run-1\"", "\"config\"", "\"environment\"", "\"profile\"",
                "\"ledger\"", "\"candidates\"", "\"records\"", "\"survivor_drafts\"", "\"tool_runs\"",
                "\"stage_timings_ms\"", "\"hunter_wave\": 0", "\"fix_attempts_by_fingerprint\"",
                "\"notes\"", "\"incomplete_reason\": \"stopped\""}) {
            assertThat(json).contains(key);
        }
        assertThat(json.indexOf("\"fp-a\"") < json.indexOf("\"fp-b\"")).isTrue();
        assertThat(json.indexOf("U-1") < json.indexOf("U-2")).isTrue();
        assertThat(json).doesNotContain("\"prior\"");
    }
}
