package com.shop.app;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** JDK-proxy stand-ins for the servlet API, so the suite needs no mocking library and no container. */
final class ServletStubs {

    private ServletStubs() {
    }

    static HttpServletRequest request(Map<String, String> parameters) {
        return (HttpServletRequest) Proxy.newProxyInstance(ServletStubs.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getParameter")) {
                        return parameters.get((String) args[0]);
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /** Records the status passed to {@code sendError} and the text written to the response. */
    static final class Response {
        int status = 200;
        final StringWriter body = new StringWriter();
        final HttpServletResponse proxy = (HttpServletResponse) Proxy.newProxyInstance(
                ServletStubs.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class},
                (p, method, args) -> {
                    switch (method.getName()) {
                        case "sendError" -> {
                            status = (Integer) args[0];
                            return null;
                        }
                        case "getWriter" -> {
                            return new PrintWriter(body, true);
                        }
                        default -> {
                            return defaultValue(method.getReturnType());
                        }
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
