package ca.carleton.secpipe.agents.refuter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.lineOf;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.runRule;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.servlet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueOverwrittenRuleTest {

    private final ValueOverwrittenRule rule = new ValueOverwrittenRule();

    @Test
    void refutesWhenEveryPathOverwritesTheTaintedValue() {
        String src = servlet("""
                        String bar = param;
                        if ("a".equals(param)) {
                            bar = "alpha";
                        } else {
                            bar = "beta"; // OVERWRITE
                        }
                        String sql = "select * from users where name = '" + bar + "'";
                        java.sql.Statement st = conn().createStatement();
                        st.executeQuery(sql); // SINK
                """);
        Optional<Refutation> r = runRule(rule, src, "CWE-89");
        assertTrue(r.isPresent());
        assertEquals(Refutation.VALUE_OVERWRITTEN, r.get().rule());
        assertEquals(lineOf(src, "// OVERWRITE"), r.get().controlLocation().line());
        assertEquals(RefuterFixtures.FILE, r.get().controlLocation().file());
    }

    @Test
    void keepsWhenOnlySomePathsOverwrite() {
        String src = servlet("""
                        String bar = param;
                        if (param.length() > 3) {
                            bar = "safe";
                        }
                        Runtime.getRuntime().exec("ping " + bar); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-78").isEmpty());
    }

    @Test
    void keepsWhenOverwriteIsInsideTryCatch() {
        String src = servlet("""
                        String bar = param;
                        try {
                            Integer.parseInt(param);
                            bar = "safe";
                        } catch (NumberFormatException e) {
                            // fall through with the tainted value
                        }
                        new java.io.File("/data/" + bar); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-22").isEmpty());
    }

    @Test
    void keepsWhenOverwriteIsInsideALoop() {
        String src = servlet("""
                        String bar = param;
                        for (int i = 0; i < param.length(); i++) {
                            bar = "safe";
                        }
                        new java.io.File("/data/" + bar); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-22").isEmpty());
    }

    @Test
    void keepsWhenTheValueIsOnlyEverConstant() {
        // nothing was overwritten: that is ConstantArgumentRule's case, not this rule's
        String src = servlet("""
                        String bar = "fixed";
                        new java.io.File("/data/" + bar); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-22").isEmpty());
    }
}
