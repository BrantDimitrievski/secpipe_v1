package ca.carleton.secpipe.agents.refuter;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finds the sink calls of a family that begin on a given line (names mirror the builtin adapter's rules). */
final class SinkLocator {

    private static final Set<String> SQL = Set.of("executeQuery", "executeUpdate", "executeLargeUpdate",
            "prepareStatement", "prepareCall", "addBatch", "createQuery", "createNativeQuery", "createSQLQuery",
            "nativeQuery", "execute", "query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet",
            "update", "batchUpdate");
    private static final Set<String> FILE_TYPES = Set.of("File", "FileInputStream", "FileOutputStream",
            "FileReader", "FileWriter", "RandomAccessFile", "ZipFile", "JarFile");
    private static final Set<String> FILES_METHODS = Set.of("newInputStream", "newOutputStream", "readAllBytes",
            "readAllLines", "readString", "write", "writeString", "lines", "newBufferedReader", "newBufferedWriter",
            "copy", "move", "delete", "deleteIfExists");

    private SinkLocator() {
    }

    static List<SinkSite> locate(CompilationUnit cu, String file, int line, String family) {
        return locate(cu, file, line, family, ScannedTypes.NONE);
    }

    static List<SinkSite> locate(CompilationUnit cu, String file, int line, String family, ScannedTypes types) {
        List<SinkSite> out = new ArrayList<>();
        for (Expression e : cu.findAll(Expression.class)) {
            if (!(e instanceof MethodCallExpr || e instanceof ObjectCreationExpr)
                    || e.getBegin().map(p -> p.line).orElse(-1) != line) {
                continue;
            }
            Optional<List<Expression>> args = relevantArgs(e, family);
            Optional<CallableDeclaration<?>> m = e.findAncestor(CallableDeclaration.class).map(c -> c);
            if (args.isPresent() && m.isPresent()) {
                out.add(new SinkSite(file, line, m.get(), e, family, args.get(), types));
            }
        }
        return List.copyOf(out);
    }

    /** The arguments that carry the injectable value, or empty if {@code e} is not a sink of the family. */
    private static Optional<List<Expression>> relevantArgs(Expression e, String family) {
        if (e instanceof MethodCallExpr mc) {
            String name = mc.getNameAsString();
            List<Expression> args = mc.getArguments();
            switch (family) {
                case "CWE-89":
                    return SQL.contains(name) ? Optional.of(args.isEmpty() ? List.of() : List.of(args.getFirst()))
                            : Optional.empty();
                case "CWE-78":
                    if (name.equals("exec")) {
                        return Optional.of(List.copyOf(args.subList(0, Math.min(2, args.size()))));
                    }
                    return name.equals("command") ? Optional.of(List.copyOf(args)) : Optional.empty();
                case "CWE-22":
                    String scope = mc.getScope().map(Object::toString).orElse("");
                    boolean pathFactory = Set.of("get", "of").contains(name)
                            && Set.of("Paths", "Path", "java.nio.file.Paths", "java.nio.file.Path").contains(scope);
                    boolean files = FILES_METHODS.contains(name)
                            && Set.of("Files", "java.nio.file.Files").contains(scope);
                    return pathFactory || files ? Optional.of(List.copyOf(args)) : Optional.empty();
                default:
                    return Optional.empty();
            }
        }
        ObjectCreationExpr oc = (ObjectCreationExpr) e;
        String type = oc.getType().getNameAsString();
        boolean sink = family.equals("CWE-78") ? type.equals("ProcessBuilder")
                : family.equals("CWE-22") && FILE_TYPES.contains(type);
        return sink ? Optional.of(List.copyOf(oc.getArguments())) : Optional.empty();
    }
}
