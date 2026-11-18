package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.json.ContractJson;

/** The standard renderer. Findings JSON goes through {@link ContractJson}; SARIF and Markdown are built here. */
public final class DefaultReportRenderer implements ReportRenderer {

    @Override
    public String json(ReportInputs inputs) {
        return ContractJson.writeFindings(inputs.findings());
    }

    @Override
    public String sarif(ReportInputs inputs) {
        return SarifWriter.render(inputs);
    }

    @Override
    public String markdown(ReportInputs inputs) {
        return MarkdownWriter.render(inputs);
    }
}
