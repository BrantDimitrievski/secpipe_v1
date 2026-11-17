package ca.carleton.secpipe.contracts.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Shared helpers for the ConfigResolver*Test classes. */
final class ConfigTestSupport {

    private ConfigTestSupport() {
    }

    static SecPipeConfig resolve(Map<String, String> overrides, Map<String, String> env) {
        return ConfigResolver.resolve(overrides, env, Optional.empty());
    }

    static SecPipeConfig resolve(Map<String, String> overrides, Map<String, String> env, Path yaml) {
        return ConfigResolver.resolve(overrides, env, Optional.of(yaml));
    }

    static ConfigException failure(Map<String, String> overrides, Map<String, String> env, Optional<Path> yaml) {
        return assertThrows(ConfigException.class, () -> ConfigResolver.resolve(overrides, env, yaml));
    }

    /** A fixture under src/test/resources/config/. */
    static Path fixture(String name) {
        URL url = ConfigTestSupport.class.getResource("/config/" + name);
        if (url == null) {
            throw new IllegalStateException("missing test fixture config/" + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path writeYaml(Path dir, String content) throws IOException {
        return Files.writeString(dir.resolve("secpipe.yml"), content);
    }

    /** Mutable map built from alternating key/value arguments (values may be empty strings for flags). */
    static Map<String, String> map(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("odd number of arguments");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
