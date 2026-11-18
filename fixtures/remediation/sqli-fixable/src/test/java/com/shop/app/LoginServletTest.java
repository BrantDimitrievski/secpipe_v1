package com.shop.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import javax.servlet.annotation.WebServlet;
import org.junit.jupiter.api.Test;

/** Behaviour that must hold before and after a correct PreparedStatement fix. */
class LoginServletTest {

    @Test
    void isMappedToLogin() {
        WebServlet mapping = LoginServlet.class.getAnnotation(WebServlet.class);
        assertEquals("/login", mapping.value()[0]);
    }

    @Test
    void answersServerErrorWhenNoDatabaseIsConfigured() throws Exception {
        // the sandbox env has no SHOP_DB_URL, so Db.get() throws SQLException and the servlet answers 500
        ServletStubs.Response resp = new ServletStubs.Response();
        new LoginServlet().doPost(ServletStubs.request(Map.of("username", "alice")), resp.proxy);
        assertEquals(500, resp.status);
        assertEquals("", resp.body.toString());
    }
}
