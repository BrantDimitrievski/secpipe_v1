package ca.carleton.secpipe.agents.refuter;

import ca.carleton.secpipe.contracts.spi.SourceIndex;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The types declared in the scanned sources, so {@link Flow} can tell a constant of another scanned class
 * ({@code Other.NAME}) from a JDK or library constant (WO-G.12). Files are parsed lazily, once, and only when a
 * cross-class upper-case field is met. {@link #NONE} knows no scanned type (every type is "not scanned").
 */
final class ScannedTypes {

    /** What the scanned sources say about {@code Type.NAME}. */
    enum Lookup {
        /** No scanned source declares a type of that simple name (JDK or library): keep the old assumption. */
        NOT_SCANNED,
        /** Every scanned declaration of the type declares {@code NAME} as a static final compile-time constant. */
        CONSTANT,
        /** The type is scanned but {@code NAME} is not a static final compile-time constant there. */
        NOT_CONSTANT
    }

    static final ScannedTypes NONE = new ScannedTypes(null);

    private final SourceIndex index;
    private Map<String, List<TypeDeclaration<?>>> byName;

    private ScannedTypes(SourceIndex index) {
        this.index = index;
    }

    static ScannedTypes of(SourceIndex index) {
        return new ScannedTypes(index);
    }

    /** Classifies {@code typeName.fieldName}; {@code typeName} is the simple name (last scope segment). */
    Lookup lookup(String typeName, String fieldName) {
        List<TypeDeclaration<?>> decls = types().getOrDefault(typeName, List.of());
        if (decls.isEmpty()) {
            return Lookup.NOT_SCANNED;
        }
        for (TypeDeclaration<?> td : decls) {
            if (!declaresConstant(td, fieldName)) {
                return Lookup.NOT_CONSTANT;
            }
        }
        return Lookup.CONSTANT;
    }

    private static boolean declaresConstant(TypeDeclaration<?> td, String fieldName) {
        boolean implicitStaticFinal = td instanceof AnnotationDeclaration
                || td instanceof ClassOrInterfaceDeclaration c && c.isInterface();
        for (BodyDeclaration<?> member : td.getMembers()) {
            if (!(member instanceof FieldDeclaration fd)) {
                continue;
            }
            for (VariableDeclarator v : fd.getVariables()) {
                if (!v.getNameAsString().equals(fieldName)) {
                    continue;
                }
                boolean staticFinal = implicitStaticFinal || fd.isStatic() && fd.isFinal();
                return staticFinal && v.getInitializer().map(ScannedTypes::isLiteralConstant).orElse(false);
            }
        }
        return false;
    }

    /** A string/number/char/boolean literal, or a unary/binary/parenthesised combination of such literals. */
    static boolean isLiteralConstant(Expression e) {
        return switch (e) {
            case StringLiteralExpr x -> true;
            case TextBlockLiteralExpr x -> true;
            case IntegerLiteralExpr x -> true;
            case LongLiteralExpr x -> true;
            case DoubleLiteralExpr x -> true;
            case CharLiteralExpr x -> true;
            case BooleanLiteralExpr x -> true;
            case EnclosedExpr x -> isLiteralConstant(x.getInner());
            case UnaryExpr x -> isLiteralConstant(x.getExpression());
            case BinaryExpr x -> isLiteralConstant(x.getLeft()) && isLiteralConstant(x.getRight());
            default -> false;
        };
    }

    private Map<String, List<TypeDeclaration<?>>> types() {
        if (byName != null) {
            return byName;
        }
        Map<String, List<TypeDeclaration<?>>> out = new HashMap<>();
        if (index != null) {
            JavaParser parser = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            for (String file : index.files()) {
                if (!file.endsWith(".java")) {
                    continue;
                }
                try {
                    Optional<List<String>> lines = index.lines(file);
                    if (lines.isEmpty()) {
                        continue;
                    }
                    ParseResult<CompilationUnit> parsed = parser.parse(String.join("\n", lines.get()));
                    parsed.getResult().filter(u -> parsed.isSuccessful()).ifPresent(u -> {
                        for (TypeDeclaration<?> td : u.findAll(TypeDeclaration.class)) {
                            out.computeIfAbsent(td.getNameAsString(), k -> new ArrayList<>()).add(td);
                        }
                    });
                } catch (RuntimeException e) {
                    // an unreadable or unparsable file declares no type we can vouch for
                }
            }
        }
        byName = out;
        return out;
    }
}
