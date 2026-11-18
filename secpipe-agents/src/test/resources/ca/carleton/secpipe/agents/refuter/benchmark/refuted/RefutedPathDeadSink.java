// cwe=CWE-22 rule=refuter.dead_branch
package org.owasp.benchmark.testcode;

public class RefutedPathDeadSink extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("RefutedPathDeadSink");
        if (param == null) param = "";

        java.io.File fileTarget = null;
        if ((500 / 42) + 196 > 200) {
            fileTarget = new java.io.File(org.owasp.benchmark.helpers.Utils.TESTFILES_DIR, "safe.txt");
        } else {
            fileTarget = new java.io.File(param); // SINK
        }
        response.getWriter().println("Access to file: '" + fileTarget + "' created.");
    }
}
