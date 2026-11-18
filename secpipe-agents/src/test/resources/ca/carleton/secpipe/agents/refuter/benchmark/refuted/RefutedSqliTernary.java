// cwe=CWE-89 rule=refuter.dead_branch
package org.owasp.benchmark.testcode;

public class RefutedSqliTernary extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("RefutedSqliTernary");
        if (param == null) param = "";

        String bar;
        int num = 106;
        bar = (7 * 18) + num > 200 ? "This_should_always_happen" : param;

        String sql = "SELECT * from USERS where USERNAME='foo' and PASSWORD='" + bar + "'";
        try {
            java.util.List<String> list =
                org.owasp.benchmark.helpers.DatabaseHelper.JDBCtemplate.queryForList(sql, String.class); // SINK
        } catch (org.springframework.dao.DataAccessException e) {
            response.getWriter().println("Error processing request.");
        }
    }
}
