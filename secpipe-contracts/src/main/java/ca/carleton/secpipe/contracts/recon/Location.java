package ca.carleton.secpipe.contracts.recon;

import ca.carleton.secpipe.contracts.spi.SourceIndex;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A source position: repo-relative POSIX {@code file}, one-based {@code line}, and an optional enclosing
 * {@code scope} written {@code Class#method(ParamTypes)} (e.g. {@code com.acme.UserDao#find(String, int)}).
 * Natural order is (file, line, scope), with an absent scope first.
 */
public record Location(String file, int line, Optional<String> scope) implements Comparable<Location> {

    /** The scope shape: a class name, {@code #}, a method name, and a parenthesised parameter-type list. */
    public static final Pattern SCOPE = Pattern.compile("[^\\s#()]+#[^\\s#()]+\\([^#()]*\\)");

    /** (file, line, scope); absent scope sorts before any present scope. */
    public static final Comparator<Location> ORDER = Comparator.comparing(Location::file)
            .thenComparingInt(Location::line)
            .thenComparing(l -> l.scope().orElse(null), Comparator.nullsFirst(Comparator.naturalOrder()));

    public Location {
        SourceIndex.requireRelativePath(file);
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1 but was " + line);
        }
        Objects.requireNonNull(scope, "scope (use Optional.empty())");
        scope.ifPresent(s -> {
            if (!SCOPE.matcher(s).matches()) {
                throw new IllegalArgumentException("scope must look like Class#method(ParamTypes): " + s);
            }
        });
    }

    /** A location without a scope. */
    public static Location of(String file, int line) {
        return new Location(file, line, Optional.empty());
    }

    /** A location with a scope. */
    public static Location of(String file, int line, String scope) {
        return new Location(file, line, Optional.of(scope));
    }

    @Override
    public int compareTo(Location other) {
        return ORDER.compare(this, other);
    }
}
