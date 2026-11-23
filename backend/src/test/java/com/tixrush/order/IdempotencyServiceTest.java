package com.tixrush.order;

import org.springframework.context.annotation.Import;
import com.tixrush.RequiresServices;
import com.tixrush.TestcontainersConfiguration;

import com.tixrush.event.SoldOutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** "A double-click never creates two orders." Needs real Redis. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@RequiresServices
class IdempotencyServiceTest {

    @Autowired
    IdempotencyService idempotency;

    private final AtomicInteger purchases = new AtomicInteger();
    private final Long userId = -System.nanoTime(); // made-up user, so runs never collide

    private final Supplier<OrderResponse> buy = () -> {
        purchases.incrementAndGet();
        sleep(300); // a slow purchase, so a double-click lands while it's still running
        return OrderResponse.queued(UUID.randomUUID(), 1L, "queue", Instant.now());
    };

    @Test
    void sameKeyTwice_buysOnce_andReplaysTheSameAnswer() {
        String key = UUID.randomUUID().toString();

        IdempotencyService.Outcome first = idempotency.execute(userId, key, buy);
        IdempotencyService.Outcome second = idempotency.execute(userId, key, buy);

        assertThat(purchases).hasValue(1);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first.response());
    }

    @Test
    void doubleClick_atTheSameMoment_buysOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotencyService.Outcome> a = pool.submit(() -> { go.await(); return idempotency.execute(userId, key, buy); });
            Future<IdempotencyService.Outcome> b = pool.submit(() -> { go.await(); return idempotency.execute(userId, key, buy); });
            go.countDown();

            assertThat(a.get().response().reservationId()).isEqualTo(b.get().response().reservationId());
            assertThat(purchases).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeys_areDifferentPurchases() {
        idempotency.execute(userId, UUID.randomUUID().toString(), buy);
        idempotency.execute(userId, UUID.randomUUID().toString(), buy);

        assertThat(purchases).hasValue(2);
    }

    @Test
    void failedFirstAttempt_canBeRetriedWithTheSameKey() {
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotency.execute(userId, key, () -> { throw new SoldOutException(1L); }))
                .isInstanceOf(SoldOutException.class);
        IdempotencyService.Outcome retry = idempotency.execute(userId, key, buy);

        assertThat(retry.replayed()).isFalse();
        assertThat(purchases).hasValue(1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
