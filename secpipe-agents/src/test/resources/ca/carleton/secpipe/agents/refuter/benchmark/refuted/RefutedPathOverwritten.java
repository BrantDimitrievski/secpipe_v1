// cwe=CWE-22 rule=refuter.value_overwritten
package org.owasp.benchmark.testcode;

public class RefutedPathOverwritten extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("RefutedPathOverwritten");
        if (param == null) param = "";

        String bar = param;
        bar = "safe!";
        String fileName = org.owasp.benchmark.helpers.Utils.TESTFILES_DIR + bar;
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(fileName); // SINK
            response.getWriter().println("The beginning of file: '" + fileName + "' is:");
        } catch (Exception e) {
            response.getWriter().println("Couldn't open FileInputStream on file: '" + fileName + "'");
        }
    }
}
