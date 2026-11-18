package ca.carleton.secpipe.core.recon;

import ca.carleton.secpipe.contracts.recon.Location;
import ca.carleton.secpipe.contracts.spi.SourceIndex;
import ca.carleton.secpipe.core.normalize.Scopes;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The deterministic JavaParser walk behind {@link ReconNode}. It parses sources only; nothing is compiled or run.
 * Entry surfaces: servlet handlers ({@code doGet}, {@code doPost}, ... in a class annotated {@code @WebServlet} or
 * extending a {@code *Servlet}), Spring {@code @*Mapping} methods and JAX-RS resource methods. Sinks: calls that
 * match the {@link SinkCatalogue} by method name, arity and the class being visible through the file's imports.
 */
final class JavaRecon {

    /** An entry surface: its location (scope always present), display label and enclosing names. */
    record EntrySurface(Location location, String simpleClassName, String methodName, String label) { }

    /** Everything one walk found. Lists are in file order; callers sort. */
    record Result(List<EntrySurface> entrySurfaces, Map<String, List<Location>> sinksByFamily, List<String> notes) { }

    private static final Set<String> SERVLET_METHODS = Set.of("doGet", "doPost", "doPut", "doDelete", "doHead",
            "doOptions", "doTrace", "doPatch", "service");
    private static final Map<String, String> SPRING_MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH", "RequestMapping", "ANY");
    private static final Set<String> JAXRS_VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final Pattern TYPE_LIKE = Pattern.compile("[A-Z][A-Za-z0-9_$]*[a-z][A-Za-z0-9_$]*");

    private final SinkCatalogue catalogue;
    private final JavaParser parser;

