package com.shop.app;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String username = req.getParameter("username");
        try (Connection conn = Db.get()) {
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("select id from users where username = '" + username + "'");
            if (rs.next()) {
                resp.getWriter().println("ok");
            } else {
                resp.sendError(401);
            }
        } catch (SQLException e) {
            resp.sendError(500);
        }
    }
}
