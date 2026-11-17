package ca.carleton.secpipe.adapter.grype;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import org.junit.jupiter.api.Test;

/** WO-0.1 placeholder: the module compiles and its package-info class loads. */
class SkeletonTest {

    @Test
    void packageInfoLoads() throws ClassNotFoundException {
        Class<?> packageInfo = Class.forName("ca.carleton.secpipe.adapter.grype.package-info");
        assertThat(packageInfo.getPackageName()).isEqualTo("ca.carleton.secpipe.adapter.grype");
    }

    @Test
    void contractsAreOnTheClasspath() {
        assertThat(PipelineGraph.class.getPackageName()).isEqualTo("ca.carleton.secpipe.contracts.pipeline");
    }
}
