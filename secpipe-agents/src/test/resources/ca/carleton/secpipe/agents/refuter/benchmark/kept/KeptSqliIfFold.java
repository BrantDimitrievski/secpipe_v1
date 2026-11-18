// cwe=CWE-89
package org.owasp.benchmark.testcode;

public class KeptSqliIfFold extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptSqliIfFold");
        if (param == null) param = "";

        String bar;
        int num = 106;
        if ((7 * 42) - num > 200) bar = "This should never happen";
        else bar = param;

        String sql = "SELECT * from USERS where USERNAME='foo' and PASSWORD='" + bar + "'";
        try {
            java.sql.Statement statement = org.owasp.benchmark.helpers.DatabaseHelper.getSqlStatement();
            statement.execute(sql); // SINK
        } catch (java.sql.SQLException e) {
            throw new javax.servlet.ServletException(e);
        }
    }
}
