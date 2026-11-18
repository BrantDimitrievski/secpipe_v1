package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NodeLoaderTest {

    private final NodeLoader loader = new NodeLoader(Map.of(
            "setup", StubNodes.Next.class.getName(),
            "recon", StubNodes.NoDefaultCtor.class.getName(),
            "detect", StubNodes.NotANode.class.getName(),
            "normalize", "ca.carleton.secpipe.core.engine.NoSuchNode"), NodeLoaderTest.class.getClassLoader());

    @Test
    void loadsARegisteredNodeAsANewInstanceEachTime() {
        PipelineNode first = loader.load("setup").orElseThrow();
        PipelineNode second = loader.load("setup").orElseThrow();

        assertThat(first.getClass()).isEqualTo(StubNodes.Next.class);
        assertThat(first == second).isFalse();
    }

    @Test
    void explainsWhyANodeCannotBeLoaded() {
        assertThat(loader.lookup("report"))
                .isEqualTo(new NodeLoader.Lookup.Missing("node report not installed: no class registered for it"));
        assertThat(loader.lookup("normalize")).isEqualTo(new NodeLoader.Lookup.Missing(
                "node normalize not installed (ca.carleton.secpipe.core.engine.NoSuchNode)"));
        assertThat(loader.lookup("recon")).isEqualTo(new NodeLoader.Lookup.Missing("node recon not loadable: "
                + StubNodes.NoDefaultCtor.class.getName() + " has no public no-arg constructor"));
        assertThat(loader.lookup("detect")).isEqualTo(new NodeLoader.Lookup.Missing("node detect not loadable: "
                + StubNodes.NotANode.class.getName() + " does not implement PipelineNode"));
        assertThat(loader.load("detect")).isEmpty();
    }

    @Test
    void defaultLoaderUsesThePipelineGraphTable() {
        // No node classes exist in secpipe-core's test classpath under the PipelineGraph FQCNs owned by other
        // modules (agents, report), so those resolve to "not installed" rather than throwing.
        NodeLoader defaults = new NodeLoader();
        assertThat(defaults.lookup("report")).isEqualTo(new NodeLoader.Lookup.Missing(
                "node report not installed (" + PipelineGraph.NODES.get("report") + ")"));
    }
}
