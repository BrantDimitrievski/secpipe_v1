package ca.carleton.secpipe.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ca.carleton.secpipe.contracts.config.ConfigResolver;
import ca.carleton.secpipe.contracts.config.SecPipeConfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixModeTest {

    @TempDir
    Path tmp;

    record Res(int exit, String out, String err) { }

    private Res scan(String... extra) {
        String[] base = {"scan", ScanCommandTest.FIXTURE.toString(), "--no-ai", "--no-sandbox", "-q",
            "--fail-on", "none", "-o", tmp.resolve("out-" + extra.length + "-" + String.join("_", extra)).toString()};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = SecPipeCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of());
        return new Res(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void prExitsTwoWithAOneLineProblemAndAHint() {
        Res r = scan("--enable-fixes", "pr");
        assertThat(r.exit()).isEqualTo(SecPipeCli.EXIT_USAGE).isEqualTo(2);
        assertThat(r.err()).isEqualTo("error: --enable-fixes pr needs the GitHub Action (not built)"
                + System.lineSeparator() + "hint: use suggest or false" + System.lineSeparator());
        assertThat(tmp.resolve("out-2---enable-fixes_pr")).doesNotExist();
    }

    @Test
    void falseAndSuggestAreAcceptedAndRun() {
        for (String mode : new String[] {"false", "suggest", "true"}) {
            Res r = scan("--enable-fixes", mode);
            assertThat(r.exit()).as(mode + ": " + r.err()).isZero();
            assertThat(r.err()).as(mode).doesNotContain("--enable-fixes");
        }
    }

    @Test
    void defaultFixModeStaysSuggest() {
        SecPipeConfig cfg = ConfigResolver.resolve(Map.of(), Map.of(), Optional.empty());
        assertThat(cfg.enableFixes()).isEqualTo(SecPipeConfig.FixMode.SUGGEST);
    }
}
