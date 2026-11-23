package com.tixrush.order;

import org.springframework.context.annotation.Import;
import com.tixrush.RequiresServices;
import com.tixrush.TestcontainersConfiguration;

import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.event.SoldOutException;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole v4 flow against REAL Redis + RabbitMQ + Postgres, with a 5-second payment hold:
 *   rush -> 202s -> workers create PENDING orders -> one is paid -> the rest expire -> tickets come back.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tixrush.orders.payment-hold=5s")
@RequiresServices(rabbit = true)
class QueuePurchaseFlowTest {

    private static final int TICKETS = 20;
    private static final int BUYERS = 100;

    @Autowired PurchaseService purchaseService;
    @Autowired OrderService orderService;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedisStockService redisStock;

    private Long eventId;
    private Long userId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        eventId = eventRepository.save(new Event("Queue Flow Test", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), TICKETS)).getId();
        userId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId();
    }

    @AfterEach
    void cleanUp() {
        redisStock.clear(eventId);
        eventRepository.deleteById(eventId); // orders go too: ON DELETE CASCADE
    }

    @Test
    void rush_thenHold_thenPayOne_restExpireAndReturnToStock() throws Exception {
        // 1. The rush: every buyer gets an instant answer, nobody waits for Postgres.
        List<UUID> reservations = new CopyOnWriteArrayList<>();
        AtomicInteger soldOut = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < BUYERS; i++) {
            pool.submit(() -> {
                try {
                    OrderResponse r = purchaseService.purchase("queue", eventId, userId);
                    assertThat(r.status()).isEqualTo(OrderResponse.QUEUED);
                    reservations.add(r.reservationId());
                } catch (SoldOutException e) {
                    soldOut.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(reservations).hasSize(TICKETS);
        assertThat(soldOut).hasValue(BUYERS - TICKETS);
        assertThat(redisStock.getStock(eventId)).isZero();

        // 2. The workers catch up: all 20 orders appear as PENDING_PAYMENT and the DB stock follows Redis.
        awaitTrue("20 pending orders + DB stock 0", Duration.ofSeconds(4),
                () -> pending() == TICKETS && available() == 0);

        // 3. One buyer pays in time.
        UUID paidOne = reservations.getFirst();
        assertThat(orderService.pay(paidOne, userId).status()).isEqualTo(Order.PAID);

        // 4. The hold runs out: the other 19 expire and their tickets go back, in the DB AND in Redis.
        awaitTrue("19 expired", Duration.ofSeconds(30),
                () -> orderRepository.countByEventIdAndStatus(eventId, Order.EXPIRED) == TICKETS - 1);
        awaitTrue("stock back to 19 in DB and Redis", Duration.ofSeconds(10),
                () -> available() == TICKETS - 1 && Integer.valueOf(TICKETS - 1).equals(redisStock.getStock(eventId)));
        assertThat(orderRepository.findByReservationId(paidOne).orElseThrow().getStatus()).isEqualTo(Order.PAID);
    }

    private long pending() {
        return orderRepository.countByEventIdAndStatus(eventId, Order.PENDING_PAYMENT);
    }

    private int available() {
        return eventRepository.findById(eventId).orElseThrow().getAvailableTickets();
    }

    private static void awaitTrue(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as("timed out waiting for: " + what).isTrue();
            Thread.sleep(100);
        }
    }
}
