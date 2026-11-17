package ca.carleton.secpipe.core.env;

import java.util.List;

/** Structured result of the environment checks. The CLI only formats it. */
public record DoctorReport(List<Check> checks) {

    public enum Status { OK, INFO, WARN, FAIL }

    /** One probe result. {@code hint} is empty when there is nothing to fix. */
    public record Check(String id, Status status, String detail, String hint) { }

    public DoctorReport {
        checks = List.copyOf(checks);
    }

    /** True when no check failed (warnings and info do not block a scan). */
    public boolean ready() {
        return checks.stream().noneMatch(c -> c.status() == Status.FAIL);
    }

    public long failures() {
        return checks.stream().filter(c -> c.status() == Status.FAIL).count();
    }
}
