package ca.carleton.secpipe.contracts.pipeline;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.spi.AnalyzerAdapter;
import ca.carleton.secpipe.contracts.spi.Budget;
import ca.carleton.secpipe.contracts.spi.LlmClient;
import ca.carleton.secpipe.contracts.spi.SandboxRunner;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Services the engine hands to every node. All are thread-safe. Nodes get no other way to reach the host. */
public interface RunContext {
    Path workDir();                       // <out>/.work, engine-owned scratch on host
    Path outDir();
    LlmClient llm();                      // mode()==NONE when AI is off
    Optional<SandboxRunner> sandbox();    // empty when Docker unusable or --no-sandbox
    Budget budget();
    List<AnalyzerAdapter> adapters();     // already filtered by config/profile/availability
    Rescanner rescanner();                // used by the remediation loop (FR-14)
    Clock clock();
    RunLog log();                         // redacting logger (WO-Y.2)

    /** Re-runs detection + normalization on a patched working copy and returns fingerprinted candidates. */
    interface Rescanner {
        List<Candidate> rescan(Path workingCopy, Set<String> scopeFiles) throws Exception;
    }

    interface RunLog {
        void info(String msg);
        void warn(String msg);
        void debug(String msg);
    }
}
