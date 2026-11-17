package ca.carleton.secpipe.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** WO-0.1 placeholder: proves the committed contracts compile and their schemas are packaged. */
class SkeletonTest {

    @Test
    void contractTypesAndSchemasAreOnTheClasspath() throws IOException {
        assertThat(PipelineGraph.class.getPackageName()).isEqualTo("ca.carleton.secpipe.contracts.pipeline");
        try (InputStream schema = PipelineGraph.class.getResourceAsStream("/schemas/findings.schema.json")) {
            assertThat(schema).as("findings.schema.json packaged in secpipe-contracts").isNotNull();
            assertThat(schema.readAllBytes()).isNotEmpty();
        }
    }
}
