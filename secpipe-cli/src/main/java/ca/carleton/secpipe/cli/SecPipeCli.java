package ca.carleton.secpipe.cli;

import ca.carleton.secpipe.cli.demo.DemoScan;
import ca.carleton.secpipe.cli.demo.SqliHit;
import ca.carleton.secpipe.validators.ValidationError;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
    static final int EXIT_FINDINGS = 1;
    static final int EXIT_USAGE = 2;

    private static final String VERSION_RESOURCE = "secpipe-version.properties";

    private SecPipeCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length >= 1 && "scan".equals(args[0])) {
            return scan(args, out, err);
        }
        if (args.length == 1 && ("--version".equals(args[0]) || "-V".equals(args[0]))) {
            out.println(version().orElse("unknown"));
            return EXIT_OK;
        }
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println("Usage: secpipe --version | --help");
            out.println("       secpipe scan <path> [--out <dir>] [--fail-on-needs-validation]");
            out.println("scan is the WO-DEMO.1 demo slice (deterministic CWE-89 detector; no AI, no sandbox).");
            out.println("The full validate, doctor, diff, report and github commands arrive with WO-K.13.");
            return EXIT_OK;
        }
        err.println("error: unsupported arguments in this bootstrap build: " + String.join(" ", args));
        err.println("hint: run 'secpipe --help'");
        return EXIT_USAGE;
    }

    private static int scan(String[] args, PrintStream out, PrintStream err) {
        String target = null;
        String outDir = "secpipe-out";
        boolean failOnNeedsValidation = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--help".equals(a) || "-h".equals(a)) {
                out.println("Usage: secpipe scan <path> [--out <dir>] [--fail-on-needs-validation]");
                return EXIT_OK;
            } else if ("--out".equals(a) || "-o".equals(a)) {
                if (i + 1 >= args.length) {
                    return usage(err, "missing value for " + a);
                }
                outDir = args[++i];
            } else if ("--fail-on-needs-validation".equals(a)) {
                failOnNeedsValidation = true;
            } else if (a.startsWith("-") || target != null) {
                return usage(err, "unsupported scan argument in the demo slice: " + a);
            } else {
                target = a;
            }
        }
        if (target == null) {
            return usage(err, "scan needs a <path> in the demo slice");
        }
        Path root;
        Path outPath;
        try {
            root = Path.of(target);
            outPath = Path.of(outDir);
        } catch (InvalidPathException e) {
            return usage(err, "invalid path: " + e.getInput());
        }
        if (!Files.isDirectory(root)) {
            err.println("error: scan target is not a directory: " + target);
            err.println("hint: pass the root of a Java/Maven project, e.g. 'secpipe scan fixtures/e2e/sqli-min'");
            return EXIT_USAGE;
        }
        DemoScan.Outcome outcome;
        try {
            outcome = DemoScan.run(root, target, outPath);
        } catch (UncheckedIOException e) {
            err.println("error: could not read the target or write to " + outDir + ": " + e.getCause().getMessage());
            err.println("hint: check that the target is readable and the --out directory is writable");
            return EXIT_USAGE;
        }
        if (!outcome.validation().ok()) {
            ValidationError first = outcome.validation().errors().get(0);
            err.println("error: findings.json failed schema validation (" + outcome.validation().errors().size()
                    + " error(s)); first: " + first);
            err.println("hint: this is a SecPipe bug in the demo slice; report it with " + outcome.findingsFile());
            return EXIT_USAGE;
        }
        out.println("secpipe: demo scan found " + outcome.findings().size() + " CWE-89 finding(s) (needs_validation) in "
                + outcome.filesParsed() + " parsed Java file(s)");
        out.println("findings.json: " + outcome.findingsFile());
        out.println("report.md:     " + outcome.reportFile());
        int shown = Math.min(5, outcome.hits().size());
        if (shown > 0) {
            out.println("top findings:");
        }
        for (int i = 0; i < shown; i++) {
            SqliHit hit = outcome.hits().get(i);
            out.println("  " + (i + 1) + ". " + DemoScan.CWE + " SQL injection at " + hit.file() + ":" + hit.line()
                    + " (" + hit.scope() + ", " + hit.method() + ")");
        }
        if (outcome.hits().size() > shown) {
            out.println("  ... and " + (outcome.hits().size() - shown) + " more (see report.md)");
        }
        return failOnNeedsValidation && !outcome.findings().isEmpty() ? EXIT_FINDINGS : EXIT_OK;
    }

    private static int usage(PrintStream err, String problem) {
        err.println("error: " + problem);
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
