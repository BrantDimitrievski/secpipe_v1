// cwe=CWE-89
package org.owasp.benchmark.testcode;

public class KeptSqliLoopOverwrite extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptSqliLoopOverwrite");
        if (param == null) param = "";

        String bar = param;
        for (int i = 0; i < param.length(); i++) {
            bar = "x";
        }
        String sql = "SELECT * from USERS where USERNAME='" + bar + "'";
        try {
            java.sql.Statement statement = org.owasp.benchmark.helpers.DatabaseHelper.getSqlStatement();
            statement.executeQuery(sql); // SINK
        } catch (java.sql.SQLException e) {
            throw new javax.servlet.ServletException(e);
        }
    }
}
