package ca.carleton.secpipe.contracts.config;

import java.util.Objects;

/**
 * A configuration problem the user can fix (WO-C.4). The message is two lines in the MVP style:
 * the problem, then {@code hint: <what to do>}. The CLI prints {@link #getMessage()} and exits 2.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String problem;
    private final String hint;

    public ConfigException(String problem, String hint) {
        super(Objects.requireNonNull(problem, "problem") + "\n"
                + "hint: " + Objects.requireNonNull(hint, "hint"));
        this.problem = problem;
        this.hint = hint;
    }

    /** The one-line problem, without the hint. */
    public String problem() {
        return problem;
    }

    /** The one-line hint, without the {@code hint: } prefix. */
    public String hint() {
        return hint;
    }
}
