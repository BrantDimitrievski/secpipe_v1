// cwe=CWE-78 rule=refuter.parameterized
package org.owasp.benchmark.testcode;

public class RefutedCmdiArgList extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("RefutedCmdiArgList");
        if (param == null) param = "";

        String bar = param;
        java.util.List<String> argList = java.util.Arrays.asList("ls", "-la", bar);
        ProcessBuilder pb = new ProcessBuilder(argList); // SINK
        try {
            Process p = pb.start();
            org.owasp.benchmark.helpers.Utils.printOSCommandResults(p, response);
        } catch (java.io.IOException e) {
            throw new javax.servlet.ServletException(e);
        }
    }
}
