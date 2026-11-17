package ca.carleton.secpipe.contracts.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pipeline topology as data (§2.4). Node implementations are loaded by FQCN; a missing class makes the engine
 * record "node <id> not installed" and follow the SKIPPED edge (so partially built pipelines still run end to end).
 */
public final class PipelineGraph {
    private PipelineGraph() { }

    public static final String START = "setup";
    public static final String END = "__end__";

    // transition labels
    public static final String NEXT = "next";
    public static final String SKIPPED = "skipped";      // engine-generated when a node is skipped or missing
    public static final String LOOP = "loop";
    public static final String DONE = "done";

    /** node id -> FQCN (owner WO in comment). */
    public static final Map<String, String> NODES = ordered(
            "setup",          "ca.carleton.secpipe.core.setup.SetupNode",                       // WO-K.3
            "recon",          "ca.carleton.secpipe.core.recon.ReconNode",                       // WO-K.4
            "recon_summary",  "ca.carleton.secpipe.agents.recon.ArchitectureSummaryNode",       // WO-G.9 (LLM, optional)
            "detect",         "ca.carleton.secpipe.core.detect.DetectNode",                     // WO-K.6
            "normalize",      "ca.carleton.secpipe.core.normalize.NormalizeNode",               // WO-K.8
            "hunt",           "ca.carleton.secpipe.agents.hunter.HuntWaveNode",                 // WO-G.2 (LLM)
            "critic",         "ca.carleton.secpipe.agents.critic.CriticNode",                   // WO-G.3 (LLM)
            "normalize_llm",  "ca.carleton.secpipe.core.normalize.NormalizeNode",               // WO-K.8 (same class, re-entry)
            "refute",         "ca.carleton.secpipe.agents.refuter.DeterministicRefuterNode",    // WO-G.5
            "disprove",       "ca.carleton.secpipe.agents.disprover.DisproverNode",             // WO-G.4 (LLM)
            "test_evidence",  "ca.carleton.secpipe.agents.evidence.TestEvidenceNode",           // WO-G.7 (uses WO-G.6)
            "score",          "ca.carleton.secpipe.core.scoring.ScoringNode",                   // WO-K.9
            "remediate",      "ca.carleton.secpipe.agents.remediation.RemediationLoopNode",     // WO-M.1 (LLM; uses WO-G.10, WO-M.2)
            "verify_records", "ca.carleton.secpipe.agents.verifier.RecordVerifierNode",         // WO-G.8 (LLM)
            "finalize",       "ca.carleton.secpipe.core.finalize.FinalizeNode",                 // WO-K.11
            "report",         "ca.carleton.secpipe.report.ReportNode");                         // WO-R.1 (+R.2, R.3)

    /** node id -> (label -> next node id). Every node implicitly has SKIPPED -> same target as NEXT unless listed. */
    public static final Map<String, Map<String, String>> TRANSITIONS = Map.ofEntries(
            Map.entry("setup",          Map.of(NEXT, "recon")),
            Map.entry("recon",          Map.of(NEXT, "recon_summary")),
            Map.entry("recon_summary",  Map.of(NEXT, "detect")),
            Map.entry("detect",         Map.of(NEXT, "normalize")),
            Map.entry("normalize",      Map.of(NEXT, "hunt")),
            // hunt: LOOP never used; NEXT -> critic; DONE (no planned units / budget) -> normalize_llm
            Map.entry("hunt",           Map.of(NEXT, "critic", DONE, "normalize_llm", SKIPPED, "normalize_llm")),
            // critic: LOOP -> another hunter wave (missing units or reassignments, profile cap not reached, budget left)
            Map.entry("critic",         Map.of(LOOP, "hunt", DONE, "normalize_llm", SKIPPED, "normalize_llm")),
            Map.entry("normalize_llm",  Map.of(NEXT, "refute")),
            Map.entry("refute",         Map.of(NEXT, "disprove")),
            Map.entry("disprove",       Map.of(NEXT, "test_evidence")),
            Map.entry("test_evidence",  Map.of(NEXT, "score")),
            Map.entry("score",          Map.of(NEXT, "remediate")),
            // remediate loops internally over attempts (bounded by max-fix-attempts); single NEXT edge
            Map.entry("remediate",      Map.of(NEXT, "verify_records")),
            Map.entry("verify_records", Map.of(NEXT, "finalize")),
            Map.entry("finalize",       Map.of(NEXT, "report")),
            Map.entry("report",         Map.of(NEXT, END)));

    public static final List<String> REQUIRED_FOR_COMPLETE = List.of("setup", "detect", "normalize", "finalize", "report");

    private static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return java.util.Collections.unmodifiableMap(m);
    }
}
