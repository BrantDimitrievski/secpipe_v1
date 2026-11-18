// cwe=CWE-22
package org.owasp.benchmark.testcode;

public class KeptPathPartialOverwrite extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptPathPartialOverwrite");
        if (param == null) param = "";

        String bar = param;
        if (param.length() > 1) {
            bar = "safe!";
        }
        java.io.File fileTarget = new java.io.File(org.owasp.benchmark.helpers.Utils.TESTFILES_DIR, bar); // SINK
        response.getWriter().println("Access to file: '" + fileTarget + "' created.");
    }
}
