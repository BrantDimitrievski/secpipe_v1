package ca.carleton.secpipe.report;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.spi.Budget;

/** A budget with fixed numbers, for tests that only read it. */
final class FixedBudget implements Budget {

    @Override
    public int max() {
        return 40;
    }

    @Override
    public int used() {
        return 6;
    }

    @Override
    public int remaining() {
        return 34;
    }

    @Override
    public int reserve(AgentRole role, int n) {
        return 0;
    }

    @Override
    public boolean tryAcquire(AgentRole role) {
        return false;
    }

    @Override
    public boolean exhausted() {
        return false;
    }

    @Override
    public boolean tryAcquireSandboxRun() {
        return false;
    }
}
