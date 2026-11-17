package ca.carleton.secpipe.cli.demo;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Demo-slice CWE-89 detector (WO-DEMO.1). Parses {@code *.java} with JavaParser only; never compiles or runs target
 * code. Flags a JDBC sink whose SQL argument is a string concatenation with a non-literal operand, or a local variable
 * that was initialized or assigned from such a concatenation in the same method. Intra-procedural, no taint sources.
 */
public final class DemoSqliDetector {

    static final Set<String> SINKS = Set.of("executeQuery", "executeUpdate", "execute", "prepareStatement",
            "addBatch");

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

    private int parsed;
    private int skipped;

    /** Result of scanning a tree. */
    public record Result(List<SqliHit> hits, int filesParsed, int filesSkipped) {
        public Result {
            hits = List.copyOf(hits);
        }
    }

    public Result scan(Path root) {
        parsed = 0;
        skipped = 0;
        List<SqliHit> hits = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            Optional<CompilationUnit> unit = parse(file);
            if (unit.isEmpty()) {
                skipped++;
                continue;
            }
            parsed++;
            unit.get().findAll(MethodCallExpr.class).forEach(call -> check(rel, call).ifPresent(hits::add));
        }
        hits.sort(Comparator.comparing(SqliHit::file).thenComparingInt(SqliHit::line)
                .thenComparing(SqliHit::sinkCall));
        return new Result(hits, parsed, skipped);
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !root.relativize(p).toString().replace('\\', '/').contains("/target/")
                            && !root.relativize(p).toString().startsWith("target"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<CompilationUnit> parse(Path file) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(Files.readString(file, StandardCharsets.UTF_8));
            return result.isSuccessful() ? result.getResult() : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<SqliHit> check(String file, MethodCallExpr call) {
        if (!SINKS.contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Expression sql = unwrap(call.getArgument(0));
        Set<String> vars = new TreeSet<>();
        String via = "";
        if (isTaintedConcat(sql, vars)) {
            // concatenated in place
        } else if (sql.isNameExpr()) {
            String name = sql.asNameExpr().getNameAsString();
            Node body = enclosingCallable(call).map(n -> (Node) n).orElseGet(() -> root(call));
            if (!assignedFromTaintedConcat(body, name, vars)) {
                return Optional.empty();
            }
            via = name;
        } else {
            return Optional.empty();
        }
        int line = call.getBegin().map(p -> p.line).orElse(1);
        return Optional.of(new SqliHit(file, line, scope(call), call.getNameAsString(), call.toString(),
                List.copyOf(vars), via));
    }

    private static boolean assignedFromTaintedConcat(Node body, String name, Set<String> vars) {
        boolean found = false;
        for (VariableDeclarator decl : body.findAll(VariableDeclarator.class)) {
            if (decl.getNameAsString().equals(name) && decl.getInitializer().isPresent()
                    && isTaintedConcat(unwrap(decl.getInitializer().get()), vars)) {
                found = true;
            }
        }
        for (AssignExpr assign : body.findAll(AssignExpr.class)) {
            if (!assign.getTarget().isNameExpr() || !assign.getTarget().asNameExpr().getNameAsString().equals(name)) {
                continue;
            }
            Expression value = unwrap(assign.getValue());
            if (assign.getOperator() == AssignExpr.Operator.PLUS) {
                if (!(value instanceof LiteralExpr) && !isConstantConcat(value)) {
                    collectNonLiterals(value, vars);
                    found = true;
                }
            } else if (assign.getOperator() == AssignExpr.Operator.ASSIGN && isTaintedConcat(value, vars)) {
                found = true;
            }
        }
        return found;
    }

    /** True when {@code e} is a '+' expression with at least one non-literal operand; collects those operands. */
    static boolean isTaintedConcat(Expression e, Set<String> vars) {
        Expression x = unwrap(e);
        if (!(x instanceof BinaryExpr bin) || bin.getOperator() != BinaryExpr.Operator.PLUS) {
            return false;
        }
        Set<String> found = new TreeSet<>();
        collectNonLiterals(x, found);
        vars.addAll(found);
        return !found.isEmpty();
    }

    private static boolean isConstantConcat(Expression e) {
        Set<String> found = new TreeSet<>();
        collectNonLiterals(e, found);
        return found.isEmpty();
    }

    private static void collectNonLiterals(Expression e, Set<String> out) {
        Expression x = unwrap(e);
        if (x instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
            collectNonLiterals(bin.getLeft(), out);
            collectNonLiterals(bin.getRight(), out);
        } else if (!(x instanceof LiteralExpr)) {
            out.add(x instanceof NameExpr n ? n.getNameAsString() : x.toString());
        }
    }

    private static Expression unwrap(Expression e) {
        Expression x = e;
        while (x instanceof EnclosedExpr enclosed) {
            x = enclosed.getInner();
        }
        return x;
    }

    private static Optional<CallableDeclaration<?>> enclosingCallable(Node n) {
        Optional<Node> p = n.getParentNode();
        while (p.isPresent()) {
            if (p.get() instanceof CallableDeclaration<?> c) {
                return Optional.of(c);
            }
            p = p.get().getParentNode();
        }
        return Optional.empty();
    }

    private static Node root(Node n) {
        Node x = n;
        while (x.getParentNode().isPresent()) {
            x = x.getParentNode().get();
        }
        return x;
    }

    private static String scope(Node n) {
        String method = enclosingCallable(n).map(c -> c.getNameAsString()).orElse("<init>");
        String type = "";
        Optional<Node> p = n.getParentNode();
        while (p.isPresent()) {
            if (p.get() instanceof TypeDeclaration<?> t) {
                type = t.getFullyQualifiedName().orElse(t.getNameAsString());
                break;
            }
            p = p.get().getParentNode();
        }
        return (type.isEmpty() ? "<unknown>" : type) + "#" + method;
    }
}
