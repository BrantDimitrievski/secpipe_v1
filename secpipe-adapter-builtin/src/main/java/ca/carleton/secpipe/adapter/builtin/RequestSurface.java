package ca.carleton.secpipe.adapter.builtin;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.Set;

/** Decides whether a file handles HTTP requests (an endpoint, or a method that reads request input). */
final class RequestSurface {

    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping",
            "GET", "POST", "PUT", "DELETE", "PATCH", "Path");
    private static final Set<String> SERVLET_METHODS = Set.of("doGet", "doPost", "doPut", "doDelete", "doPatch", "service");
    private static final Set<String> SOURCE_ANNOTATIONS = Set.of(
            "RequestParam", "PathVariable", "RequestHeader", "RequestBody", "CookieValue", "RequestPart",
            "ModelAttribute", "PathParam", "QueryParam", "FormParam", "HeaderParam");
    private static final Set<String> SOURCE_CALLS = Set.of(
            "getParameter", "getParameterValues", "getParameterMap", "getHeader", "getQueryString", "getPathInfo",
            "getRequestURI", "getCookies", "getInputStream", "getReader", "getPart");

    private RequestSurface() {
    }

    static boolean fileHandlesRequests(CompilationUnit cu) {
        for (CallableDeclaration<?> c : cu.findAll(CallableDeclaration.class)) {
            boolean servlet = c.findAncestor(ClassOrInterfaceDeclaration.class)
                    .map(t -> t.getExtendedTypes().stream().anyMatch(x -> x.getNameAsString().endsWith("HttpServlet")))
                    .orElse(false);
            boolean endpoint = c.getAnnotations().stream().anyMatch(a -> MAPPING_ANNOTATIONS.contains(a.getName().getIdentifier()))
                    || (servlet && SERVLET_METHODS.contains(c.getNameAsString()));
            boolean reads = c.getParameters().stream().anyMatch(p -> p.getAnnotations().stream()
                    .anyMatch(a -> SOURCE_ANNOTATIONS.contains(a.getName().getIdentifier())))
                    || c.findAll(MethodCallExpr.class).stream().anyMatch(m -> SOURCE_CALLS.contains(m.getNameAsString()));
            if (endpoint || reads) return true;
        }
        return false;
    }
}
