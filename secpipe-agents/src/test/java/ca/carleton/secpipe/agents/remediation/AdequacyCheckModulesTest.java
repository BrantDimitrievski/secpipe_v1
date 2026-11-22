package ca.carleton.secpipe.agents.remediation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** WO-M.5: the JaCoCo report is built from the class files of every touched module, and only from safe paths. */
class AdequacyCheckModulesTest {

    private static final String SINGLE_MODULE_REPORT = "if [ -f " + AdequacyCheck.EXEC + " ] && [ -f "
            + AdequacyCheck.CLI + " ]; then java -jar " + AdequacyCheck.CLI + " report " + AdequacyCheck.EXEC
            + " --classfiles target/classes --xml " + AdequacyCheck.XML + " >/dev/null 2>&1"
            + " || echo 'secpipe: jacoco report failed' >&2; fi\n";

    private static String script(List<String> cmd) {
        return cmd.get(2);
    }

    @Test
    void twoModulesGiveOneClassfilesEntryEachAndSkipMissingDirectories() {
        List<String> cmd = AdequacyCheck.command(List.of("a.B"), List.of("com.shop.core.*", "com.shop.web.*"),
                List.of("core", "web"));
        String s = script(cmd);

        assertTrue(s.contains("for d in 'core/target/classes' 'web/target/classes'; do [ -d \"$d\" ] "
                + "&& cf=\"$cf --classfiles $d\"; done\n"), s);
        assertTrue(s.contains("report " + AdequacyCheck.EXEC + " $cf --xml " + AdequacyCheck.XML), s);
        assertTrue(s.contains("[ -n \"$cf\" ]"), "no report when no class directory exists: " + s);
        assertFalse(s.contains("--classfiles target/classes"), s);
        assertTrue(s.endsWith("exit $rc\n"), s);
        assertEquals("a.B", cmd.get(4));
    }

    @Test
    void aRootClassesDirectoryIsListedNextToModuleOnes() {
        String s = script(AdequacyCheck.command(List.of("a.B"), List.of("x.*"), List.of("", "m/n")));

        assertTrue(s.contains("for d in 'target/classes' 'm/n/target/classes'; do"), s);
    }

    @Test
    void aSingleRootModuleKeepsTheExactPreviousCommand() {
        List<String> classes = List.of("a.B", "c.D");
        List<String> includes = List.of("com.shop.app.*");

        List<String> legacy = AdequacyCheck.command(classes, includes);

        assertEquals(legacy, AdequacyCheck.command(classes, includes, List.of("")));
        assertEquals(legacy, AdequacyCheck.command(classes, includes, List.of()));
        assertTrue(script(legacy).contains("rc=$?\n" + SINGLE_MODULE_REPORT + "exit $rc\n"), script(legacy));
        assertFalse(script(legacy).contains("for d in"), script(legacy));
    }

    @Test
    void modulesAreTheDirectoriesBeforeSrcMainJavaOfProductionFilesOnly() {
        assertEquals(Optional.of(List.of("core", "web")), AdequacyCheck.modules(List.of(
                "web/src/main/java/a/W.java", "core/src/main/java/a/C.java", "core/src/main/java/a/D.java",
                "core/src/test/java/a/CTest.java", "docs/readme.md")));
        assertEquals(Optional.of(List.of("", "m/n")), AdequacyCheck.modules(List.of(
                "src/main/java/a/R.java", "m/n/src/main/java/b/N.java")));
        assertEquals(Optional.of(List.of()), AdequacyCheck.modules(List.of("src/test/java/a/T.java")));
    }

    @Test
    void aHostileModulePathYieldsNoModulesAndNoCommand() {
        for (String hostile : List.of("a;rm -rf x/src/main/java/A.java", "../x/src/main/java/A.java",
                "a/../b/src/main/java/A.java", "./src/main/java/A.java", "a b/src/main/java/A.java",
                "a$(id)/src/main/java/A.java", "a'/src/main/java/A.java", "a//b/src/main/java/A.java",
                "/abs/src/main/java/A.java")) {
            assertEquals(Optional.empty(), AdequacyCheck.modules(List.of("core/src/main/java/ok/B.java", hostile)),
                    hostile);
        }
        for (String module : List.of("a;rm -rf x", "..", "../x", "a/./b", "a b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> AdequacyCheck.command(List.of("a.B"), List.of("x.*"), List.of("core", module)), module);
        }
    }
}
