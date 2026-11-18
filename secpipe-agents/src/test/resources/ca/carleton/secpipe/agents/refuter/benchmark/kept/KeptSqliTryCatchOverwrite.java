// cwe=CWE-89
package org.owasp.benchmark.testcode;

public class KeptSqliTryCatchOverwrite extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptSqliTryCatchOverwrite");
        if (param == null) param = "";

        String bar = param;
        try {
            Integer.parseInt(param);
            bar = "12";
        } catch (NumberFormatException e) {
            response.getWriter().println("not a number");
        }
        String sql = "SELECT * from USERS where USERNAME='foo' and PASSWORD='" + bar + "'";
        try {
            java.sql.Statement statement = org.owasp.benchmark.helpers.DatabaseHelper.getSqlStatement();
            statement.executeUpdate(sql); // SINK
        } catch (java.sql.SQLException e) {
            throw new javax.servlet.ServletException(e);
        }
    }
}
