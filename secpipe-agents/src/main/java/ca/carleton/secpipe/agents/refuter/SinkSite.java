package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.recon.Location;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.Expression;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One sink call a candidate points at: its file and line, the enclosing method, the call itself, the CWE family
 * ({@code CWE-22}, {@code CWE-78} or {@code CWE-89}) and the arguments that carry the injectable value.
 */
record SinkSite(String file, int line, CallableDeclaration<?> method, Expression call, String family,
                List<Expression> relevantArgs, ScannedTypes types) {

    SinkSite {
        relevantArgs = List.copyOf(relevantArgs);
        types = Objects.requireNonNull(types, "types");
    }

    SinkSite(String file, int line, CallableDeclaration<?> method, Expression call, String family,
             List<Expression> relevantArgs) {
        this(file, line, method, call, family, relevantArgs, ScannedTypes.NONE);
    }

    /** {@link Flow#run} over this site's method, with the scanned types known to the refuter. */
    Optional<Flow.Outcome> run(Node target, Flow.Mode mode) {
        return Flow.run(method, target, mode, types);
    }

    Location at(int controlLine) {
        return Location.of(file, controlLine >= 1 ? controlLine : line);
    }

    String callName() {
        return call.isMethodCallExpr() ? call.asMethodCallExpr().getNameAsString()
                : call.asObjectCreationExpr().getType().getNameAsString();
    }
}
