package ca.carleton.secpipe.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * The {@code secpipe} command line (WO-K.13, PLAN §4.3): {@code scan}, {@code validate}, {@code --help} and
 * {@code --version}. Exit codes are frozen: 0 OK, 1 findings gate, 2 usage or environment error (a one-line problem
 * plus a one-line {@code hint:}), 3 incomplete run with {@code --fail-on-incomplete}.
 */
public final class SecPipeCli {

    static final int EXIT_OK = 0;
    static final int EXIT_FINDINGS = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_INCOMPLETE = 3;

    private static final String VERSION_RESOURCE = "secpipe-version.properties";

    private SecPipeCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err, System.getenv()));
    }

    /** Runs with the process environment. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, System.getenv());
    }

    /** Runs {@code args}; {@code env} replaces the process environment (tests pass the fake-LLM switches here). */
    public static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        CommandLine cli = commandLine(out, err, env);
        if (args.length == 0) {
            err.println("error: no command given");
            err.println("hint: run 'secpipe --help'; to scan the current directory run 'secpipe scan .'");
            return EXIT_USAGE;
        }
        return cli.execute(args);
    }

    static CommandLine commandLine(PrintStream out, PrintStream err, Map<String, String> env) {
        CommandLine cli = new CommandLine(new Root());
        cli.addSubcommand("scan", new ScanCommand(out, err, env));
        cli.addSubcommand("validate", new ValidateCommand(out, err));
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        cli.setParameterExceptionHandler((ex, args) -> {
            String sub = ex.getCommandLine() == null || ex.getCommandLine().getCommandName() == null
                    || ex.getCommandLine() == cli ? "" : ex.getCommandLine().getCommandName() + " ";
            err.println("error: " + oneLine(ex.getMessage()));
            err.println("hint: run 'secpipe " + sub + "--help' for the usage");
            return EXIT_USAGE;
        });
        cli.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            err.println("error: internal error: " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + oneLine(ex.getMessage())));
            err.println("hint: re-run with -v for details and report it as a SecPipe bug");
            return EXIT_USAGE;
        });
        return cli;
    }

    static String oneLine(String s) {
        if (s == null || s.isBlank()) {
            return "unknown problem";
        }
        return s.lines().findFirst().orElse(s).strip();
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

    /** The root command: only {@code --help}, {@code --version} and the subcommands. */
    @Command(name = "secpipe", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class,
            description = "Scan Java/Maven projects for vulnerabilities; every finding is verified or explains why "
                    + "it is not.")
    static final class Root implements Runnable {
        @CommandLine.Spec
        private CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "no command given");
        }
    }

    /** Prints the filtered project version. */
    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {version().orElse("unknown")};
        }
    }
}
