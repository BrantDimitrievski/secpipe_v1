package ca.carleton.secpipe.contracts.pipeline;

/** ADR-02. Executes PipelineGraph from START to END. PlainGraphRunner (core) is the reference implementation. */
public interface GraphRunner {
    /** Runs to a terminal state; never throws for node failures (they become incomplete_reason). */
    PipelineState run(PipelineState initial, RunContext ctx);
}
