package ca.carleton.secpipe.core.normalize;

import java.util.Objects;

/** Canonical spelling of {@code Class#method(ParamTypes)} sink scopes, so equivalent signatures hash alike. */
public final class Scopes {

    private Scopes() { }

    /**
     * Collapses whitespace runs; keeps a single space only between two identifier characters (for example
     * {@code final String}), drops it next to punctuation. {@code "Foo # bar( String , Map<K, V> [] )"} becomes
     * {@code "Foo#bar(String,Map<K,V>[])"}.
     */
    public static String canonical(String scope) {
        Objects.requireNonNull(scope, "scope");
        String s = scope.strip();
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && out.length() > 0 && Character.isJavaIdentifierPart(out.charAt(out.length() - 1))
                    && Character.isJavaIdentifierPart(c)) {
                out.append(' ');
            }
            pendingSpace = false;
            out.append(c);
        }
        return out.toString();
    }
}
