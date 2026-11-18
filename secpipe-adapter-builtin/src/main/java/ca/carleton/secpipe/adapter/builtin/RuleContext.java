package ca.carleton.secpipe.adapter.builtin;

import ca.carleton.secpipe.contracts.finding.Finding.Level3;
import ca.carleton.secpipe.contracts.finding.Finding.Score5;
import ca.carleton.secpipe.contracts.finding.Finding.TraceKind;
import ca.carleton.secpipe.contracts.finding.Finding.TraceStep;
import ca.carleton.secpipe.contracts.ids.Fingerprints;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Per-file state shared by the built-in rules. Collects {@link Hit}s. */
public class RuleContext {

    public static final String TOOL_ID = "builtin";

    private final String file;
    private final CompilationUnit cu;
    private final List<String> lines;
    private final boolean fileHandlesRequests;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<Node, TaintTracker> trackers = new IdentityHashMap<>();
    private final Map<String, String> stringConstants = new HashMap<>();
    private final TaintTracker empty = new TaintTracker(null);

    public RuleContext(String file, CompilationUnit cu, List<String> lines, boolean fileHandlesRequests) {
        this.file = file;
        this.cu = cu;
        this.lines = List.copyOf(lines);
        this.fileHandlesRequests = fileHandlesRequests;
        cu.findAll(VariableDeclarator.class).forEach(vd -> vd.getInitializer()
                .filter(Expression::isStringLiteralExpr)
                .ifPresent(init -> stringConstants.put(vd.getNameAsString(), init.asStringLiteralExpr().getValue())));
    }

    public CompilationUnit cu() {
        return cu;
    }

    public String file() {
        return file;
    }

    public boolean fileHandlesRequests() {
        return fileHandlesRequests;
    }

    public TaintTracker taintFor(Node node) {
        Optional<CallableDeclaration> callable = node.findAncestor(CallableDeclaration.class);
        return callable.<TaintTracker>map(c -> trackers.computeIfAbsent(c, k -> new TaintTracker((CallableDeclaration<?>) k)))
                .orElse(empty);
    }

    /** Resolves a literal, or a variable/field initialized with a literal, to its string value. */
    public Optional<String> constantString(Expression e) {
        if (e instanceof StringLiteralExpr s) return Optional.of(s.getValue());
        if (e instanceof NameExpr n) return Optional.ofNullable(stringConstants.get(n.getNameAsString()));
        if (e instanceof FieldAccessExpr f) return Optional.ofNullable(stringConstants.get(f.getNameAsString()));
        return Optional.empty();
    }

    /** Reports a hit with only a sink location (no taint flow). */
    public void report(Node node, String ruleId, String cwe, Score5 severity, Level3 confidence,
                       String title, String message) {
        report(node, ruleId, cwe, severity, confidence, title, message, List.of());
    }

    /** Reports a hit; {@code taintedArgs} are the sink arguments used to find the source for the trace. */
    public void report(Node node, String ruleId, String cwe, Score5 severity, Level3 confidence,
                       String title, String message, List<Expression> taintedArgs) {
        int line = node.getBegin().map(p -> p.line).orElse(0);
        for (Hit existing : hits) {
            if (existing.line() == line && existing.ruleId().equals(ruleId)) return;
        }
        String scope = scopeOf(node);
        List<TraceStep> trace = new ArrayList<>();
        TaintTracker tracker = taintFor(node);
        for (Expression arg : taintedArgs) {
            Optional<Node> origin = tracker.origin(arg);
            if (origin.isPresent()) {
                trace.add(entryStep(origin.get(), scope));
                break;
            }
        }
        String snippet = line > 0 && line <= lines.size() ? truncate(lines.get(line - 1).strip()) : "";
        trace.add(new TraceStep(TraceKind.SINK, file, Math.max(line, 1), scope, snippet.isEmpty() ? title : snippet));
        String sink = node.getTokenRange().map(Object::toString).orElse(node.toString());
        hits.add(new Hit(ruleId, cwe, file, Math.max(line, 1), severity, confidence, title, message,
                Fingerprints.normalizeStatement(sink), scope, trace, snippet));
    }

    private TraceStep entryStep(Node origin, String sinkScope) {
        int line = Math.max(origin.getBegin().map(p -> p.line).orElse(1), 1);
        String description;
        String scope = origin.findAncestor(CallableDeclaration.class).isPresent() ? scopeOf(origin) : sinkScope;
        if (origin instanceof Parameter p) {
            description = "method parameter '" + p.getNameAsString() + "' (" + p.getType().asString() + ")";
        } else if (origin instanceof MethodCallExpr mc) {
            description = "untrusted input read via " + mc.getNameAsString() + "()";
        } else {
            description = "untrusted input";
        }
        return new TraceStep(TraceKind.ENTRYPOINT, file, line, scope, description);
    }

    /** {@code Class#method(ParamTypes)} of the callable enclosing the node; {@code Class#<clinit>()} outside any. */
    static String scopeOf(Node node) {
        List<String> types = new ArrayList<>();
        Node cur = node;
        while (true) {
            Optional<TypeDeclaration> t = cur.findAncestor(TypeDeclaration.class);
            if (t.isEmpty()) break;
            types.add(0, t.get().getNameAsString());
            cur = t.get();
        }
        String owner = types.isEmpty() ? "?" : String.join(".", types);
        Optional<CallableDeclaration> callable = node instanceof CallableDeclaration<?>
                ? Optional.of((CallableDeclaration) node) : node.findAncestor(CallableDeclaration.class);
        if (callable.isEmpty()) return owner + "#<clinit>()";
        CallableDeclaration<?> c = callable.get();
        String name = c instanceof ConstructorDeclaration ? "<init>" : c.getNameAsString();
        String params = c.getParameters().stream().map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(","));
        return owner + "#" + name + "(" + params + ")";
    }

    public List<Hit> hits() {
        return hits;
    }

    static String truncate(String s) {
        return s.length() > 240 ? s.substring(0, 237) + "..." : s;
    }
}
