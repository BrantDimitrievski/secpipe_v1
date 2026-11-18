package ca.carleton.secpipe.agents.testgen;

import ca.carleton.secpipe.contracts.agent.AgentOutputs.DisproverResult;
import ca.carleton.secpipe.contracts.spi.SourceIndex;

import java.util.List;
import java.util.Objects;

/**
 * Everything the regression-test generator may see for one survivor (PLAN Part 5 A8), built by
 * {@link TestGenContextBuilder}. {@code snippets} hold raw repository text; {@link TestGenAgent} wraps each one with
 * {@code PromptRenderer.untrusted(...)}, never concatenating it into the prompt itself.
 *
 * @param family           weakness family {@code CWE-<n>}
 * @param draft            the survivor draft (disprover result) for the fingerprint
 * @param payloads         payload hints for the family (may be empty)
 * @param testDependencies test-classpath coordinates, e.g. {@code org.junit.jupiter:junit-jupiter:5.11.0}
 * @param snippets         source slices, sorted by path then first line, non-overlapping per file
 */
public record TestGenInput(String family, DisproverResult draft, List<String> payloads, List<String> testDependencies,
                           List<Snippet> snippets) {

    public TestGenInput {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(draft, "draft");
        payloads = List.copyOf(payloads);
        testDependencies = List.copyOf(testDependencies);
        snippets = List.copyOf(snippets);
    }

    public String fingerprint() {
        return draft.fingerprint();
    }

    /** Lines {@code fromLine..toLine} (1-based, inclusive) of {@code path}, joined with {@code \n}. */
    public record Snippet(String path, int fromLine, int toLine, String content) {
        public Snippet {
            SourceIndex.requireRelativePath(path);
            Objects.requireNonNull(content, "content");
            if (fromLine < 1 || toLine < fromLine) {
                throw new IllegalArgumentException("bad line range " + fromLine + "-" + toLine + " for " + path);
            }
        }
    }
}
