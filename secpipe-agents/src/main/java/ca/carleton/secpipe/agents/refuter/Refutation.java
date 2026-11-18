package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.recon.Location;

import java.util.Objects;

/**
 * Why a candidate was refuted: the rule id ({@code refuter.<pattern>}, recorded as
 * {@code verification.disprover.rule}), the location of the control that defeats the claim, and a one-line reason.
 */
public record Refutation(String rule, Location controlLocation, String reason) {

    public static final String VALUE_OVERWRITTEN = "refuter.value_overwritten";
    public static final String CONSTANT_VALUE = "refuter.constant_value";
    public static final String DEAD_BRANCH = "refuter.dead_branch";
    public static final String PARAMETERIZED = "refuter.parameterized";

    public Refutation {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(controlLocation, "controlLocation");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
