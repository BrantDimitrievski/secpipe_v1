package ca.carleton.secpipe.llm.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** WO-0.1 placeholder: the module compiles and its package-info class loads. */
class SkeletonTest {

    @Test
    void packageInfoLoads() throws ClassNotFoundException {
        Class<?> packageInfo = Class.forName("ca.carleton.secpipe.llm.anthropic.package-info");
        assertThat(packageInfo.getPackageName()).isEqualTo("ca.carleton.secpipe.llm.anthropic");
    }
}
