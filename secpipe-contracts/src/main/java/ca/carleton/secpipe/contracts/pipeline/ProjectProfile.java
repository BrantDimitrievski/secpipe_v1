package ca.carleton.secpipe.contracts.pipeline;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** What setup + recon learned about the target. Paths in lists are repo-relative with '/' separators. */
public record ProjectProfile(Path root, String language, BuildTool buildTool, Set<String> frameworks,
                             List<String> sourceFiles, List<String> testSourceFiles, List<String> manifests, long linesOfCode) {
    public enum BuildTool { MAVEN_WRAPPER, MAVEN, GRADLE_WRAPPER, GRADLE, NONE }
}
