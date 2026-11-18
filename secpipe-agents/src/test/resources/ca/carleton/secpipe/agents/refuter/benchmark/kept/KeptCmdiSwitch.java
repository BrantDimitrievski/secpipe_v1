// cwe=CWE-78
package org.owasp.benchmark.testcode;

public class KeptCmdiSwitch extends javax.servlet.http.HttpServlet {

    @Override
    public void doPost(javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response)
            throws javax.servlet.ServletException, java.io.IOException {
        response.setContentType("text/html;charset=UTF-8");
        String param = request.getParameter("KeptCmdiSwitch");
        if (param == null) param = "";

        String bar;
        String guess = "ABC";
        char switchTarget = guess.charAt(2);
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
        String[] args = {"sh", "-c", "echo " + bar};
        Runtime r = Runtime.getRuntime();
        Process p = r.exec(args); // SINK
    }
}
