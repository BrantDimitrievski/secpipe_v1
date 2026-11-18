package ca.carleton.secpipe.agents.refuter;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code parameterized}: SQL executed through a {@code PreparedStatement} whose query text is constant (values bound
 * with {@code setX}), or a process started from an argv array/list whose {@code command[0]} is a constant program
 * that is not a shell or script interpreter (no shell parsing of the untrusted value).
 */
final class ParameterizedApiRule implements RefuterRule {

    private static final Set<String> EXECUTE = Set.of("executeQuery", "executeUpdate", "execute", "executeLargeUpdate");
    private static final Set<String> PREPARE = Set.of("prepareStatement", "prepareCall");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "zsh", "ksh", "csh", "tcsh", "dash", "ash", "cmd",
            "cmd.exe", "command.com", "powershell", "powershell.exe", "pwsh", "pwsh.exe", "env", "xargs", "busybox",
            "python", "python3", "perl", "ruby", "node", "php", "osascript", "eval", "exec", "sudo", "su", "nohup",
            "nice", "timeout", "time", "watch", "ssh", "awk", "sed", "find");
    private static final Set<String> INLINE_FLAGS = Set.of("-c", "/c", "/C", "/k", "/K", "-e", "-Command", "-command",
            "-EncodedCommand");

    @Override
    public String id() {
        return Refutation.PARAMETERIZED;
    }

    @Override
    public Optional<Refutation> check(SinkSite site) {
        return switch (site.family()) {
            case "CWE-89" -> preparedStatement(site);
            case "CWE-78" -> argv(site);
            default -> Optional.empty();
        };
    }

    private Optional<Refutation> preparedStatement(SinkSite site) {
        if (!(site.call() instanceof MethodCallExpr mc) || !mc.getArguments().isEmpty()
                || !EXECUTE.contains(mc.getNameAsString()) || mc.getScope().isEmpty()
                || !(mc.getScope().get() instanceof NameExpr receiver)) {
            return Optional.empty();
        }
        String name = receiver.getNameAsString();
        Optional<VariableDeclarator> decl = soleDeclaration(site, name);
        if (decl.isEmpty() || !decl.get().getType().asString().matches("(.*\\.)?(PreparedStatement|CallableStatement)")
                || !(decl.get().getInitializer().orElse(null) instanceof MethodCallExpr prepare)
                || !PREPARE.contains(prepare.getNameAsString()) || prepare.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> sqlSafe = RefuterRule.safeArgs(Flow.run(site.method(), prepare, Flow.Mode.STRONG),
                List.of(prepare.getArgument(0)));
        if (sqlSafe.isEmpty()) {
            return Optional.empty();
        }
        int control = site.method().findAll(MethodCallExpr.class).stream()
                .filter(m -> m.getNameAsString().startsWith("set") && m.getScope().map(s -> s instanceof NameExpr n
                        && n.getNameAsString().equals(name)).orElse(false))
                .map(Flow::line).findFirst().orElse(Flow.line(prepare));
        return Optional.of(new Refutation(id(), site.at(control), "the query text passed to "
                + prepare.getNameAsString() + " (line " + Flow.line(prepare) + ") is constant; untrusted values are "
                + "bound as parameters, so " + mc.getNameAsString() + " cannot be injected"));
    }

    private Optional<Refutation> argv(SinkSite site) {
        List<Expression> args = site.call().isMethodCallExpr() ? site.call().asMethodCallExpr().getArguments()
                : site.call().asObjectCreationExpr().getArguments();
        boolean exec = site.call().isMethodCallExpr() && site.callName().equals("exec");
        Optional<List<Expression>> argv;
        if (args.isEmpty()) {
            return Optional.empty();
        } else if (!exec && args.size() >= 2) {
            argv = Optional.of(List.copyOf(args));
        } else {
            argv = elements(site, args.getFirst());
        }
        if (argv.isEmpty() || argv.get().isEmpty()) {
            return Optional.empty();
        }
        Optional<Flow.Outcome> outcome = Flow.run(site.method(), site.call(), Flow.Mode.STRONG);
        if (outcome.isEmpty() || !outcome.get().reachable()) {
            return Optional.empty();
        }
        Expression first = argv.get().getFirst();
        if (!(outcome.get().eval(first) instanceof Val.Const k) || !(k.value() instanceof String program)
                || program.isBlank() || program.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        String base = program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1)
                .toLowerCase(Locale.ROOT);
        if (SHELLS.contains(base) || SHELLS.contains(base.replaceAll("\\.exe$", ""))) {
            return Optional.empty();
        }
        if (argv.get().size() > 1) {
            // an untrusted or inline-script option right after the program could change its mode: stay unsure
            Val second = outcome.get().eval(argv.get().get(1));
            if (!(second instanceof Val.Const k2) || INLINE_FLAGS.contains(String.valueOf(k2.value()))) {
                return Optional.empty();
            }
        }
        return Optional.of(new Refutation(id(), site.at(Flow.line(first)), "the command runs '" + program
                + "' directly without a shell; untrusted input is passed as a separate argument and is never "
                + "parsed as a command"));
    }

    /** The elements of an argv given as an array/list literal or an only-read local initialised with one. */
    private static Optional<List<Expression>> elements(SinkSite site, Expression e) {
        if (e instanceof ArrayCreationExpr ac && ac.getInitializer().isPresent()) {
            return Optional.of(List.copyOf(ac.getInitializer().get().getValues()));
        }
        if (e instanceof ArrayInitializerExpr ai) {
            return Optional.of(List.copyOf(ai.getValues()));
        }
        if (e instanceof MethodCallExpr mc && mc.getScope().isPresent() && Set.of("List.of", "Arrays.asList")
                .contains(mc.getScope().get().toString().replaceAll(".*\\.", "") + "." + mc.getNameAsString())) {
            return Optional.of(List.copyOf(mc.getArguments()));
        }
        if (e instanceof NameExpr n) {
            String name = n.getNameAsString();
            Optional<VariableDeclarator> decl = soleDeclaration(site, name);
            boolean onlyUsedHere = site.method().findAll(NameExpr.class).stream()
                    .filter(x -> x.getNameAsString().equals(name))
                    .allMatch(x -> x.getParentNode().map(p -> p == site.call()).orElse(false));
            if (decl.isPresent() && onlyUsedHere && decl.get().getInitializer().isPresent()) {
                Expression init = decl.get().getInitializer().get();
                return init instanceof NameExpr ? Optional.empty() : elements(site, init);
            }
        }
        return Optional.empty();
    }

    /** The single declaration of local {@code name} in the sink's method, if it is never reassigned. */
    private static Optional<VariableDeclarator> soleDeclaration(SinkSite site, String name) {
        List<VariableDeclarator> decls = site.method().findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getNameAsString().equals(name)).toList();
        boolean reassigned = site.method().findAll(AssignExpr.class).stream()
                .anyMatch(a -> a.getTarget() instanceof NameExpr t && t.getNameAsString().equals(name));
        boolean isParam = site.method().getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name));
        if (decls.size() != 1 || reassigned || isParam) {
            return Optional.empty();
        }
        return Optional.of(decls.getFirst());
    }
}
