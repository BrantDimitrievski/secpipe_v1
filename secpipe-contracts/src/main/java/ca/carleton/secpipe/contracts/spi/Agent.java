package ca.carleton.secpipe.contracts.spi;

import ca.carleton.secpipe.contracts.agent.AgentRole;

/**
 * Feature A. A stateless LLM agent: builds one bounded request from its input and declares the single JSON object
 * type it must return. Agents never call the LLM, read files, or write state themselves: AgentRunner (WO-G.1) does
 * budget → privacy filter → LlmClient → strict single-object parse → schema → grounding → fresh retry or drop.
 *
 * @param <I> the bounded context (exactly what the agent may see — Part 5 lists it per agent)
 * @param <O> a record from ca.carleton.secpipe.contracts.agent.AgentOutputs
 */
public interface Agent<I, O> {

    AgentRole role();

    /** e.g. "agent/disprover-result.schema.json" */
    String outputSchemaId();

    Class<O> outputType();

    /** Prompt template id under secpipe-agents/src/main/resources/prompts/, e.g. "disprover.v1". */
    String promptTemplateId();

    /** Pure function of the input. Repo text must be wrapped by the caller-supplied UntrustedBlock renderer (ADR-08). */
    LlmClient.Request buildRequest(I input, PromptRenderer renderer);

    /** Rendering helpers provided by WO-G.1; agents must not concatenate raw repo text into prompts. */
    interface PromptRenderer {
        String template(String templateId, java.util.Map<String, String> vars);
        String untrusted(String path, int fromLine, int toLine, String content);
    }
}
