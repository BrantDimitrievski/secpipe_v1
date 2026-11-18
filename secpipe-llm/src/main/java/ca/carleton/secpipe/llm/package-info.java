/**
 * SecPipe llm: the single LLM assembly point (WO-L.1).
 *
 * <p>{@link ca.carleton.secpipe.llm.LlmClients#assemble} builds one of three {@code LlmClient}s:
 * {@link ca.carleton.secpipe.llm.RoutingLlmClient} (a real provider discovered through {@code LlmProviderFactory},
 * with per-role model routing, retries, model fallback, per-attempt budget accounting and redaction),
 * {@link ca.carleton.secpipe.llm.FakeLlmClient} (CI and tests, JSON fixtures) or
 * {@link ca.carleton.secpipe.llm.NoneLlmClient} (AI off or unavailable).
 */
package ca.carleton.secpipe.llm;
