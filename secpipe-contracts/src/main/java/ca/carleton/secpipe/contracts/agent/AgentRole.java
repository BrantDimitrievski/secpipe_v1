package ca.carleton.secpipe.contracts.agent;

/** LLM agent roles. JSON form is lower_snake_case. Cheap vs strong default mapping: ADR-10. */
public enum AgentRole {
    RECON_SUMMARY(false), HUNTER(false), CRITIC(false), TESTGEN(false),
    DISPROVER(true), RECORD_VERIFIER(true), REMEDIATION(true);

    private final boolean strongByDefault;
    AgentRole(boolean strongByDefault) { this.strongByDefault = strongByDefault; }
    public boolean strongByDefault() { return strongByDefault; }
}
