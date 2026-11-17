package ca.carleton.secpipe.contracts.pipeline;

/**
 * A step of the pipeline graph. Implementations have a public no-arg constructor and live at the FQCN listed in
 * PipelineGraph.NODES, so the engine can load them reflectively without core depending on agents (Part 3).
 */
public interface PipelineNode {

    /** Primary id in PipelineGraph.NODES. A class may be registered under several graph ids (normalize, normalize_llm);
     *  the engine keys execution by graph id and passes it as RunContext-independent state, never by this value. */
    String id();

    /** True if the node needs an LLM; the engine skips it (transition SKIPPED) when llm-provider=none. */
    boolean requiresLlm();

    /** True if the node needs SandboxRunner; when unavailable the node still runs and must record blockers. */
    boolean usesSandbox();

    Result apply(PipelineState state, RunContext ctx) throws Exception;

    /** label must be one of the outgoing labels declared for this node in PipelineGraph.TRANSITIONS. */
    record Result(String label, StateDelta delta) {
        public static Result next(StateDelta d) { return new Result(PipelineGraph.NEXT, d); }
    }
}
