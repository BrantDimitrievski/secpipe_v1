// cwe=CWE-89
package org.owasp.benchmark.testcode;

public class KeptSqliTernary extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptSqliTernary");
        if (param == null) param = "";

        String bar;
        int num = 106;
        bar = (7 * 42) - num > 200 ? "This should never happen" : param;

        String sql = "SELECT * from USERS where USERNAME='foo' and PASSWORD='" + bar + "'";
        java.util.List<String> list =
                org.owasp.benchmark.helpers.DatabaseHelper.JDBCtemplate.queryForList(sql, String.class); // SINK
    }
}
