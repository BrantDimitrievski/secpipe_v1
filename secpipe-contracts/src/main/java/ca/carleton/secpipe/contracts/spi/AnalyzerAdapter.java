package ca.carleton.secpipe.contracts.spi;

import ca.carleton.secpipe.contracts.finding.Candidate;
import ca.carleton.secpipe.contracts.pipeline.ProjectProfile;
import ca.carleton.secpipe.contracts.run.RunMetadata;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plug-in contract for SAST/SCA tools (NFR-3). Discovered with java.util.ServiceLoader; each adapter lives in its
 * own Maven module with its own META-INF/services/ca.carleton.secpipe.contracts.spi.AnalyzerAdapter file, so adding
 * an adapter never edits a shared file. Adapters are read-only with respect to the target project.
 */
public interface AnalyzerAdapter {

    /** Stable lowercase id used in --tools and reports, e.g. "semgrep". Pattern ^[a-z][a-z0-9-]*$. */
    String id();

    String displayName();

    Kind kind();

    /** CWE ids this adapter has rules for (e.g. "CWE-89"); empty for SCA. Drives ledger coverage claims. */
    Set<String> supportedCwes();

    /** Whether the tool is installed/usable; never throws. */
    Availability availability(EnvironmentInfo env);

    /** On by default for this profile (subject to availability). Opt-in adapters return false. */
    boolean defaultEnabled(ca.carleton.secpipe.contracts.config.RunProfile profile);

    /** Licence note shown by `secpipe doctor`; opt-in gates (e.g. CodeQL terms) are enforced by the adapter. */
    String licenseNote();

    /** True if the adapter must execute inside SandboxRunner (e.g. SpotBugs needs compiled classes). */
    boolean requiresSandbox();

    Result analyze(Request request) throws AnalyzerException;

    enum Kind { SAST, SCA }

    record Availability(boolean available, Optional<String> version, Optional<String> reason, Optional<String> installHint) {
        public static Availability yes(String version) { return new Availability(true, Optional.ofNullable(version), Optional.empty(), Optional.empty()); }
        public static Availability no(String reason, String hint) { return new Availability(false, Optional.empty(), Optional.of(reason), Optional.ofNullable(hint)); }
    }

    /** scopeFiles empty = whole project. workDir is adapter-private scratch on the host (never inside the target). */
    record Request(Path projectRoot, ProjectProfile profile, Set<String> scopeFiles, Path workDir, Duration timeout,
                   boolean offline, Optional<SandboxRunner> sandbox) { }

    /** A claim that rules for `cwe` ran over `files` (repo-relative). Lets the ledger mark units covered. */
    record CoverageClaim(String cwe, List<String> files, List<String> ruleIds) { }

    record Result(List<Candidate> candidates, List<CoverageClaim> coverage, RunMetadata.ToolRun run) { }

    class AnalyzerException extends Exception {
        public AnalyzerException(String message, Throwable cause) { super(message, cause); }
        public AnalyzerException(String message) { super(message); }
    }
}
