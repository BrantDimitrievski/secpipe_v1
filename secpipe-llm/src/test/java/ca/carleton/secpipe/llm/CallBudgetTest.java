package ca.carleton.secpipe.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.carleton.secpipe.contracts.agent.AgentRole;
import ca.carleton.secpipe.contracts.run.RunMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

class CallBudgetTest {

    @Test
    void reservationProtectsVerifiersFromHunters() {
        CallBudget b = new CallBudget(5, 0);
        assertEquals(2, b.reserve(AgentRole.RECORD_VERIFIER, 2));
        int hunterCalls = 0;
        while (b.tryAcquire(AgentRole.HUNTER)) {
            hunterCalls++;
        }
        assertEquals(3, hunterCalls);
        assertTrue(b.tryAcquire(AgentRole.RECORD_VERIFIER));
        assertTrue(b.tryAcquire(AgentRole.RECORD_VERIFIER));
        assertFalse(b.tryAcquire(AgentRole.RECORD_VERIFIER));
        assertTrue(b.exhausted());
    }

    @Test
    void reserveGrantsOnlyWhatIsFree() {
        CallBudget b = new CallBudget(3, 0);
        assertEquals(3, b.reserve(AgentRole.CRITIC, 10));
        assertEquals(0, b.reserve(AgentRole.HUNTER, 1));
    }

    @Test
    void refundReturnsACall() {
        CallBudget b = new CallBudget(1, 0);
        assertTrue(b.tryAcquire(AgentRole.HUNTER));
        assertFalse(b.tryAcquire(AgentRole.HUNTER));
        b.refund(AgentRole.HUNTER);
        assertEquals(0, b.used());
        assertTrue(b.tryAcquire(AgentRole.HUNTER));
    }

    @Test
    void snapshotAndExhaustionReason() {
        CallBudget b = new CallBudget(2, 4);
        b.reserve(AgentRole.DISPROVER, 1);
        assertTrue(b.exhaustionReason().isEmpty());
        b.tryAcquire(AgentRole.HUNTER);
        b.tryAcquireSandboxRun();
        RunMetadata.BudgetSnapshot s = b.snapshot();
        assertEquals(new RunMetadata.BudgetSnapshot(2, 1, Map.of("disprover", 1), false, 4, 1), s);
        b.tryAcquire(AgentRole.DISPROVER);
        assertTrue(b.exhaustionReason().orElseThrow().contains("--max-agent-calls"));
    }

    @Test
    void sandboxRunsAreIndependent() {
        CallBudget b = new CallBudget(0, 2);
        assertFalse(b.tryAcquire(AgentRole.HUNTER));
        assertTrue(b.tryAcquireSandboxRun());
        assertTrue(b.tryAcquireSandboxRun());
        assertFalse(b.tryAcquireSandboxRun());
    }

    @Test
    void neverExceedsMaxUnderSixteenThreads() throws InterruptedException {
        CallBudget b = new CallBudget(5000, 0);
        b.reserve(AgentRole.RECORD_VERIFIER, 100);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            AgentRole role = t % 2 == 0 ? AgentRole.HUNTER : AgentRole.RECORD_VERIFIER;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 1000; i++) {
                    if (b.tryAcquire(role)) {
                        granted.incrementAndGet();
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        start.countDown();
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(5000, granted.get());
        assertEquals(5000, b.used());
    }
}
