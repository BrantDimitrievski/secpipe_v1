// cwe=CWE-89
package org.owasp.benchmark.testcode;

public class KeptSqliPreparedConcat extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptSqliPreparedConcat");
        if (param == null) param = "";

        String bar = param;
        String sql = "SELECT * from USERS where USERNAME='foo' and PASSWORD='" + bar + "'";
        try {
            java.sql.Connection connection = org.owasp.benchmark.helpers.DatabaseHelper.getSqlConnection();
            java.sql.PreparedStatement statement = connection.prepareStatement(sql);
            statement.execute(); // SINK
        } catch (java.sql.SQLException e) {
            throw new javax.servlet.ServletException(e);
        }
    }
}
