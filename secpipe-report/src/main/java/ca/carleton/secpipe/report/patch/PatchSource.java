package ca.carleton.secpipe.report.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads a patch artifact by the path recorded in {@code fix_attempts[].patch_artifact} (relative to the output
 * directory). A missing, unreadable or escaping path gives an empty result, never an exception.
 */
@FunctionalInterface
public interface PatchSource {

    /** A source with no patches. */
    PatchSource NONE = artifact -> Optional.empty();

    Optional<String> read(String artifact);

    /** Reads from {@code outDir}; refuses absolute paths and paths that leave the directory. */
    static PatchSource fromDir(Path outDir) {
        Path root = outDir.toAbsolutePath().normalize();
        return artifact -> {
            if (!FixedPatches.isSafeRelative(artifact)) {
                return Optional.empty();
            }
            Path file = root.resolve(artifact).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Optional.empty();
            }
        };
    }
}
