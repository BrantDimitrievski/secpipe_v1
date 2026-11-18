package com.shop.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import javax.servlet.annotation.WebServlet;
import org.junit.jupiter.api.Test;

class TagFilterTest {

    @Test
    void isMappedToProductTags() {
        assertEquals("/products/tag", TagFilter.class.getAnnotation(WebServlet.class).value()[0]);
    }

    @Test
    void answersServerErrorWhenNoDatabaseIsConfigured() throws Exception {
        ServletStubs.Response resp = new ServletStubs.Response();
        new TagFilter().doGet(ServletStubs.request(Map.of("tag", "sale")), resp.proxy);
        assertEquals(500, resp.status);
    }
}
