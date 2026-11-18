package ca.carleton.secpipe.agents.refuter;

import java.util.Objects;

/**
 * Abstract value of a local in {@link Flow}: an exact compile-time constant, an untainted value whose exact content
 * is unknown, a possibly tainted value, or a declared but unassigned local. {@code line} is the latest source line
 * of a definition that contributed to the value (0 for a bare literal).
 */
sealed interface Val permits Val.Const, Val.Safe, Val.Tainted, Val.Unassigned {

    /** Untainted for refutation purposes. */
    default boolean safe() {
        return this instanceof Const || this instanceof Safe;
    }

    int line();

    Val withLine(int line);

    record Const(Object value, int line) implements Val {
        public Const {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public Val withLine(int l) {
            return new Const(value, l);
        }
    }

    record Safe(int line) implements Val {
        @Override
        public Val withLine(int l) {
            return new Safe(l);
        }
    }

    record Tainted() implements Val {
        @Override
        public int line() {
            return 0;
        }

        @Override
        public Val withLine(int l) {
            return this;
        }
    }

    record Unassigned() implements Val {
        @Override
        public int line() {
            return 0;
        }

        @Override
        public Val withLine(int l) {
            return this;
        }
    }

    Val TAINTED = new Tainted();
    Val UNASSIGNED = new Unassigned();

    static Val join(Val a, Val b) {
        if (a instanceof Unassigned) {
            return b;
        }
        if (b instanceof Unassigned) {
            return a;
        }
        if (a instanceof Tainted || b instanceof Tainted) {
            return TAINTED;
        }
        int line = Math.max(a.line(), b.line());
        if (a instanceof Const ca && b instanceof Const cb && ca.value().equals(cb.value())) {
            return new Const(ca.value(), line);
        }
        return new Safe(line);
    }
}
