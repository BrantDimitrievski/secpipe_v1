package ca.carleton.secpipe.contracts.spi;

import ca.carleton.secpipe.contracts.agent.AgentRole;

/**
 * Feature O. Agent-call budget shared by all LLM work in one run. Every provider attempt (including retries and
 * fallbacks) consumes one call. Critic and verifier calls are reserved before hunters are assigned.
 */
public interface Budget {
    int max();
    int used();
    int remaining();
    /** Reserve n calls for a role; returns how many could actually be reserved. */
    int reserve(AgentRole role, int n);
    /** Consume one call, preferring the role's reservation; false if none available. */
    boolean tryAcquire(AgentRole role);
    boolean exhausted();
    /** Sandbox executions are budgeted separately (max-sandbox-runs). */
    boolean tryAcquireSandboxRun();
}
