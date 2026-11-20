package ca.carleton.secpipe.agents.testgen;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import org.junit.jupiter.api.Test;

/** WO-G.12: the testgen prompt is a {@code .md} template that {@link DefaultPromptRenderer} loads and hashes. */
class TestGenAgentPromptHashTest {

    @Test
    void promptShaEqualsTheRendererHash() {
        assertThat(TestGenAgent.promptSha256()).matches("[0-9a-f]{64}")
                .isEqualTo(DefaultPromptRenderer.sha256("testgen.v1"));
    }

    @Test
    void promptLoadsFromMarkdownAndKeepsTheSafeApiGuidance() {
        String text = DefaultPromptRenderer.load(TestGenAgent.TEMPLATE_ID);
        assertThat(text).contains("===SYSTEM===").contains("===USER===").doesNotContain("<!--")
                .contains("record BOTH the unsafe and the safe API")
                .contains("Connection.prepareStatement(String) + setString")
                .contains("new ProcessBuilder(List<String>)")
                .contains("normalize() followed by a startsWith(base) check")
                .contains("ObjectInputFilter");
        assertThat(TestGenAgent.class.getClassLoader().getResource("prompts/testgen.v1.txt")).isNull();
        assertThat(TestGenAgent.class.getClassLoader().getResource("prompts/testgen.v1.md")).isNotNull();
    }
}
