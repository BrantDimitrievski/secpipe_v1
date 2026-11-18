package ca.carleton.secpipe.agents.refuter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.lineOf;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.runRule;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.servlet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstantArgumentRuleTest {

    private final ConstantArgumentRule rule = new ConstantArgumentRule();

    @Test
    void refutesWhenTheSinkArgumentFoldsToAConstant() {
        String src = servlet("""
                        log(param);
                        int limit = 10 * 5;
                        String sql = "select * from " + TABLE + " limit " + limit;
                        conn().createStatement().executeQuery(sql); // SINK
                """);
        Optional<Refutation> r = runRule(rule, src, "CWE-89");
        assertTrue(r.isPresent());
        assertEquals(Refutation.CONSTANT_VALUE, r.get().rule());
        assertEquals(lineOf(src, "// SINK"), r.get().controlLocation().line());
    }

    @Test
    void keepsWhenTheArgumentConcatenatesTheRequestValue() {
        String src = servlet("""
                        String sql = "select * from users where name = '" + param + "'";
                        conn().createStatement().executeQuery(sql); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-89").isEmpty());
    }

    @Test
    void keepsWhenAnyDefinitionIsTaintedEvenIfLaterOverwritten() {
        String src = servlet("""
                        String cmd = param;
                        cmd = "ls";
                        Runtime.getRuntime().exec(cmd); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-78").isEmpty());
    }

    @Test
    void keepsWhenValueComesFromAMutableBuilder() {
        String src = servlet("""
                        StringBuilder sb = new StringBuilder("ls ");
                        sb.append(param);
                        String cmd = "x";
                        cmd = sb.toString();
                        Runtime.getRuntime().exec(cmd); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-78").isEmpty());
    }
}
