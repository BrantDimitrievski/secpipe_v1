package ca.carleton.secpipe.agents.disprover;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.agents.prompt.DefaultPromptRenderer;
import org.junit.jupiter.api.Test;

/** WO-G.12: the disprover prompt is a {@code .md} template that {@link DefaultPromptRenderer} loads and hashes. */
class DisproverAgentPromptHashTest {

    @Test
    void promptShaEqualsTheRendererHash() {
        assertThat(DisproverAgent.promptSha256()).matches("[0-9a-f]{64}")
                .isEqualTo(DefaultPromptRenderer.sha256("disprover.v1"));
    }

    @Test
    void promptLoadsFromMarkdownWithoutItsAttributionHeader() {
        String text = DefaultPromptRenderer.load(DisproverAgent.TEMPLATE_ID);
        assertThat(text).startsWith("You did not write this candidate. Try to refute it.")
                .doesNotContain("<!--");
        assertThat(DisproverAgent.instructions()).isEqualTo(text);
        assertThat(DisproverAgent.class.getClassLoader().getResource("prompts/disprover.v1.txt")).isNull();
        assertThat(DisproverAgent.class.getClassLoader().getResource("prompts/disprover.v1.md")).isNotNull();
    }
}
