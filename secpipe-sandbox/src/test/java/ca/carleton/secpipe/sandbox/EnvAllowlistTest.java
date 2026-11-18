package ca.carleton.secpipe.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvAllowlistTest {

    @Test
    void linesAreSortedKeyEqualsValue() {
        List<String> lines = EnvAllowlist.DEFAULT.lines(Map.of("TZ", "UTC", "LANG", "C.UTF-8", "MAVEN_OPTS", ""));
        assertEquals(List.of("LANG=C.UTF-8", "MAVEN_OPTS=", "TZ=UTC"), lines);
    }

    @Test
    void keysOffTheAllowlistAreRefused() {
        Map<String, String> env = Map.of("GITHUB_TOKEN", "ghp_x", "LANG", "C");
        assertEquals(List.of("not on the allowlist: GITHUB_TOKEN"), EnvAllowlist.DEFAULT.violations(env));
        assertThrows(IllegalArgumentException.class, () -> EnvAllowlist.DEFAULT.lines(env));
    }

    @Test
    void bareKeysLineBreaksAndBadNamesAreRefused() {
        Map<String, String> env = new HashMap<>();
        env.put("LANG", null);
        env.put("TZ", "UTC\nGITHUB_TOKEN=x");
        env.put("1BAD", "x");
        List<String> v = EnvAllowlist.DEFAULT.violations(env);
        assertEquals(3, v.size(), v.toString());
        assertThrows(IllegalArgumentException.class, () -> new EnvAllowlist(java.util.Set.of("A-B")));
    }

    @Test
    void envFileIsUtf8NoBomLfOnly(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("env");
        EnvAllowlist.DEFAULT.writeEnvFile(Map.of("LANG", "fr_CA.UTF-8", "TZ", "América/Montréal"), f);
        byte[] bytes = Files.readAllBytes(f);
        assertFalse(bytes.length >= 3 && (bytes[0] & 0xff) == 0xEF, "no BOM");
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("LANG=fr_CA.UTF-8\nTZ=América/Montréal\n", text);
        assertFalse(text.contains("\r"));
        for (String line : text.split("\n")) {
            assertTrue(line.contains("="), "every line is KEY=VALUE: " + line);
        }
    }

    @Test
    void defaultAllowsNothingSecretShaped() {
        for (String name : EnvAllowlist.DEFAULT.names()) {
            assertFalse(name.matches("(?i).*(TOKEN|KEY|SECRET|PASS|CRED|AUTH).*"), name);
        }
    }
}
