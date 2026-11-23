package com.tixrush.order;

import com.tixrush.common.TooBusyException;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.event.SoldOutException;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Fires TOTAL_BUYERS threads at an event with only TICKETS tickets, all at the same moment,
 * against the REAL database, then checks whether any tickets were oversold.
 *
 * No @Transactional here: the purchases run on other threads, and each must really commit,
 * exactly like real concurrent requests. So we clean up by hand in @AfterEach.
 */
@SpringBootTest
class PurchaseConcurrencyTest {

    private static final int TICKETS = 50;
    private static final int TOTAL_BUYERS = 200;
    private static final int THREADS = 32;

    @Autowired PurchaseService purchaseService;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;

    private Long eventId;
    private Long userId;

    record Outcome(int bought, int soldOut, int tooBusy, int otherErrors) {}

    @BeforeEach
    void createEvent() {
        Instant now = Instant.now();
        Event event = eventRepository.save(new Event("Concurrency Test", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), TICKETS));
        eventId = event.getId();
        userId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId(); // any real user works
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteById(eventId); // orders go too: ON DELETE CASCADE
    }

    @Test
    void pessimistic_sellsExactlyAllTickets_neverMore() throws Exception {
        Outcome outcome = rush("pessimistic");

        assertThat(outcome.bought()).isEqualTo(TICKETS);
        assertThat(outcome.soldOut()).isEqualTo(TOTAL_BUYERS - TICKETS);
        assertThat(orderRepository.countByEventId(eventId)).isEqualTo(TICKETS);
        assertThat(availableTickets()).isZero();
    }

    @Test
    void optimistic_neverOversells() throws Exception {
        Outcome outcome = rush("optimistic");

        long orders = orderRepository.countByEventId(eventId);
        // The invariant that matters: every order is backed by a ticket taken from the counter.
        assertThat(orders).isEqualTo(outcome.bought());
        assertThat(orders).isEqualTo(TICKETS - availableTickets());
        assertThat(orders).isLessThanOrEqualTo(TICKETS);
        assertThat(outcome.otherErrors()).isZero();
    }

    @Test
    void naive_demonstratesTheRace() throws Exception {
        Outcome outcome = rush("naive");

        long orders = orderRepository.countByEventId(eventId);
        long oversold = orders - (TICKETS - availableTickets());
        // No assertion on purpose: a race isn't guaranteed to happen on every run.
        System.out.printf("%n[NAIVE] %d tickets, %d buyers -> %d orders created, %d left in stock, OVERSOLD = %d%n%n",
                TICKETS, TOTAL_BUYERS, orders, availableTickets(), oversold);
        assertThat(outcome.otherErrors()).isZero();
    }

    /** Starts all buyers, releases them at the same instant with a latch, and tallies results. */
    private Outcome rush(String strategy) throws InterruptedException {
        AtomicInteger bought = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger tooBusy = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        CountDownLatch startGun = new CountDownLatch(1);       // everyone waits on this
        CountDownLatch finished = new CountDownLatch(TOTAL_BUYERS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < TOTAL_BUYERS; i++) {
            pool.submit(() -> {
                try {
                    startGun.await();
                    purchaseService.purchase(strategy, eventId, userId);
                    bought.incrementAndGet();
                } catch (SoldOutException e) {
                    soldOut.incrementAndGet();
                } catch (TooBusyException e) {
                    tooBusy.incrementAndGet();
                } catch (Exception e) {
                    other.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    finished.countDown();
                }
            });
        }

        startGun.countDown();                                   // GO!
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Outcome outcome = new Outcome(bought.get(), soldOut.get(), tooBusy.get(), other.get());
        System.out.printf("[%s] %s%n", strategy.toUpperCase(), outcome);
        return outcome;
    }

    private int availableTickets() {
        return eventRepository.findById(eventId).orElseThrow().getAvailableTickets();
    }
}
