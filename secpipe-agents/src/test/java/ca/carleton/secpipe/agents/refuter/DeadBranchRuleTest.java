package ca.carleton.secpipe.agents.refuter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.lineOf;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.runRule;
import static ca.carleton.secpipe.agents.refuter.RefuterFixtures.servlet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadBranchRuleTest {

    private final DeadBranchRule rule = new DeadBranchRule();

    @Test
    void refutesWhenTheTaintedBranchIsGuardedByAConstantFalseCondition() {
        String src = servlet("""
                        String bar;
                        int num = 86;
                        if ((7 * 42) - num > 200) { // COND
                            bar = "This_should_always_happen";
                        } else {
                            bar = param;
                        }
                        String sql = "SELECT * from USERS where USERNAME='foo' and PASSWORD='" + bar + "'";
                        conn().createStatement().execute(sql); // SINK
                """);
        Optional<Refutation> r = runRule(rule, src, "CWE-89");
        assertTrue(r.isPresent());
        assertEquals(Refutation.DEAD_BRANCH, r.get().rule());
        assertEquals(lineOf(src, "// COND"), r.get().controlLocation().line());
    }

    @Test
    void refutesWhenTheSinkItselfIsUnreachable() {
        String src = servlet("""
                        if ((500 / 42) + 196 > 200) { // COND
                            response.getWriter().println("ok");
                        } else {
                            new java.io.FileInputStream(param); // SINK
                        }
                """);
        Optional<Refutation> r = runRule(rule, src, "CWE-22");
        assertTrue(r.isPresent());
        assertEquals(lineOf(src, "// COND"), r.get().controlLocation().line());
    }

    @Test
    void keepsWhenTheConditionSelectsTheTaintedBranch() {
        String src = servlet("""
                        String bar;
                        int num = 106;
                        if ((7 * 42) - num > 200) {
                            bar = "This_should_never_happen";
                        } else {
                            bar = param;
                        }
                        conn().createStatement().execute("select '" + bar + "'"); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-89").isEmpty());
    }

    @Test
    void keepsWhenTheConditionDependsOnRequestData() {
        String src = servlet("""
                        String bar = param;
                        if (param.length() > 200) {
                            bar = "safe";
                        }
                        conn().createStatement().execute("select '" + bar + "'"); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-89").isEmpty());
    }

    @Test
    void foldsASwitchOnAConstantSelector() {
        String src = servlet("""
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
                            default:
                                bar = param;
                                break;
                        }
                        Runtime.getRuntime().exec("echo " + bar); // SINK
                """);
        assertTrue(runRule(rule, src, "CWE-78").isPresent());
    }
}
