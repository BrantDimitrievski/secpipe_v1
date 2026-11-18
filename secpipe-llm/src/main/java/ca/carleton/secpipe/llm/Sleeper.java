package ca.carleton.secpipe.llm;

import java.time.Duration;

/** Waits between retries. Injectable so tests never really sleep. */
@FunctionalInterface
public interface Sleeper {

    /** Sleeps with {@link Thread#sleep(Duration)}. */
    Sleeper REAL = Thread::sleep;

    void sleep(Duration duration) throws InterruptedException;
}
