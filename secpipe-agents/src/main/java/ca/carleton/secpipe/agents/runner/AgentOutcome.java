package ca.carleton.secpipe.agents.runner;

import ca.carleton.secpipe.contracts.spi.LlmClient.ModelInfo;

import java.util.Objects;

/**
 * Result of {@link AgentRunner#run}. {@code attempts} counts the LLM calls the runner started for this item.
 *
 * @param <O> the agent output record type
 */
public sealed interface AgentOutcome<O> permits AgentOutcome.Ok, AgentOutcome.Dropped, AgentOutcome.Unavailable {

    int attempts();

    /** A schema-valid, grounded output from the fresh agent {@code agentId} ({@code <role>-<8hex>}). */
    record Ok<O>(O value, int attempts, String agentId, ModelInfo model) implements AgentOutcome<O> {
        public Ok {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(model, "model");
        }
    }

    /**
     * Every attempt returned malformed, schema-invalid or ungrounded output. The caller marks the unit
     * {@code blocked} ("agent output invalid") or the candidate {@code needs_validation}.
     */
    record Dropped<O>(String reason, int attempts) implements AgentOutcome<O> {
        public Dropped {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * No usable LLM: AI is off ({@code mode()==NONE}, 0 attempts), the agent-call budget is exhausted, or the
     * provider is unavailable. The caller records {@code needs_validation} / {@code incomplete}.
     */
    record Unavailable<O>(String reason, int attempts) implements AgentOutcome<O> {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
