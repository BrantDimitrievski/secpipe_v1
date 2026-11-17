package ca.carleton.secpipe.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Minimal bootstrap entry point (WO-0.1) so CI can run {@code java -jar secpipe.jar --version}.
 *
 * <p>WO-K.13 replaces this class with the picocli command tree from {@code docs/PLAN.md} §4.3. Exit codes follow
 * that contract: 0 for OK, 2 for usage errors (a one-line problem plus a one-line {@code hint:}).
 */
public final class SecPipeCli {

    static final int EXIT_OK = 0;
    static final int EXIT_USAGE = 2;

    private static final String VERSION_RESOURCE = "secpipe-version.properties";

    private SecPipeCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && ("--version".equals(args[0]) || "-V".equals(args[0]))) {
            out.println(version().orElse("unknown"));
            return EXIT_OK;
        }
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println("Usage: secpipe --version | --help");
            out.println("This is the WO-0.1 bootstrap build; the scan, validate, doctor, diff, report and github");
            out.println("commands arrive with WO-K.13.");
            return EXIT_OK;
        }
        err.println("error: unsupported arguments in this bootstrap build: " + String.join(" ", args));
        err.println("hint: run 'secpipe --help'");
        return EXIT_USAGE;
    }

    static Optional<String> version() {
        try (InputStream in = SecPipeCli.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            Properties props = new Properties();
            props.load(in);
            String value = props.getProperty("version", "").strip();
            return value.isEmpty() || value.startsWith("${") ? Optional.empty() : Optional.of(value);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
