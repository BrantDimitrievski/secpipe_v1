package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.config.SecPipeConfig;
import ca.carleton.secpipe.contracts.pipeline.RunContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Plain {@link RunContext.RunLog}: formats each message as {@code [level] message} and hands it to a sink chosen by
 * the caller (the CLI passes stderr; library code never touches {@code System.out}). Verbosity filters lines:
 * {@code QUIET} keeps warnings, {@code NORMAL} adds info, {@code VERBOSE} adds debug.
 *
 * <p>This version does not redact. WO-Y.2 adds secret redaction in front of the sink.
 */
public final class DefaultRunLog implements RunContext.RunLog {

    private final SecPipeConfig.Verbosity verbosity;
    private final Consumer<String> sink;

    public DefaultRunLog(SecPipeConfig.Verbosity verbosity, Consumer<String> sink) {
        this.verbosity = Objects.requireNonNull(verbosity, "verbosity");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** A log that drops every line. */
    public static DefaultRunLog discarding() {
        return new DefaultRunLog(SecPipeConfig.Verbosity.QUIET, line -> { });
    }

    /** A log that keeps every line (debug included) in memory; for tests and debugging. */
    public static Collecting collecting() {
        return new Collecting();
    }

    @Override
    public void info(String msg) {
        if (verbosity != SecPipeConfig.Verbosity.QUIET) {
            emit("info", msg);
        }
    }

    @Override
    public void warn(String msg) {
        emit("warn", msg);
    }

    @Override
    public void debug(String msg) {
        if (verbosity == SecPipeConfig.Verbosity.VERBOSE) {
            emit("debug", msg);
        }
    }

    private void emit(String level, String msg) {
        sink.accept("[" + level + "] " + msg);
    }

    /** A verbose log that records its formatted lines. Thread-safe. */
    public static final class Collecting implements RunContext.RunLog {
        private final List<String> lines = new ArrayList<>();
        private final DefaultRunLog delegate = new DefaultRunLog(SecPipeConfig.Verbosity.VERBOSE, this::add);

        private Collecting() { }

        private synchronized void add(String line) {
            lines.add(line);
        }

        /** The lines logged so far, in order. */
        public synchronized List<String> lines() {
            return List.copyOf(lines);
        }

        @Override
        public void info(String msg) {
            delegate.info(msg);
        }

        @Override
        public void warn(String msg) {
            delegate.warn(msg);
        }

        @Override
        public void debug(String msg) {
            delegate.debug(msg);
        }
    }
}
