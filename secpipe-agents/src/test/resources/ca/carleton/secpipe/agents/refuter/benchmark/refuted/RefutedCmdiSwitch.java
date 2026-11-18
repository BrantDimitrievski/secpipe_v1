// cwe=CWE-78 rule=refuter.dead_branch
package org.owasp.benchmark.testcode;

public class RefutedCmdiSwitch extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("RefutedCmdiSwitch");
        if (param == null) param = "";

        String bar;
        String guess = "ABC";
        char switchTarget = guess.charAt(1);
        switch (switchTarget) {
            case 'A':
                bar = param;
                break;
            case 'B':
                bar = "bob";
                break;
            case 'C':
            case 'D':
                bar = param;
                break;
            default:
                bar = "bob's your uncle";
                break;
        }
        String a1 = "sh";
        String a2 = "-c";
        String[] args = {a1, a2, "echo " + bar};
        Runtime r = Runtime.getRuntime();
        try {
            Process p = r.exec(args); // SINK
            org.owasp.benchmark.helpers.Utils.printOSCommandResults(p, response);
        } catch (java.io.IOException e) {
            response.getWriter().println("Problem executing cmdi");
        }
    }
}
