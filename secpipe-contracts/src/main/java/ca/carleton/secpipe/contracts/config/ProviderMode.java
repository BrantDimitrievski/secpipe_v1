package ca.carleton.secpipe.contracts.config;

/**
 * --llm-provider values. FAKE is test-only (CI) and is rejected by the CLI unless SECPIPE_ALLOW_FAKE_LLM=1.
 * COPILOT replaces the brief's "github-models" mode: GitHub Models was retired on 2026-07-30 (ADR-10).
 */
public enum ProviderMode {
    NONE("none"), COPILOT("copilot"), ANTHROPIC("anthropic"), OPENAI("openai"),
    OPENAI_COMPATIBLE("openai-compatible"), OLLAMA("ollama"), FAKE("fake");

    private final String cliName;
    ProviderMode(String cliName) { this.cliName = cliName; }
    public String cliName() { return cliName; }

    public static ProviderMode fromCli(String s) {
        for (ProviderMode m : values()) if (m.cliName.equals(s)) return m;
        throw new IllegalArgumentException("unknown llm provider: " + s);
    }
}
