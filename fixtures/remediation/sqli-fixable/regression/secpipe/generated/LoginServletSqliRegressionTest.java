package secpipe.generated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the CWE-89 finding in LoginServlet. It FAILS on the vulnerable code (the username payload is
 * part of the SQL text) and PASSES after a correct fix (the SQL text has a placeholder and the payload is bound with
 * setString). The recording Db stub covers both paths: createStatement/executeQuery(String) and
 * prepareStatement(String)/setString/executeQuery(). No assumption is used, so the test is never skipped.
 */
class LoginServletSqliRegressionTest {

    // Db.get() reads its JDBC URL from the environment, which is empty in the sandbox, so the test swaps in a
    // recording Db for LoginServlet only (child-first class loader).
    private static final String RECORDING_DB = String.join("\n",
            "package com.shop.app;",
            "import java.lang.reflect.Proxy;",
            "import java.sql.Connection;",
            "import java.sql.PreparedStatement;",
            "import java.sql.ResultSet;",
            "import java.sql.Statement;",
            "import java.util.ArrayList;",
            "import java.util.List;",
            "public class Db {",
            "    public static final List<String> SQL = new ArrayList<>();",
            "    public static final List<String> BOUND = new ArrayList<>();",
            "    public static final List<String> EXECUTED = new ArrayList<>();",
            "    private static Object proxy(Class<?> type, java.lang.reflect.InvocationHandler h) {",
            "        return Proxy.newProxyInstance(Db.class.getClassLoader(), new Class<?>[] {type}, h);",
            "    }",
            "    public static Connection get() {",
            "        ResultSet rs = (ResultSet) proxy(ResultSet.class,",
            "                (p, m, a) -> m.getReturnType() == boolean.class ? Boolean.FALSE : null);",
            "        Statement st = (Statement) proxy(Statement.class, (p, m, a) -> {",
            "            if (m.getName().equals(\"executeQuery\") && a != null && a.length == 1) {",
            "                SQL.add((String) a[0]);",
            "                EXECUTED.add((String) a[0]);",
            "                return rs;",
            "            }",
            "            return null;",
            "        });",
            "        return (Connection) proxy(Connection.class, (p, m, a) -> {",
            "            if (m.getName().equals(\"createStatement\")) {",
            "                return st;",
            "            }",
            "            if (m.getName().equals(\"prepareStatement\")) {",
            "                String sql = (String) a[0];",
            "                SQL.add(sql);",
            "                return proxy(PreparedStatement.class, (pp, pm, pa) -> {",
            "                    if (pm.getName().equals(\"setString\")) {",
            "                        BOUND.add((String) pa[1]);",
            "                        return null;",
            "                    }",
            "                    if (pm.getName().equals(\"executeQuery\") && (pa == null || pa.length == 0)) {",
            "                        EXECUTED.add(sql);",
            "                        return rs;",
            "                    }",
            "                    return pm.getReturnType() == boolean.class ? Boolean.FALSE : null;",
            "                });",
            "            }",
            "            return m.getReturnType() == boolean.class ? Boolean.FALSE : null;",
            "        });",
            "    }",
            "}",
            "");

    @Test
    void usernamePayloadMustNotReachTheQueryText() throws Exception {
        Path tmp = Files.createTempDirectory("secpipe-regression-");
        Path src = tmp.resolve("src/com/shop/app/Db.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, RECORDING_DB, StandardCharsets.UTF_8);
        Path out = tmp.resolve("classes");
        Files.createDirectories(out);
        int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString());
        assertEquals(0, rc, "the recording Db stub did not compile");

        Map<String, byte[]> defs = new HashMap<>();
        defs.put("com.shop.app.Db", Files.readAllBytes(out.resolve("com/shop/app/Db.class")));
        ClassLoader parent = LoginServletSqliRegressionTest.class.getClassLoader();
        try (InputStream in = parent.getResourceAsStream("com/shop/app/LoginServlet.class")) {
            defs.put("com.shop.app.LoginServlet", in.readAllBytes());
        }
        ClassLoader loader = new ChildFirst(parent, defs);

        String payload = "' OR '1'='1";
        HttpServletRequest req = (HttpServletRequest) Proxy.newProxyInstance(parent,
                new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> m.getName().equals("getParameter") ? payload : null);
        HttpServletResponse resp = (HttpServletResponse) Proxy.newProxyInstance(parent,
                new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> null);

        Class<?> servlet = loader.loadClass("com.shop.app.LoginServlet");
        Method doPost = servlet.getDeclaredMethod("doPost", HttpServletRequest.class, HttpServletResponse.class);
        doPost.setAccessible(true);
        doPost.invoke(servlet.getDeclaredConstructor().newInstance(), req, resp);

        Class<?> db = loader.loadClass("com.shop.app.Db");
        List<?> sql = (List<?>) db.getField("SQL").get(null);
        List<?> bound = (List<?>) db.getField("BOUND").get(null);
        List<?> executed = (List<?>) db.getField("EXECUTED").get(null);
        assertEquals(1, executed.size(), "the login query did not run exactly once: " + executed);
        assertFalse(sql.stream().anyMatch(q -> String.valueOf(q).contains(payload)),
                "attacker-controlled username reached the SQL text: " + sql);
        assertTrue(bound.contains(payload), "the username was not bound as a query parameter: " + bound);
    }

    private static final class ChildFirst extends ClassLoader {
        private final Map<String, byte[]> defs;

        ChildFirst(ClassLoader parent, Map<String, byte[]> defs) {
            super(parent);
            this.defs = defs;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null && defs.containsKey(name)) {
                    byte[] b = defs.get(name);
                    c = defineClass(name, b, 0, b.length);
                }
                return c != null ? c : super.loadClass(name, resolve);
            }
        }
    }
}
