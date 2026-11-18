package ca.carleton.secpipe.agents.runner;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.Citation;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.ControlExamined;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.CriticResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.HunterCandidate;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.HunterResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.MissingUnit;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RecordVerifierResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.RemediationResult;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.Step;
import ca.carleton.secpipe.contracts.agent.AgentOutputs.TestGenResult;
import ca.carleton.secpipe.contracts.finding.Finding;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Minimal deterministic grounding (WO-G.1, folded from WO-V.3): every file/line an agent output cites must exist in
 * the {@link GroundingContext#sources()} index, every path it lists as reviewed or as a starting path must be an
 * indexed file, and every fingerprint it names must be one of {@link GroundingContext#knownFingerprints()}.
 * Quotes are not compared with the source here (that is the stretch WO-V.3).
 *
 * <p>Checked per output type:
 * <ul>
 *   <li>hunter: {@code reviewed_paths}; each candidate's trace steps and evidence citations;</li>
 *   <li>critic: each missing unit's {@code starting_paths};</li>
 *   <li>disprover: fingerprint; {@code controls_examined}, trace, evidence;</li>
 *   <li>record verifier: fingerprint; the replacement finding's fingerprint, trace and evidence;</li>
 *   <li>testgen: fingerprint;</li>
 *   <li>remediation: fingerprint; {@code decision_point} ({@code files_changed} may name new files).</li>
 * </ul>
 */
public final class GroundingCheck {

    private GroundingCheck() {
    }

    /**
     * The grounding problems of {@code output}, sorted and de-duplicated; empty means grounded.
     *
     * @throws IllegalArgumentException if {@code output} is not an agent output record
     */
    public static List<String> problems(Object output, GroundingContext ctx) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(ctx, "ctx");
        Collector c = new Collector(ctx);
        switch (output) {
            case HunterResult h -> {
                h.reviewedPaths().forEach(c::file);
                for (HunterCandidate cand : h.candidates()) {
                    cand.trace().forEach(c::step);
                    cand.evidence().forEach(c::citation);
                }
            }
            case CriticResult cr -> {
                for (MissingUnit u : cr.missingUnits()) {
                    u.startingPaths().forEach(c::file);
                }
            }
            case DisproverResult d -> {
                c.fingerprint(d.fingerprint());
                for (ControlExamined ce : d.controlsExamined()) {
                    c.location(ce.file(), ce.line());
                }
                d.trace().forEach(c::step);
                d.evidence().forEach(c::citation);
            }
            case RecordVerifierResult r -> {
                c.fingerprint(r.fingerprint());
                r.replacement().ifPresent(f -> {
                    c.fingerprint(f.fingerprint());
                    for (Finding.TraceStep s : f.trace()) {
                        c.location(s.file(), s.line());
                    }
                    for (Finding.Evidence e : f.evidence()) {
                        c.location(e.file(), e.line());
                    }
                });
            }
            case TestGenResult t -> c.fingerprint(t.fingerprint());
            case RemediationResult rm -> {
                c.fingerprint(rm.fingerprint());
                c.citation(rm.decisionPoint());
            }
            default -> throw new IllegalArgumentException("not an agent output record: " + output.getClass().getName());
        }
        return List.copyOf(c.problems);
    }

    private static final class Collector {
        private final GroundingContext ctx;
        private final TreeSet<String> problems = new TreeSet<>();

        Collector(GroundingContext ctx) {
            this.ctx = ctx;
        }

        void step(Step s) {
            location(s.file(), s.line());
        }

        void citation(Citation c) {
            location(c.file(), c.line());
        }

        void fingerprint(String fp) {
            if (!ctx.knownFingerprints().contains(fp)) {
                problems.add("fingerprint " + fp + " is not in the agent input");
            }
        }

        boolean file(String path) {
            try {
                if (!ctx.sources().exists(path)) {
                    problems.add("file " + path + " is not in the source index");
                    return false;
                }
                return true;
            } catch (IllegalArgumentException e) {
                problems.add("path " + path + " is not a canonical repo-relative path");
                return false;
            }
        }

        void location(String path, int line) {
            if (file(path) && ctx.sources().line(path, line).isEmpty()) {
                problems.add("line " + line + " is out of range in " + path);
            }
        }
    }
}