    JavaRecon(SinkCatalogue catalogue) {
        this.catalogue = catalogue;
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false);
        this.parser = new JavaParser(config);
    }

    /** Walks {@code files} (repo-relative, read through {@code index}). */
    Result scan(SourceIndex index, List<String> files) {
        List<EntrySurface> entries = new ArrayList<>();
        Map<String, List<Location>> sinks = new TreeMap<>();
        List<String> notes = new ArrayList<>();
        for (String file : files) {
            Optional<List<String>> lines = index.lines(file);
            if (lines.isEmpty()) {
                notes.add("recon: could not read " + file);
                continue;
            }
            ParseResult<CompilationUnit> parsed;
            try {
                parsed = parser.parse(String.join("\n", lines.get()));
            } catch (RuntimeException e) {
                notes.add("recon: could not parse " + file + ": " + firstLine(String.valueOf(e.getMessage())));
                continue;
            }
            if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                String why = parsed.getProblems().stream().findFirst().map(Problem::getMessage).orElse("parse error");
                notes.add("recon: could not parse " + file + ": " + firstLine(why));
                continue;
            }
            CompilationUnit cu = parsed.getResult().get();
            findEntrySurfaces(file, cu, entries);
            findSinks(file, cu, sinks);
        }
        return new Result(List.copyOf(entries), sinks, List.copyOf(notes));
    }

    // ---------------------------------------------------------------------------------------------- entry surfaces

    private void findEntrySurfaces(String file, CompilationUnit cu, List<EntrySurface> out) {
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            boolean servlet = annotation(type.getAnnotations(), "WebServlet").isPresent() || extendsServlet(type);
            Optional<String> servletRoute = annotation(type.getAnnotations(), "WebServlet").flatMap(JavaRecon::route);
            Optional<String> classRoute = annotation(type.getAnnotations(), "RequestMapping")
                    .or(() -> annotation(type.getAnnotations(), "Path")).flatMap(JavaRecon::route);
            for (MethodDeclaration m : type.getMethods()) {
                if (m.getBody().isEmpty()) {
                    continue;
                }
                Optional<String> label = Optional.empty();
                String name = m.getNameAsString();
                if (servlet && SERVLET_METHODS.contains(name)) {
                    String verb = name.equals("service") ? "ANY" : name.substring(2).toUpperCase(Locale.ROOT);
                    label = Optional.of(verb + " " + servletRoute.orElse(type.getNameAsString()));
                } else {
                    label = springLabel(m, classRoute).or(() -> jaxrsLabel(m, classRoute));
                }
                if (label.isPresent()) {
                    int line = m.getName().getBegin().map(p -> p.line).orElse(1);
                    out.add(new EntrySurface(Location.of(file, line, scope(type, m)), type.getNameAsString(), name,
                            label.get()));
                }
            }
        }
    }

    private static boolean extendsServlet(TypeDeclaration<?> type) {
        if (!type.isClassOrInterfaceDeclaration()) {
            return false;
        }
        return type.asClassOrInterfaceDeclaration().getExtendedTypes().stream()
                .anyMatch(t -> t.getNameAsString().endsWith("Servlet"));
    }

    private static Optional<String> springLabel(MethodDeclaration m, Optional<String> classRoute) {
        for (AnnotationExpr a : m.getAnnotations()) {
            String verb = SPRING_MAPPINGS.get(a.getName().getIdentifier());
            if (verb != null) {
                return Optional.of(verb + " " + join(classRoute, route(a)));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> jaxrsLabel(MethodDeclaration m, Optional<String> classRoute) {
        Optional<String> verb = m.getAnnotations().stream().map(a -> a.getName().getIdentifier())
                .filter(JAXRS_VERBS::contains).findFirst();
        Optional<AnnotationExpr> path = annotation(m.getAnnotations(), "Path");
        if (verb.isEmpty() && path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(verb.orElse("ANY") + " " + join(classRoute, path.flatMap(JavaRecon::route)));
    }

    private static String join(Optional<String> prefix, Optional<String> suffix) {
        String p = prefix.orElse("");
        String s = suffix.orElse("");
        String joined = p.isEmpty() ? s : s.isEmpty() ? p : p.replaceAll("/+$", "") + "/" + s.replaceAll("^/+", "");
        return joined.isEmpty() ? "/" : joined;
    }

    private static Optional<AnnotationExpr> annotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        return annotations.stream().filter(a -> a.getName().getIdentifier().equals(simpleName)).findFirst();
    }

    /** The first string literal of an annotation's value / path / urlPatterns member. */
    private static Optional<String> route(AnnotationExpr a) {
        List<Expression> candidates = new ArrayList<>();
        if (a instanceof SingleMemberAnnotationExpr single) {
            candidates.add(single.getMemberValue());
        } else if (a instanceof NormalAnnotationExpr normal) {
            normal.getPairs().stream()
                    .filter(p -> Set.of("value", "path", "urlPatterns").contains(p.getNameAsString()))
                    .forEach(p -> candidates.add(p.getValue()));
        }
        for (Expression e : candidates) {
            Optional<String> s = e.findFirst(StringLiteralExpr.class).map(StringLiteralExpr::asString)
                    .map(String::strip).filter(v -> !v.isEmpty() && v.chars().noneMatch(Character::isISOControl));
            if (s.isPresent()) {
                return s;
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------------- sinks

    private void findSinks(String file, CompilationUnit cu, Map<String, List<Location>> out) {
        Imports imports = Imports.of(cu);
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            for (SinkCatalogue.Sink s : catalogue.forMethod(call.getNameAsString())) {
                if (call.getArguments().size() > s.arg() && matchesCall(call, s, imports)) {
                    int line = call.getName().getBegin().map(p -> p.line).orElse(1);
                    out.computeIfAbsent(s.family(), k -> new ArrayList<>()).add(location(file, line, call));
                }
            }
        }
        for (ObjectCreationExpr created : cu.findAll(ObjectCreationExpr.class)) {
            String qualified = created.getType().getNameWithScope();
            String simple = created.getType().getNameAsString();
            for (SinkCatalogue.Sink s : catalogue.forMethod(SinkCatalogue.CONSTRUCTOR)) {
                boolean typeMatches = qualified.equals(s.className())
                        || (qualified.equals(simple) && simple.equals(s.simpleClassName()) && imports.sees(s));
                if (typeMatches && created.getArguments().size() > s.arg()) {
                    int line = created.getBegin().map(p -> p.line).orElse(1);
                    out.computeIfAbsent(s.family(), k -> new ArrayList<>()).add(location(file, line, created));
                }
            }
        }
    }

    private static boolean matchesCall(MethodCallExpr call, SinkCatalogue.Sink s, Imports imports) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return imports.staticallyImports(s);
        }
        String receiver = scope.get().toString();
        if (receiver.equals(s.className())) {
            return true;
        }
        boolean typeLike = scope.get() instanceof NameExpr n && TYPE_LIKE.matcher(n.getNameAsString()).matches();
        if (typeLike) {
            return receiver.equals(s.simpleClassName()) && imports.sees(s);
        }
        return !s.staticOnly() && imports.sees(s);
    }

    private static Location location(String file, int line, Node node) {
        Optional<CallableDeclaration<?>> callable = enclosingCallable(node);
        if (callable.isPresent()) {
            Optional<TypeDeclaration<?>> type = enclosingType(callable.get());
            if (type.isPresent()) {
                return Location.of(file, line, scope(type.get(), callable.get()));
            }
        }
        return Location.of(file, line);
    }

    @SuppressWarnings("unchecked")
    private static Optional<CallableDeclaration<?>> enclosingCallable(Node node) {
        Optional<Node> cur = node.getParentNode();
        while (cur.isPresent()) {
            if (cur.get() instanceof CallableDeclaration<?> c) {
                return Optional.of(c);
            }
            if (cur.get() instanceof TypeDeclaration<?>) {
                return Optional.empty();
            }
            cur = cur.get().getParentNode();
        }
        return Optional.empty();
    }

    private static Optional<TypeDeclaration<?>> enclosingType(Node node) {
        Optional<Node> cur = node.getParentNode();
        while (cur.isPresent()) {
            if (cur.get() instanceof TypeDeclaration<?> t) {
                return Optional.of(t);
            }
            cur = cur.get().getParentNode();
        }
        return Optional.empty();
    }

    /** {@code pkg.Outer.Inner#method(Type,Type)} in {@link Scopes#canonical(String)} spelling. */
    static String scope(TypeDeclaration<?> type, CallableDeclaration<?> callable) {
        String owner = type.getFullyQualifiedName().orElse(type.getNameAsString());
        String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
        List<String> params = new ArrayList<>();
        for (Parameter p : callable.getParameters()) {
            params.add(p.getType().asString() + (p.isVarArgs() ? "..." : ""));
        }
        return Scopes.canonical(owner + "#" + name + "(" + String.join(",", params) + ")");
    }

    private static String firstLine(String s) {
        String line = s.lines().findFirst().orElse("").strip();
        return line.isEmpty() ? "parse error" : line;
    }

    /** What a compilation unit imports. */
    private record Imports(String pkg, Set<String> types, Set<String> packages, Set<String> statics,
                           Set<String> staticWildcards) {

        static Imports of(CompilationUnit cu) {
            Set<String> types = new TreeSet<>();
            Set<String> packages = new TreeSet<>();
            Set<String> statics = new TreeSet<>();
            Set<String> staticWildcards = new TreeSet<>();
            for (ImportDeclaration i : cu.getImports()) {
                String name = i.getNameAsString();
                if (i.isStatic()) {
                    (i.isAsterisk() ? staticWildcards : statics).add(name);
                } else {
                    (i.isAsterisk() ? packages : types).add(name);
                }
            }
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            return new Imports(pkg, types, packages, statics, staticWildcards);
        }

        boolean sees(SinkCatalogue.Sink s) {
            return types.contains(s.className()) || packages.contains(s.packageName())
                    || s.packageName().equals("java.lang") || s.packageName().equals(pkg);
        }

        boolean staticallyImports(SinkCatalogue.Sink s) {
            return statics.contains(s.className() + "." + s.method()) || staticWildcards.contains(s.className());
        }
    }
}
