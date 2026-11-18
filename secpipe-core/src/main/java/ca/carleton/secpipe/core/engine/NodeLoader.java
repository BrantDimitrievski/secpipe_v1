package ca.carleton.secpipe.core.engine;

import ca.carleton.secpipe.contracts.pipeline.PipelineGraph;
import ca.carleton.secpipe.contracts.pipeline.PipelineNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads pipeline nodes reflectively by FQCN (Part 3: core never depends on agents or report at compile time). The
 * default table is {@link PipelineGraph#NODES}; a node WO only has to create its class at the listed FQCN.
 *
 * <p>Every call creates a new instance through the public no-arg constructor. A missing or broken class is not an
 * error: {@link #lookup} explains why, and the runner follows the node's SKIPPED edge.
 */
public final class NodeLoader {

    private final Map<String, String> fqcnById;
    private final ClassLoader classLoader;

    /** Loads from {@link PipelineGraph#NODES} with this class's loader. */
    public NodeLoader() {
        this(PipelineGraph.NODES, NodeLoader.class.getClassLoader());
    }

    /** Loads from a custom id → FQCN table (tests register stub nodes this way). */
    public NodeLoader(Map<String, String> fqcnById, ClassLoader classLoader) {
        this.fqcnById = Map.copyOf(fqcnById);
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /** A new instance of the node registered under graph id {@code id}, or empty if it cannot be loaded. */
    public Optional<PipelineNode> load(String id) {
        return lookup(id) instanceof Lookup.Found f ? Optional.of(f.node()) : Optional.empty();
    }

    /** Like {@link #load}, but says why a node is missing. */
    public Lookup lookup(String id) {
        Objects.requireNonNull(id, "id");
        String fqcn = fqcnById.get(id);
        if (fqcn == null) {
            return new Lookup.Missing("node " + id + " not installed: no class registered for it");
        }
        Class<?> type;
        try {
            type = Class.forName(fqcn, true, classLoader);
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return new Lookup.Missing("node " + id + " not installed (" + fqcn + ")");
        } catch (LinkageError e) {
            return new Lookup.Missing("node " + id + " not loadable: " + fqcn + " failed to initialize: " + e);
        }
        if (!PipelineNode.class.isAssignableFrom(type)) {
            return new Lookup.Missing("node " + id + " not loadable: " + fqcn + " does not implement PipelineNode");
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            return new Lookup.Missing("node " + id + " not loadable: " + fqcn + " is abstract");
        }
        try {
            Constructor<?> ctor = type.getConstructor();
            return new Lookup.Found((PipelineNode) ctor.newInstance());
        } catch (NoSuchMethodException e) {
            return new Lookup.Missing("node " + id + " not loadable: " + fqcn + " has no public no-arg constructor");
        } catch (InvocationTargetException e) {
            return new Lookup.Missing("node " + id + " not loadable: constructor of " + fqcn + " threw "
                    + e.getCause());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return new Lookup.Missing("node " + id + " not loadable: " + fqcn + ": " + e);
        }
    }

    /** The result of {@link #lookup}. */
    public sealed interface Lookup {
        /** The node was loaded. */
        record Found(PipelineNode node) implements Lookup {
            public Found {
                Objects.requireNonNull(node, "node");
            }
        }

        /** The node could not be loaded; {@code reason} becomes a run note. */
        record Missing(String reason) implements Lookup {
            public Missing {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
