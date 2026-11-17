package ca.carleton.secpipe.contracts.spi;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Host facts detected once per run by the setup node (NFR-1). */
public record EnvironmentInfo(String os, String arch, String javaVersion, Optional<Path> javaHome,
                              Optional<String> mavenVersion, boolean mavenWrapper, boolean dockerAvailable,
                              Optional<String> dockerVersion, boolean githubActions, Map<String, Path> toolsOnPath) { }
