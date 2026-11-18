package ca.carleton.secpipe.contracts.config;

/**
 * --llm-provider values. FAKE is test-only (CI) and is rejected by the CLI unless SECPIPE_ALLOW_FAKE_LLM=1.
 * There is no GitHub-native mode: GitHub Models was retired on 2026-07-30 and spike S.2 found no safe replacement
 * (WO-CC.1, ADR-10). The AI modes are NONE plus bring-your-own-key.
 */
public enum ProviderMode {
    NONE("none"), ANTHROPIC("anthropic"), OPENAI("openai"),
    OPENAI_COMPATIBLE("openai-compatible"), OLLAMA("ollama"), FAKE("fake");

    private final String cliName;
    ProviderMode(String cliName) { this.cliName = cliName; }
    public String cliName() { return cliName; }

    public static ProviderMode fromCli(String s) {
        for (ProviderMode m : values()) if (m.cliName.equals(s)) return m;
        throw new IllegalArgumentException("unknown llm provider: " + s);
    }
}
