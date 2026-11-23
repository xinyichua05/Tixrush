package com.tixrush.order;

import com.tixrush.common.TooBusyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VERSION 3: OPTIMISTIC LOCK. "Assume there WON'T be a fight; detect it if there is."
 *
 *   Buyer A: read (stock 10, version 41)     Buyer B: read (stock 10, version 41)
 *   Buyer A: UPDATE ... WHERE version = 41   -> 1 row, OK. Version is now 42
 *   Buyer B: UPDATE ... WHERE version = 41   -> 0 rows! Conflict, so roll back and RETRY
 *   Buyer B (retry): read (stock 9, version 42) -> UPDATE ... WHERE version = 42 -> OK
 *
 * Nobody waits on a lock, but when many buyers hit the SAME row, most attempts
 * collide and retry. Under a flash sale that means lots of wasted work.
 * After MAX_ATTEMPTS we give up and return 503 "try again".
 */
@Component
public class OptimisticPurchaseStrategy implements PurchaseStrategy {

    private static final int MAX_ATTEMPTS = 10;

    private final OptimisticPurchaseAttempt attempt;
    private final Counter retryCounter;          // visible at /actuator/metrics/tixrush.purchase.optimistic.retries
    private final AtomicLong retriesSinceReset = new AtomicLong(); // shown in the admin stats endpoint

    public OptimisticPurchaseStrategy(OptimisticPurchaseAttempt attempt, MeterRegistry meterRegistry) {
        this.attempt = attempt;
        this.retryCounter = Counter.builder("tixrush.purchase.optimistic.retries")
                .description("Optimistic-lock conflicts that caused a retry")
                .register(meterRegistry);
    }

    @Override
    public String name() {
        return "optimistic";
    }

    // NOT @Transactional: each loop iteration gets its own fresh transaction inside attempt().
    @Override
    public OrderResponse purchase(Long eventId, Long userId) {
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            try {
                return attempt.attempt(eventId, userId, name());
            } catch (OptimisticLockingFailureException conflict) {
                retryCounter.increment();
                retriesSinceReset.incrementAndGet();
                backOff(i);
            }
            // SoldOutException is NOT caught: sold out is a final answer, retrying won't help.
        }
        throw new TooBusyException("Too many buyers right now, please try again");
    }

    /** Wait a random, growing amount, so buyers that collided don't all retry at the same instant. */
    private static void backOff(int attemptNumber) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, 5L * attemptNumber + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long retriesSinceReset() {
        return retriesSinceReset.get();
    }

    public void resetRetries() {
        retriesSinceReset.set(0);
    }
}
