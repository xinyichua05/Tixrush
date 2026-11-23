package com.tixrush.order;

import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.event.SoldOutException;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same rush as PurchaseConcurrencyTest, but for version 4 (Redis counter).
 * Only runs when REDIS_URL is set. Without Redis it is skipped, not failed.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = "rediss?://.+")
class RedisPurchaseConcurrencyTest {

    private static final int TICKETS = 50;
    private static final int TOTAL_BUYERS = 200;

    @Autowired PurchaseService purchaseService;
    @Autowired RedisStockService redisStock;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;

    private Long eventId;
    private Long userId;

    @BeforeEach
    void createEvent() {
        Instant now = Instant.now();
        eventId = eventRepository.save(new Event("Redis Concurrency Test", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), TICKETS)).getId();
        userId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId();
    }

    @AfterEach
    void cleanUp() {
        redisStock.clear(eventId);
        eventRepository.deleteById(eventId);
    }

    @Test
    void redis_sellsExactlyAllTickets_neverMore() throws Exception {
        AtomicInteger bought = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(TOTAL_BUYERS);
        ExecutorService pool = Executors.newFixedThreadPool(32);

        for (int i = 0; i < TOTAL_BUYERS; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    purchaseService.purchase("redis", eventId, userId);
                    bought.incrementAndGet();
                } catch (SoldOutException e) {
                    soldOut.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    finished.countDown();
                }
            });
        }
        startGun.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        System.out.printf("[REDIS] bought=%d soldOut=%d other=%d redisStock=%d%n",
                bought.get(), soldOut.get(), other.get(), redisStock.getStock(eventId));

        assertThat(bought.get()).isEqualTo(TICKETS);
        assertThat(soldOut.get()).isEqualTo(TOTAL_BUYERS - TICKETS);
        assertThat(other.get()).isZero();
        assertThat(orderRepository.countByEventId(eventId)).isEqualTo(TICKETS);
        assertThat(redisStock.getStock(eventId)).isZero();
    }
}
