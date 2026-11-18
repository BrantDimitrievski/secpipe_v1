package ca.carleton.secpipe.agents.refuter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.lineOf;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.runRule;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.servlet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterizedApiRuleTest {

    private final ParameterizedApiRule rule = new ParameterizedApiRule();

    @Test
    void refutesAPreparedStatementWithConstantQueryText() {
        String src = servlet("""
                        String sql = "SELECT * from USERS where USERNAME=? and PASSWORD=?";
                        java.sql.PreparedStatement ps = conn().prepareStatement(sql);
                        ps.setString(1, "foo"); // BIND
                        ps.setString(2, param);
                        ps.executeQuery(); // SINK
                """);
        Optional<Refutation> r = runRule(rule, src, "CWE-89");
        assertTrue(r.isPresent());
        assertEquals(Refutation.PARAMETERIZED, r.get().rule());
        assertEquals(lineOf(src, "// BIND"), r.get().controlLocation().line());
    }

    @Test
    void refutesAProcessBuilderWithAConstantNonShellProgram() {
        String src = servlet("""
                        new ProcessBuilder("tar", "-czf", "/backups/" + param + ".tgz").start(); // SINK
                """);
        Optional<Refutation> r = runRule(rule, src, "CWE-78");
        assertTrue(r.isPresent());
        assertEquals(Refutation.PARAMETERIZED, r.get().rule());
    }

    @Test
    void keepsAPreparedStatementBuiltByConcatenation() {
        String src = servlet("""
                        String sql = "SELECT * from USERS where USERNAME='" + param + "'";
                        java.sql.PreparedStatement ps = conn().prepareStatement(sql);
                        ps.execute(); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-89").isEmpty());
    }

    @Test
    void keepsAShellInvocation() {
        String src = servlet("""
                        String[] args = {"sh", "-c", "echo " + param};
                        Runtime.getRuntime().exec(args); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-78").isEmpty());
    }

    @Test
    void keepsWhenReflectionIsPresent() {
        String src = servlet("""
                        Class.forName("demo.Hook").getMethod("run").invoke(null);
                        new ProcessBuilder("ls", "-la", param).start(); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-78").isEmpty());
    }

    @Test
    void keepsAStatementExecuteWithAnArgument() {
        String src = servlet("""
                        java.sql.Statement st = conn().createStatement();
                        st.execute("select '" + param + "'"); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-89").isEmpty());
    }
}
