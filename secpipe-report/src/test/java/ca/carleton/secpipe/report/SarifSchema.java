package ca.carleton.secpipe.report;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates SARIF text against the schema in test resources (see the note inside that file). */
final class SarifSchema {

    private static final String ID = "https://secpipe.dev/test/sarif-2.1.0-subset.schema.json";
    private static final Schema SCHEMA = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_7,
                    builder -> builder.schemas(Map.of(ID, Fixtures.resource("sarif-2.1.0-subset.schema.json"))))
            .getSchema(SchemaLocation.of(ID));

    private SarifSchema() {
    }

    /** Schema errors as readable lines; empty when valid. */
    static List<String> errors(String sarif) {
        JsonNode tree = JsonMapper.builder().build().readTree(sarif);
        return SCHEMA.validate(tree).stream().map(Error::toString).sorted().toList();
    }
}
