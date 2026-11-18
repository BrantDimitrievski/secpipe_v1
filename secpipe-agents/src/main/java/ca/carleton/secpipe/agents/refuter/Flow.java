package ca.carleton.secpipe.agents.refuter;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Intra-procedural abstract interpreter over one method body, in source order, up to a target expression (the
 * sink). It tracks only locals of immutable types (String, primitives, boxed types, File, and String arrays that
 * are never mutated or passed anywhere but the target); every other value is {@link Val.Tainted}. It never
 * compiles or runs anything. Whenever it meets a construct it does not model precisely (loops around the target,
 * catch/finally around the target, labelled breaks, lambdas, reflection) it bails, and callers pass the
 * candidate through.
 */
final class Flow {

    /** WEAK: assignments join with the previous value. STRONG: assignments replace it. FOLDING: STRONG plus
     *  pruning of branches whose condition or switch selector folds to a constant. */
    enum Mode { WEAK, STRONG, FOLDING }

    /** Signals "unsure": the caller must not refute. */
    static final class Bail extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Bail(String message) {
            super(message, null, false, false);
        }
    }

    /** Result of running to the target. {@code reachable == false} means a folded condition makes it dead. */
    record Outcome(boolean reachable, int deadLine, List<Integer> prunedLines, Flow flow, Map<String, Val> state) {

        /** Evaluates {@code e} in the state just before the target executes. */
        Val eval(Expression e) {
            return flow.eval(e, new HashMap<>(state));
        }
    }

    private static final Set<String> TRACKED_TYPES = Set.of("String", "java.lang.String", "Integer", "Long",
            "Short", "Byte", "Character", "Boolean", "Double", "Float", "File", "java.io.File");
    private static final Set<String> REFLECTION = Set.of("invoke", "forName", "getMethod", "getDeclaredMethod",
            "getField", "getDeclaredField", "setAccessible", "newInstance", "lookup", "findVirtual", "findStatic");
    private static final Set<String> PURE_STRING = Set.of("toUpperCase", "toLowerCase", "trim", "strip", "concat",
            "substring", "replace", "replaceAll", "replaceFirst", "toString", "intern", "valueOf", "format", "join",
            "charAt", "length", "isEmpty", "equals", "equalsIgnoreCase", "contains", "startsWith", "endsWith",
            "indexOf", "parseInt", "parseLong", "toCharArray", "getName", "getPath", "getAbsolutePath");

    private final CallableDeclaration<?> method;
    private final Node target;
    private final Mode mode;
    private final Set<String> trackedArrays = new HashSet<>();
    private final List<Integer> pruned = new ArrayList<>();
    private Map<String, Val> atTarget;
    private boolean dead;
    private int deadLine;
    private int fieldDepth;

    private Flow(CallableDeclaration<?> method, Node target, Mode mode) {
        this.method = method;
        this.target = target;
        this.mode = mode;
    }

    /** Runs {@code method} up to {@code target}; empty when unsure. */
    static Optional<Outcome> run(CallableDeclaration<?> method, Node target, Mode mode) {
        Flow flow = new Flow(method, target, mode);
        try {
            return Optional.of(flow.go());
        } catch (Bail | ArithmeticException | IndexOutOfBoundsException | ClassCastException e) {
            return Optional.empty();
        }
    }

    private Outcome go() {
        guard();
        Optional<BlockStmt> body = bodyOf(method);
        if (body.isEmpty() || !target.isDescendantOf(body.get())) {
            throw new Bail("target outside a method body");
        }
        Map<String, Val> st = new HashMap<>();
        method.getParameters().forEach(p -> st.put(p.getNameAsString(), Val.TAINTED));
        Map<String, Val> out = exec(body.get(), st);
        if (atTarget == null && !dead) {
            if (out == null && !pruned.isEmpty()) {
                dead = true;
                deadLine = pruned.getLast();
            } else {
                throw new Bail("target not reached");
            }
        }
        return new Outcome(!dead, deadLine, List.copyOf(pruned), this, dead ? Map.of() : Map.copyOf(atTarget));
    }

    private static Optional<BlockStmt> bodyOf(CallableDeclaration<?> c) {
        if (c instanceof com.github.javaparser.ast.body.MethodDeclaration m) {
            return m.getBody();
        }
        if (c instanceof com.github.javaparser.ast.body.ConstructorDeclaration k) {
            return Optional.of(k.getBody());
        }
        return Optional.empty();
    }

    /** Soundness guard: lambdas, method references, anonymous classes and reflection make us give up. */
    private void guard() {
        if (!method.findAll(LambdaExpr.class).isEmpty() || !method.findAll(MethodReferenceExpr.class).isEmpty()
                || method.findAll(ObjectCreationExpr.class).stream()
                        .anyMatch(o -> o.getAnonymousClassBody().isPresent())
                || !method.findAll(LocalClassDeclarationStmt.class).isEmpty()) {
            throw new Bail("lambda or inner class");
        }
        if (method.findAll(MethodCallExpr.class).stream().anyMatch(m -> REFLECTION.contains(m.getNameAsString()))) {
            throw new Bail("reflection");
        }
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            if (isStringArray(v.getType()) && arrayOnlyRead(v.getNameAsString())) {
                trackedArrays.add(v.getNameAsString());
            }
        }
    }

    private static boolean isStringArray(Type t) {
        return t instanceof ArrayType a && a.getArrayLevel() == 1
                && TRACKED_TYPES.contains(a.getComponentType().asString());
    }

    /** A String[] is tracked only if every use is an element read, {@code .length}, or an argument of the target. */
    private boolean arrayOnlyRead(String name) {
        for (NameExpr use : method.findAll(NameExpr.class)) {
            if (!use.getNameAsString().equals(name)) {
                continue;
            }
            Optional<Node> parent = use.getParentNode();
            if (parent.isEmpty()) {
                return false;
            }
            Node p = parent.get();
            boolean ok;
            if (p instanceof ArrayAccessExpr aa) {
                ok = !(aa.getParentNode().orElse(null) instanceof AssignExpr ae && ae.getTarget() == aa)
                        && !(aa.getParentNode().orElse(null) instanceof UnaryExpr);
            } else if (p instanceof FieldAccessExpr fa) {
                ok = fa.getNameAsString().equals("length");
            } else {
                ok = p == target;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------- statements

    /** Transfer function; returns null when the statement does not complete normally. */
    private Map<String, Val> exec(Statement s, Map<String, Val> st) {
        if (st == null || atTarget != null || dead) {
            return st;
        }
        if (target.isDescendantOf(s)) {
            return descend(s, st);
        }
        if (s instanceof BlockStmt b) {
            Map<String, Val> cur = st;
            for (Statement x : b.getStatements()) {
                cur = exec(x, cur);
                if (cur == null) {
                    return null;
                }
            }
            return cur;
        }
        if (s instanceof ExpressionStmt e) {
            eval(e.getExpression(), st);
            return st;
        }
        if (s instanceof IfStmt i) {
            Val c = eval(i.getCondition(), st);
            Optional<Boolean> folded = foldedBoolean(c);
            if (folded.isPresent()) {
                pruned.add(line(i));
                if (folded.get()) {
                    return exec(i.getThenStmt(), st);
                }
                return i.getElseStmt().isPresent() ? exec(i.getElseStmt().get(), st) : st;
            }
            Map<String, Val> t = exec(i.getThenStmt(), copy(st));
            Map<String, Val> f = i.getElseStmt().isPresent() ? exec(i.getElseStmt().get(), copy(st)) : st;
            return join(t, f);
        }
        if (s instanceof SwitchStmt sw) {
            return execSwitch(sw, st);
        }
        if (s instanceof ForStmt || s instanceof ForEachStmt || s instanceof WhileStmt || s instanceof DoStmt) {
            return havoc(s, st);
        }
        if (s instanceof TryStmt t) {
            if (!t.getCatchClauses().isEmpty()) {
                return havoc(t, st);
            }
            Map<String, Val> cur = st;
            for (Expression r : t.getResources()) {
                eval(r, cur);
            }
            cur = exec(t.getTryBlock(), cur);
            if (t.getFinallyBlock().isPresent()) {
                cur = cur == null ? null : exec(t.getFinallyBlock().get(), cur);
            }
            return cur;
        }
        if (s instanceof ReturnStmt r) {
            r.getExpression().ifPresent(x -> eval(x, st));
            return null;
        }
        if (s instanceof ThrowStmt th) {
            eval(th.getExpression(), st);
            return null;
        }
        if (s instanceof LabeledStmt l) {
            if (!l.findAll(BreakStmt.class).isEmpty()) {
                throw new Bail("labelled break");
            }
            return exec(l.getStatement(), st);
        }
        if (s instanceof SynchronizedStmt sy) {
            eval(sy.getExpression(), st);
            return exec(sy.getBody(), st);
        }
        if (s instanceof EmptyStmt || s instanceof AssertStmt) {
            return st;
        }
        throw new Bail("unsupported statement " + s.getClass().getSimpleName());
    }

    /** The target lies inside {@code s}: walk towards it and capture the state when it is reached. */
    private Map<String, Val> descend(Statement s, Map<String, Val> st) {
        if (s instanceof BlockStmt b) {
            Map<String, Val> cur = st;
            for (Statement x : b.getStatements()) {
                cur = exec(x, cur);
                if (atTarget != null || dead) {
                    return null;
                }
                if (cur == null) {
                    if (!pruned.isEmpty()) {
                        dead = true;
                        deadLine = pruned.getLast();
                        return null;
                    }
                    throw new Bail("target after a statement that never completes");
                }
            }
            throw new Bail("target not found in block");
        }
        if (s instanceof ExpressionStmt || s instanceof ReturnStmt || s instanceof ThrowStmt) {
            for (Node n : s.findAll(Node.class)) {
                boolean sideEffect = n instanceof AssignExpr
                        || (n instanceof UnaryExpr u && isIncDec(u));
                boolean afterTarget = n instanceof AssignExpr ae && ae.getTarget() instanceof NameExpr
                        && (ae.getValue() == target || target.isDescendantOf(ae.getValue()));
                if (sideEffect && !afterTarget && !n.isDescendantOf(target) && n != target) {
                    throw new Bail("side effect in sink statement");
                }
            }
            if (s instanceof ExpressionStmt es && es.getExpression() instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator v : vde.getVariables()) {
                    if (target.isDescendantOf(v) || v.getInitializer().map(i -> i == target).orElse(false)) {
                        break;
                    }
                    declare(v, st);
                }
            }
            atTarget = copy(st);
            return null;
        }
        if (s instanceof IfStmt i) {
            if (target.isDescendantOf(i.getCondition()) || i.getCondition() == target) {
                throw new Bail("target in condition");
            }
            Val c = eval(i.getCondition(), st);
            boolean inThen = target.isDescendantOf(i.getThenStmt());
            Optional<Boolean> folded = foldedBoolean(c);
            if (folded.isPresent()) {
                pruned.add(line(i));
                if (folded.get() != inThen) {
                    dead = true;
                    deadLine = line(i);
                    return null;
                }
            }
            return descend(inThen ? i.getThenStmt() : i.getElseStmt().orElseThrow(), st);
        }
        if (s instanceof TryStmt t) {
            if (!target.isDescendantOf(t.getTryBlock())) {
                for (Expression r : t.getResources()) {
                    if (target.isDescendantOf(r)) {
                        if (r.findAll(AssignExpr.class).size() > 0) {
                            throw new Bail("side effect in resource");
                        }
                        atTarget = copy(st);
                        return null;
                    }
                    eval(r, st);
                }
                throw new Bail("target in catch or finally");
            }
            for (Expression r : t.getResources()) {
                eval(r, st);
            }
            return descend(t.getTryBlock(), st);
        }
        if (s instanceof SwitchStmt sw) {
            return descendSwitch(sw, st);
        }
        if (s instanceof LabeledStmt l) {
            return descend(l.getStatement(), st);
        }
        if (s instanceof SynchronizedStmt sy) {
            return descend(sy.getBody(), st);
        }
        throw new Bail("target inside " + s.getClass().getSimpleName());
    }

    private Map<String, Val> execSwitch(SwitchStmt sw, Map<String, Val> st) {
        Val sel = eval(sw.getSelector(), st);
        NodeList<SwitchEntry> entries = sw.getEntries();
        Optional<Integer> start = matchingEntry(sw, sel);
        if (start.isPresent()) {
            pruned.add(line(sw));
            return start.get() < 0 ? st : runEntries(entries, start.get(), st);
        }
        Map<String, Val> out = entries.stream().anyMatch(e -> e.getLabels().isEmpty()) ? null : st;
        for (int i = 0; i < entries.size(); i++) {
            out = join(out, runEntries(entries, i, copy(st)));
        }
        return out;
    }

    /** Index of the entry a constant selector picks, -1 if none matches and no default; empty if not foldable. */
    private Optional<Integer> matchingEntry(SwitchStmt sw, Val sel) {
        if (mode != Mode.FOLDING || !(sel instanceof Val.Const c)) {
            return Optional.empty();
        }
        int def = -1;
        for (int i = 0; i < sw.getEntries().size(); i++) {
            SwitchEntry e = sw.getEntries().get(i);
            if (e.getLabels().isEmpty()) {
                def = i;
            }
            for (Expression label : e.getLabels()) {
                Val l = eval(label, new HashMap<>());
                if (!(l instanceof Val.Const lc)) {
                    return Optional.empty();
                }
                if (constEquals(c.value(), lc.value())) {
                    return Optional.of(i);
                }
            }
        }
        return Optional.of(def);
    }

    /** Runs entries from {@code from} with fall-through until an unlabelled break; null if it never completes. */
    private Map<String, Val> runEntries(NodeList<SwitchEntry> entries, int from, Map<String, Val> st) {
        Map<String, Val> cur = st;
        for (int i = from; i < entries.size(); i++) {
            SwitchEntry e = entries.get(i);
            for (Statement x : e.getStatements()) {
                if (x instanceof BreakStmt b) {
                    if (b.getLabel().isPresent()) {
                        throw new Bail("labelled break");
                    }
                    return cur;
                }
                if (!x.findAll(BreakStmt.class).isEmpty()) {
                    throw new Bail("nested break in switch");
                }
                cur = exec(x, cur);
                if (cur == null) {
                    return null;
                }
            }
            if (e.getType() != SwitchEntry.Type.STATEMENT_GROUP) {
                return cur;
            }
        }
        return cur;
    }

    private Map<String, Val> descendSwitch(SwitchStmt sw, Map<String, Val> st) {
        if (target.isDescendantOf(sw.getSelector())) {
            throw new Bail("target in selector");
        }
        Val sel = eval(sw.getSelector(), st);
        NodeList<SwitchEntry> entries = sw.getEntries();
        int at = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (target.isDescendantOf(entries.get(i))) {
                at = i;
            }
        }
        Optional<Integer> start = matchingEntry(sw, sel);
        int from;
        if (start.isPresent()) {
            pruned.add(line(sw));
            if (start.get() < 0 || start.get() > at) {
                dead = true;
                deadLine = line(sw);
                return null;
            }
            from = start.get();
        } else {
            boolean prevFallsThrough = at > 0 && entries.get(at - 1).getType() == SwitchEntry.Type.STATEMENT_GROUP
                    && entries.get(at - 1).getStatements().stream().noneMatch(x -> x instanceof BreakStmt
                    || x instanceof ReturnStmt || x instanceof ThrowStmt);
            if (prevFallsThrough) {
                throw new Bail("fall-through into the target entry");
            }
            from = at;
        }
        Map<String, Val> cur = st;
        for (int i = from; i < at; i++) {
            cur = runEntries(entries, i, cur);
            if (cur == null) {
                throw new Bail("switch entry before the target never completes");
            }
            if (entries.get(i).getStatements().stream().anyMatch(x -> x instanceof BreakStmt)) {
                dead = true;
                deadLine = line(sw);
                return null;
            }
        }
        for (Statement x : entries.get(at).getStatements()) {
            if (target.isDescendantOf(x)) {
                return descend(x, cur);
            }
            if (x instanceof BreakStmt || !x.findAll(BreakStmt.class).isEmpty()) {
                throw new Bail("break before target");
            }
            cur = exec(x, cur);
            if (cur == null) {
                throw new Bail("target after a statement that never completes");
            }
        }
        throw new Bail("target not found in switch entry");
    }

    /** Every local assigned or declared inside {@code n} becomes tainted afterwards. */
    private Map<String, Val> havoc(Node n, Map<String, Val> st) {
        for (AssignExpr a : n.findAll(AssignExpr.class)) {
            if (a.getTarget() instanceof NameExpr ne) {
                st.put(ne.getNameAsString(), Val.TAINTED);
            }
        }
        for (UnaryExpr u : n.findAll(UnaryExpr.class)) {
            if (isIncDec(u) && u.getExpression() instanceof NameExpr ne) {
                st.put(ne.getNameAsString(), Val.TAINTED);
            }
        }
        for (VariableDeclarator v : n.findAll(VariableDeclarator.class)) {
            st.put(v.getNameAsString(), Val.TAINTED);
        }
        return st;
    }

    // ---------------------------------------------------------------------------------------------- expressions

    Val eval(Expression e, Map<String, Val> st) {
        if (e instanceof StringLiteralExpr s) {
            return new Val.Const(s.asString(), 0);
        }
        if (e instanceof TextBlockLiteralExpr t) {
            return new Val.Const(t.asString(), 0);
        }
        if (e instanceof IntegerLiteralExpr i) {
            return new Val.Const(i.asNumber().intValue(), 0);
        }
        if (e instanceof LongLiteralExpr l) {
            return new Val.Const(l.asNumber().longValue(), 0);
        }
        if (e instanceof CharLiteralExpr c) {
            return new Val.Const(c.asChar(), 0);
        }
        if (e instanceof BooleanLiteralExpr b) {
            return new Val.Const(b.getValue(), 0);
        }
        if (e instanceof DoubleLiteralExpr d) {
            return new Val.Const(d.asDouble(), 0);
        }
        if (e instanceof NullLiteralExpr || e instanceof ClassExpr || e instanceof InstanceOfExpr) {
            if (e instanceof InstanceOfExpr io) {
                eval(io.getExpression(), st);
            }
            return new Val.Safe(0);
        }
        if (e instanceof EnclosedExpr en) {
            return eval(en.getInner(), st);
        }
        if (e instanceof CastExpr c) {
            Val v = eval(c.getExpression(), st);
            if (v instanceof Val.Const k && c.getType() instanceof PrimitiveType p) {
                return castConst(k, p);
            }
            return v;
        }
        if (e instanceof NameExpr n) {
            return lookup(n.getNameAsString(), st);
        }
        if (e instanceof FieldAccessExpr fa) {
            return fieldAccess(fa);
        }
        if (e instanceof VariableDeclarationExpr vde) {
            vde.getVariables().forEach(v -> declare(v, st));
            return new Val.Safe(0);
        }
        if (e instanceof AssignExpr a) {
            return assign(a, st);
        }
        if (e instanceof UnaryExpr u) {
            return unary(u, st);
        }
        if (e instanceof BinaryExpr b) {
            return binary(b, st);
        }
        if (e instanceof ConditionalExpr c) {
            Val cond = eval(c.getCondition(), st);
            Optional<Boolean> folded = foldedBoolean(cond);
            if (folded.isPresent()) {
                pruned.add(line(c));
                return eval(folded.get() ? c.getThenExpr() : c.getElseExpr(), st);
            }
            Map<String, Val> t = copy(st);
            Map<String, Val> f = copy(st);
            Val vt = eval(c.getThenExpr(), t);
            Val vf = eval(c.getElseExpr(), f);
            replace(st, join(t, f));
            return Val.join(vt, vf);
        }
        if (e instanceof ArrayInitializerExpr ai) {
            Val v = new Val.Safe(0);
            for (Expression x : ai.getValues()) {
                v = Val.join(v, eval(x, st));
            }
            return v;
        }
        if (e instanceof ArrayCreationExpr ac) {
            return ac.getInitializer().map(i -> eval(i, st)).orElse(Val.TAINTED);
        }
        if (e instanceof ArrayAccessExpr aa) {
            Val arr = eval(aa.getName(), st);
            eval(aa.getIndex(), st);
            return arr;
        }
        if (e instanceof ObjectCreationExpr oc) {
            List<Val> args = oc.getArguments().stream().map(x -> eval(x, st)).toList();
            String type = oc.getType().getNameAsString();
            boolean allSafe = args.stream().allMatch(Val::safe);
            if (type.equals("String") && args.size() == 1 && args.getFirst() instanceof Val.Const k
                    && k.value() instanceof String) {
                return k;
            }
            if (allSafe && Set.of("String", "File").contains(type)) {
                return joinAll(args);
            }
            return Val.TAINTED;
        }
        if (e instanceof MethodCallExpr mc) {
            return call(mc, st);
        }
        return Val.TAINTED;
    }

    private void declare(VariableDeclarator v, Map<String, Val> st) {
        Val init = v.getInitializer().map(i -> eval(i, st)).orElse(Val.UNASSIGNED);
        String name = v.getNameAsString();
        Type t = v.getType();
        boolean tracked = t instanceof PrimitiveType
                || (t instanceof ClassOrInterfaceType c && TRACKED_TYPES.contains(c.asString()))
                || (t instanceof VarType && init instanceof Val.Const)
                || trackedArrays.contains(name);
        st.put(name, tracked ? init.withLine(definitionLine(init, v)) : Val.TAINTED);
    }

    private static int definitionLine(Val v, Node at) {
        return v.line() > 0 ? v.line() : line(at);
    }

    private Val lookup(String name, Map<String, Val> st) {
        Val v = st.get(name);
        if (v != null) {
            return v instanceof Val.Unassigned ? Val.TAINTED : v;
        }
        return constantField(name);
    }

    /** A {@code final} field of a tracked type with an initializer, declared exactly once in this file. */
    private Val constantField(String name) {
        List<VariableDeclarator> found = method.findCompilationUnit().map(cu -> cu.findAll(FieldDeclaration.class))
                .orElse(List.of()).stream()
                .filter(FieldDeclaration::isFinal)
                .flatMap(f -> f.getVariables().stream())
                .filter(v -> v.getNameAsString().equals(name))
                .toList();
        if (found.size() != 1 || found.getFirst().getInitializer().isEmpty() || fieldDepth > 8) {
            return Val.TAINTED;
        }
        VariableDeclarator v = found.getFirst();
        Type t = v.getType();
        if (!(t instanceof PrimitiveType) && !TRACKED_TYPES.contains(t.asString())) {
            return Val.TAINTED;
        }
        fieldDepth++;
        try {
            Val init = eval(v.getInitializer().get(), new HashMap<>());
            return init.safe() ? init.withLine(definitionLine(init, v)) : Val.TAINTED;
        } finally {
            fieldDepth--;
        }
    }

    /**
     * {@code this.X} or a same-file constant; a static constant of another type ({@code Utils.TESTFILES_DIR},
     * upper-case name on an upper-case type) is assumed untainted (documented assumption, see PR).
     */
    private Val fieldAccess(FieldAccessExpr fa) {
        String name = fa.getNameAsString();
        if (fa.getScope().isThisExpr()) {
            return constantField(name);
        }
        Val local = constantField(name);
        if (local.safe()) {
            return local;
        }
        String scope = fa.getScope().toString();
        String last = scope.substring(scope.lastIndexOf('.') + 1);
        boolean typeScope = !last.isEmpty() && Character.isUpperCase(last.charAt(0))
                && (fa.getScope().isNameExpr() || fa.getScope().isFieldAccessExpr());
        if (typeScope && name.matches("[A-Z][A-Z0-9_]*")) {
            return new Val.Safe(0);
        }
        return Val.TAINTED;
    }

    private Val assign(AssignExpr a, Map<String, Val> st) {
        Val rhs = eval(a.getValue(), st);
        if (!(a.getTarget() instanceof NameExpr ne)) {
            if (a.getTarget() instanceof ArrayAccessExpr) {
                throw new Bail("array element write");
            }
            return rhs;
        }
        String name = ne.getNameAsString();
        Val old = st.get(name);
        if (old == null) {
            return rhs; // a field: not tracked
        }
        Val value;
        if (a.getOperator() == AssignExpr.Operator.ASSIGN) {
            value = rhs;
        } else {
            BinaryExpr.Operator op = a.getOperator().toBinaryOperator().orElseThrow(() -> new Bail("operator"));
            value = fold(op, old instanceof Val.Unassigned ? Val.TAINTED : old, rhs);
        }
        if (!isTrackedLocal(name)) {
            st.put(name, Val.TAINTED); // mutable or untracked type: never trusted
            return Val.TAINTED;
        }
        value = value.withLine(definitionLine(value, a));
        st.put(name, mode == Mode.WEAK ? Val.join(old, value) : value);
        return value;
    }

    private boolean isTrackedLocal(String name) {
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name)) {
                Type t = v.getType();
                return t instanceof PrimitiveType || TRACKED_TYPES.contains(t.asString())
                        || trackedArrays.contains(name);
            }
        }
        return method.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name)
                && (p.getType() instanceof PrimitiveType || TRACKED_TYPES.contains(p.getType().asString())));
    }

    private Val unary(UnaryExpr u, Map<String, Val> st) {
        Val v = eval(u.getExpression(), st);
        if (isIncDec(u)) {
            if (u.getExpression() instanceof NameExpr ne && st.containsKey(ne.getNameAsString())) {
                Val next = v instanceof Val.Const k && k.value() instanceof Integer n
                        ? new Val.Const(n + (u.getOperator().name().contains("INCREMENT") ? 1 : -1), line(u))
                        : (v.safe() ? new Val.Safe(line(u)) : Val.TAINTED);
                Val old = st.get(ne.getNameAsString());
                st.put(ne.getNameAsString(), mode == Mode.WEAK ? Val.join(old, next) : next);
            }
            return v.safe() ? new Val.Safe(v.line()) : Val.TAINTED;
        }
        if (!(v instanceof Val.Const k)) {
            return v.safe() ? new Val.Safe(v.line()) : Val.TAINTED;
        }
        Object x = k.value();
        return switch (u.getOperator()) {
            case LOGICAL_COMPLEMENT -> x instanceof Boolean b ? new Val.Const(!b, k.line()) : new Val.Safe(k.line());
            case MINUS -> x instanceof Integer i ? new Val.Const(-i, k.line())
                    : x instanceof Long l ? new Val.Const(-l, k.line())
                    : x instanceof Double d ? new Val.Const(-d, k.line()) : new Val.Safe(k.line());
            case PLUS -> k;
            case BITWISE_COMPLEMENT -> x instanceof Integer i ? new Val.Const(~i, k.line()) : new Val.Safe(k.line());
            default -> new Val.Safe(k.line());
        };
    }

    private static boolean isIncDec(UnaryExpr u) {
        return switch (u.getOperator()) {
            case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    private Val binary(BinaryExpr b, Map<String, Val> st) {
        BinaryExpr.Operator op = b.getOperator();
        Val l = eval(b.getLeft(), st);
        if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
            Optional<Boolean> lb = foldedBoolean(l);
            boolean shortCircuit = lb.isPresent() && lb.get() == (op == BinaryExpr.Operator.OR);
            if (shortCircuit) {
                return l;
            }
            Map<String, Val> rs = copy(st);
            Val r = eval(b.getRight(), rs);
            replace(st, lb.isPresent() ? rs : join(rs, st));
            return fold(op, l, r);
        }
        Val r = eval(b.getRight(), st);
        return fold(op, l, r);
    }

    private Val call(MethodCallExpr mc, Map<String, Val> st) {
        Optional<Expression> scopeExpr = mc.getScope();
        Val scope = null;
        String typeScope = null;
        if (scopeExpr.isPresent()) {
            Expression se = scopeExpr.get();
            if (se instanceof NameExpr ne && !st.containsKey(ne.getNameAsString())
                    && Character.isUpperCase(ne.getNameAsString().charAt(0))) {
                typeScope = ne.getNameAsString();
            } else if (isStringBuilderChain(se)) {
                scope = builderChain(se, st);
            } else {
                scope = eval(se, st);
            }
        }
        List<Val> args = mc.getArguments().stream().map(x -> eval(x, st)).toList();
        String name = mc.getNameAsString();
        if (typeScope != null) {
            return staticCall(typeScope, name, args);
        }
        if (scope == null || !PURE_STRING.contains(name)) {
            return Val.TAINTED;
        }
        if (!scope.safe() || !args.stream().allMatch(Val::safe)) {
            return Val.TAINTED;
        }
        int line = Math.max(scope.line(), joinAll(args).line());
        if (scope instanceof Val.Const k && k.value() instanceof String s
                && args.stream().allMatch(a -> a instanceof Val.Const)) {
            List<Object> a = args.stream().map(x -> ((Val.Const) x).value()).toList();
            Object r = stringMethod(s, name, a);
            if (r != null) {
                return new Val.Const(r, line);
            }
        }
        return new Val.Safe(line);
    }

    private static boolean isStringBuilderChain(Expression e) {
        Expression cur = e;
        while (cur instanceof MethodCallExpr m && Set.of("append", "insert").contains(m.getNameAsString())
                && m.getScope().isPresent()) {
            cur = m.getScope().get();
        }
        return cur != e || (cur instanceof ObjectCreationExpr oc
                && Set.of("StringBuilder", "StringBuffer").contains(oc.getType().getNameAsString()));
    }

    private Val builderChain(Expression e, Map<String, Val> st) {
        if (e instanceof ObjectCreationExpr oc) {
            if (!Set.of("StringBuilder", "StringBuffer").contains(oc.getType().getNameAsString())) {
                return Val.TAINTED;
            }
            Val v = new Val.Const("", 0);
            for (Expression a : oc.getArguments()) {
                Val x = eval(a, st);
                v = x instanceof Val.Const k && k.value() instanceof String ? x : (x.safe() ? new Val.Safe(x.line())
                        : Val.TAINTED);
            }
            return v;
        }
        if (e instanceof MethodCallExpr m && m.getScope().isPresent()) {
            Val base = builderChain(m.getScope().get(), st);
            Val acc = base;
            for (Expression a : m.getArguments()) {
                Val x = eval(a, st);
                acc = m.getNameAsString().equals("append") ? fold(BinaryExpr.Operator.PLUS, acc, x)
                        : (acc.safe() && x.safe() ? new Val.Safe(Math.max(acc.line(), x.line())) : Val.TAINTED);
            }
            return acc;
        }
        return Val.TAINTED;
    }

    private static Val staticCall(String type, String name, List<Val> args) {
        boolean known = switch (type) {
            case "String" -> Set.of("valueOf", "format", "join").contains(name);
            case "Integer", "Long" -> Set.of("parseInt", "parseLong", "valueOf", "toString").contains(name);
            case "Math" -> Set.of("abs", "max", "min").contains(name);
            default -> false;
        };
        if (!known || !args.stream().allMatch(Val::safe)) {
            return Val.TAINTED;
        }
        Val all = joinAll(args);
        if (args.size() == 1 && args.getFirst() instanceof Val.Const k) {
            Object x = k.value();
            if (type.equals("String") && name.equals("valueOf")) {
                return new Val.Const(String.valueOf(x), k.line());
            }
            if (type.equals("Integer") && Set.of("parseInt", "valueOf").contains(name) && x instanceof String s) {
                try {
                    return new Val.Const(Integer.parseInt(s), k.line());
                } catch (NumberFormatException ex) {
                    return new Val.Safe(k.line());
                }
            }
        }
        return new Val.Safe(all.line());
    }

    private static Object stringMethod(String s, String name, List<Object> a) {
        try {
            return switch (name) {
                case "length" -> a.isEmpty() ? s.length() : null;
                case "isEmpty" -> a.isEmpty() ? s.isEmpty() : null;
                case "trim" -> a.isEmpty() ? s.trim() : null;
                case "toUpperCase" -> a.isEmpty() ? s.toUpperCase(java.util.Locale.ROOT) : null;
                case "toLowerCase" -> a.isEmpty() ? s.toLowerCase(java.util.Locale.ROOT) : null;
                case "toString", "intern" -> a.isEmpty() ? s : null;
                case "charAt" -> a.size() == 1 && a.getFirst() instanceof Integer i ? s.charAt(i) : null;
                case "substring" -> a.size() == 1 && a.getFirst() instanceof Integer i ? s.substring(i)
                        : a.size() == 2 && a.get(0) instanceof Integer i && a.get(1) instanceof Integer j
                        ? s.substring(i, j) : null;
                case "equals" -> a.size() == 1 ? s.equals(a.getFirst()) : null;
                case "equalsIgnoreCase" -> a.size() == 1 && a.getFirst() instanceof String o
                        ? s.equalsIgnoreCase(o) : null;
                case "contains" -> a.size() == 1 && a.getFirst() instanceof String o ? s.contains(o) : null;
                case "startsWith" -> a.size() == 1 && a.getFirst() instanceof String o ? s.startsWith(o) : null;
                case "endsWith" -> a.size() == 1 && a.getFirst() instanceof String o ? s.endsWith(o) : null;
                case "concat" -> a.size() == 1 && a.getFirst() instanceof String o ? s.concat(o) : null;
                case "indexOf" -> a.size() == 1 && a.getFirst() instanceof String o ? s.indexOf(o)
                        : a.size() == 1 && a.getFirst() instanceof Character c ? s.indexOf(c) : null;
                default -> null;
            };
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------- folding

    static Val fold(BinaryExpr.Operator op, Val l, Val r) {
        if (!l.safe() || !r.safe()) {
            return Val.TAINTED;
        }
        int line = Math.max(l.line(), r.line());
        if (!(l instanceof Val.Const lc) || !(r instanceof Val.Const rc)) {
            return new Val.Safe(line);
        }
        Object a = lc.value();
        Object b = rc.value();
        if (op == BinaryExpr.Operator.PLUS && (a instanceof String || b instanceof String)) {
            return new Val.Const(String.valueOf(a) + b, line);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            Object res = switch (op) {
                case AND, BINARY_AND -> x && y;
                case OR, BINARY_OR -> x || y;
                case XOR -> x ^ y;
                case EQUALS -> x.equals(y);
                case NOT_EQUALS -> !x.equals(y);
                default -> null;
            };
            return res == null ? new Val.Safe(line) : new Val.Const(res, line);
        }
        Optional<Number> na = number(a);
        Optional<Number> nb = number(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return new Val.Safe(line);
        }
        Object res = arithmetic(op, na.get(), nb.get());
        return res == null ? new Val.Safe(line) : new Val.Const(res, line);
    }

    private static Optional<Number> number(Object o) {
        if (o instanceof Character c) {
            return Optional.of((int) c);
        }
        if (o instanceof Integer || o instanceof Long || o instanceof Double) {
            return Optional.of((Number) o);
        }
        return Optional.empty();
    }

    private static Object arithmetic(BinaryExpr.Operator op, Number a, Number b) {
        if (a instanceof Double || b instanceof Double) {
            double x = a.doubleValue();
            double y = b.doubleValue();
            return switch (op) {
                case PLUS -> x + y;
                case MINUS -> x - y;
                case MULTIPLY -> x * y;
                case DIVIDE -> x / y;
                case LESS -> x < y;
                case GREATER -> x > y;
                case LESS_EQUALS -> x <= y;
                case GREATER_EQUALS -> x >= y;
                case EQUALS -> x == y;
                case NOT_EQUALS -> x != y;
                default -> null;
            };
        }
        if (a instanceof Long || b instanceof Long) {
            long x = a.longValue();
            long y = b.longValue();
            return switch (op) {
                case PLUS -> x + y;
                case MINUS -> x - y;
                case MULTIPLY -> x * y;
                case DIVIDE -> x / y;
                case REMAINDER -> x % y;
                case LESS -> x < y;
                case GREATER -> x > y;
                case LESS_EQUALS -> x <= y;
                case GREATER_EQUALS -> x >= y;
                case EQUALS -> x == y;
                case NOT_EQUALS -> x != y;
                default -> null;
            };
        }
        int x = a.intValue();
        int y = b.intValue();
        return switch (op) {
            case PLUS -> x + y;
            case MINUS -> x - y;
            case MULTIPLY -> x * y;
            case DIVIDE -> x / y;
            case REMAINDER -> x % y;
            case BINARY_AND -> x & y;
            case BINARY_OR -> x | y;
            case XOR -> x ^ y;
            case LEFT_SHIFT -> x << y;
            case SIGNED_RIGHT_SHIFT -> x >> y;
            case UNSIGNED_RIGHT_SHIFT -> x >>> y;
            case LESS -> x < y;
            case GREATER -> x > y;
            case LESS_EQUALS -> x <= y;
            case GREATER_EQUALS -> x >= y;
            case EQUALS -> x == y;
            case NOT_EQUALS -> x != y;
            default -> null;
        };
    }

    private static Val castConst(Val.Const k, PrimitiveType p) {
        Optional<Number> n = number(k.value());
        if (n.isEmpty()) {
            return k;
        }
        return switch (p.getType()) {
            case INT -> new Val.Const(n.get().intValue(), k.line());
            case LONG -> new Val.Const(n.get().longValue(), k.line());
            case DOUBLE -> new Val.Const(n.get().doubleValue(), k.line());
            case CHAR -> new Val.Const((char) n.get().intValue(), k.line());
            default -> new Val.Safe(k.line());
        };
    }

    private static boolean constEquals(Object sel, Object label) {
        Optional<Number> a = number(sel);
        Optional<Number> b = number(label);
        if (a.isPresent() && b.isPresent()) {
            return a.get().longValue() == b.get().longValue();
        }
        return sel.equals(label);
    }

    private Optional<Boolean> foldedBoolean(Val v) {
        if (mode == Mode.FOLDING && v instanceof Val.Const k && k.value() instanceof Boolean b) {
            return Optional.of(b);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------------------------------------- state

    private static Map<String, Val> copy(Map<String, Val> st) {
        return st == null ? null : new HashMap<>(st);
    }

    private static Map<String, Val> join(Map<String, Val> a, Map<String, Val> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Map<String, Val> out = new HashMap<>(a);
        b.forEach((k, v) -> out.merge(k, v, Val::join));
        return out;
    }

    private static void replace(Map<String, Val> st, Map<String, Val> with) {
        st.clear();
        st.putAll(with);
    }

    private static Val joinAll(List<Val> vals) {
        Val v = new Val.Safe(0);
        for (Val x : vals) {
            v = Val.join(v, x);
        }
        return vals.size() == 1 ? vals.getFirst() : v;
    }

    static int line(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }
}
