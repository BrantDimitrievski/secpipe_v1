// cwe=CWE-78
package org.owasp.benchmark.testcode;

public class KeptCmdiShellBuilder extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptCmdiShellBuilder");
        if (param == null) param = "";

        String bar = param;
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo " + bar); // SINK
        Process p = pb.start();
        org.owasp.benchmark.helpers.Utils.printOSCommandResults(p, response);
    }
}
