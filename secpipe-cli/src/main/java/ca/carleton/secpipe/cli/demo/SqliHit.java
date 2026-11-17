package ca.carleton.secpipe.cli.demo;

import java.util.List;

/**
 * One CWE-89 sink hit from {@link DemoSqliDetector}.
 *
 * @param file       path relative to the scan target, with '/'
 * @param line       1-based line of the sink call
 * @param scope      enclosing type and method, e.g. {@code com.shop.app.LoginServlet#doPost}
 * @param method     sink method name, e.g. {@code executeQuery}
 * @param sinkCall   sink call source text (as printed by JavaParser)
 * @param variables  non-literal operands of the concatenation, sorted
 * @param viaVariable name of the local the query was assigned to, or empty when concatenated in place
 */
public record SqliHit(String file, int line, String scope, String method, String sinkCall, List<String> variables,
                      String viaVariable) {
    public SqliHit {
        variables = List.copyOf(variables);
    }
}
