package ca.carleton.secpipe.adapter.builtin;

import ca.carleton.secpipe.contracts.finding.Finding;

import java.util.List;

/** One rule match in one file, before it is turned into a contract {@code Candidate}. */
public record Hit(String ruleId, String cwe, String file, int line, Finding.Score5 severity, Finding.Level3 confidence,
                  String title, String message, String sinkStatement, String sinkScope,
                  List<Finding.TraceStep> trace, String snippet) {
    public Hit {
        trace = List.copyOf(trace);
    }
}
