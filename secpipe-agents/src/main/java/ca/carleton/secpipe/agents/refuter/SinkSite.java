package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.recon.Location;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.Expression;

import java.util.List;

/**
 * One sink call a candidate points at: its file and line, the enclosing method, the call itself, the CWE family
 * ({@code CWE-22}, {@code CWE-78} or {@code CWE-89}) and the arguments that carry the injectable value.
 */
record SinkSite(String file, int line, CallableDeclaration<?> method, Expression call, String family,
                List<Expression> relevantArgs) {

    SinkSite {
        relevantArgs = List.copyOf(relevantArgs);
    }

    Location at(int controlLine) {
        return Location.of(file, controlLine >= 1 ? controlLine : line);
    }

    String callName() {
        return call.isMethodCallExpr() ? call.asMethodCallExpr().getNameAsString()
                : call.asObjectCreationExpr().getType().getNameAsString();
    }
}
