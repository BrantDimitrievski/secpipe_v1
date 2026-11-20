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

/**
 * Validates SARIF text against the official OASIS SARIF 2.1.0 schema (draft-04), vendored byte-identical in test
 * resources; see {@code THIRD_PARTY_NOTICES.md}.
 */
final class SarifSchema {

    static final String RESOURCE = "sarif-schema-2.1.0.json";
    /** The schema's own {@code id}. */
    private static final String ID =
            "https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/schemas/sarif-schema-2.1.0.json";
    private static final Schema SCHEMA = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_4,
                    builder -> builder.schemas(Map.of(ID, Fixtures.resource(RESOURCE))))
            .getSchema(SchemaLocation.of(ID));

    private SarifSchema() {
    }

    /** Schema errors as readable lines; empty when valid. */
    static List<String> errors(String sarif) {
        JsonNode tree = JsonMapper.builder().build().readTree(sarif);
        return SCHEMA.validate(tree).stream().map(Error::toString).sorted().toList();
    }
}
