// cwe=CWE-89 rule=refuter.parameterized
package org.owasp.benchmark.testcode;

public class RefutedSqliPrepared extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("RefutedSqliPrepared");
        if (param == null) param = "";

        String bar = param;
        String sql = "SELECT * from USERS where USERNAME=? and PASSWORD=?";
        try {
            java.sql.Connection connection = org.owasp.benchmark.helpers.DatabaseHelper.getSqlConnection();
            java.sql.PreparedStatement statement = connection.prepareStatement(sql,
                    java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
            statement.setString(1, "foo");
            statement.setString(2, bar);
            statement.execute(); // SINK
        } catch (java.sql.SQLException e) {
            throw new javax.servlet.ServletException(e);
        }
    }
}
