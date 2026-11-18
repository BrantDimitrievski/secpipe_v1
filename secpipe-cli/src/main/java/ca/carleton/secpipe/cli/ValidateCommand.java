package ca.carleton.secpipe.cli;

import ca.carleton.secpipe.contracts.json.LedgerCodec;
import ca.carleton.secpipe.contracts.ledger.LedgerUnit;
import ca.carleton.secpipe.core.ledger.LedgerInvariants;
import ca.carleton.secpipe.core.scoring.ScoringPolicy;
import ca.carleton.secpipe.validators.FindingsValidator;
import ca.carleton.secpipe.validators.SchemaRegistry;
import ca.carleton.secpipe.validators.ValidationError;
import ca.carleton.secpipe.validators.ValidationResult;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code secpipe validate findings|ledger|run|agent:<schema> FILE [--root PATH]} (PLAN §4.3): validators only.
 * Exit 0 valid, 1 invalid, 2 usage error.
 */
@Command(name = "validate", mixinStandardHelpOptions = true, versionProvider = SecPipeCli.VersionProvider.class,
        description = "Validate a SecPipe JSON file against its schema and deterministic rules.")
final class ValidateCommand implements Callable<Integer> {

    /** Errors printed per invalid file; the rest are counted. */
    static final int MAX_PRINTED = 20;

    private final PrintStream out;
    private final PrintStream err;

    @Parameters(index = "0", paramLabel = "KIND", description = "findings | ledger | run | agent:<schema>")
    private String kind;

    @Parameters(index = "1", paramLabel = "FILE")
    private String file;

    @Option(names = "--root", paramLabel = "PATH",
            description = "Scanned tree, so findings evidence can be checked against the source (rule F7)")
    private String root;

    ValidateCommand(PrintStream out, PrintStream err) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public Integer call() {
        String text;
        Optional<Path> rootPath;
        try {
            text = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            rootPath = root == null ? Optional.empty() : Optional.of(Path.of(root));
        } catch (IOException | InvalidPathException e) {
            return usage("cannot read " + file + ": " + SecPipeCli.oneLine(e.getMessage()),
                    "pass the path of an existing JSON file");
        }
        if (rootPath.isPresent() && !Files.isDirectory(rootPath.get())) {
            return usage("--root is not a directory: " + root, "pass the directory that was scanned");
        }
        Optional<ValidationResult> result = validate(kind, text, rootPath);
        if (result.isEmpty()) {
            return usage("unknown kind '" + kind + "'",
                    "use findings, ledger, run or agent:<schema> (e.g. agent:disprover-result)");
        }
        ValidationResult r = result.get();
        if (r.ok()) {
            out.println("valid: " + file + " (" + kind + ")");
            return SecPipeCli.EXIT_OK;
        }
        err.println("invalid: " + file + " (" + kind + "): " + r.errors().size() + " error(s)");
        r.errors().stream().limit(MAX_PRINTED).forEach(e -> err.println("  " + e));
        if (r.errors().size() > MAX_PRINTED) {
            err.println("  ... " + (r.errors().size() - MAX_PRINTED) + " more");
        }
        return SecPipeCli.EXIT_FINDINGS;
    }

    /** Validates {@code text} as {@code kind}; empty when the kind is unknown. */
    static Optional<ValidationResult> validate(String kind, String text, Optional<Path> root) {
        switch (kind) {
            case "findings":
                return Optional.of(FindingsValidator.validateJson(text, root, new ScoringPolicy()));
            case "ledger":
                return Optional.of(ledger(text));
            case "run":
                return Optional.of(SchemaRegistry.shared().validate("run-metadata.schema.json", text));
            default:
                if (kind.startsWith("agent:") && kind.length() > "agent:".length()) {
                    String name = kind.substring("agent:".length());
                    String schema = "agent/" + (name.endsWith(".schema.json") ? name : name + ".schema.json");
                    boolean known = SchemaRegistry.shared().ids().stream().anyMatch(id -> id.endsWith("/" + schema));
                    return known ? Optional.of(SchemaRegistry.shared().validate(schema, text)) : Optional.empty();
                }
                return Optional.empty();
        }
    }

    /** Schema first; when it passes, the whole-ledger invariants (L1, and L2 as checked at finalize). */
    static ValidationResult ledger(String text) {
        ValidationResult schema = SchemaRegistry.shared().validate("ledger.schema.json", text);
        if (!schema.ok()) {
            return schema;
        }
        List<LedgerUnit> units;
        try {
            units = LedgerCodec.read(text);
        } catch (RuntimeException e) {
            return ValidationResult.of(List.of(ValidationError.of("ledger.read", "",
                    SecPipeCli.oneLine(e.getMessage()))));
        }
        return ValidationResult.of(LedgerInvariants.checkL1(units)).merge(LedgerInvariants.checkFinal(units));
    }

    private int usage(String problem, String hint) {
        err.println("error: " + problem);
        err.println("hint: " + hint);
        return SecPipeCli.EXIT_USAGE;
    }
}
