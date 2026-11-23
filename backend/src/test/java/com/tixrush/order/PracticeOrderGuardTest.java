package com.tixrush.order;

import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stop + restart a Practice rush: an order from the OLD run that reaches the worker after the new run
 * started must be dropped, otherwise it would take a seat in the new run. Real events are unaffected.
 * (@Transactional: everything is rolled back after each test.)
 */
@SpringBootTest
@Transactional
class PracticeOrderGuardTest {

    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;

    private Long userId;
    private Instant saleStart;

    @BeforeEach
    void setUp() {
        userId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId();
        saleStart = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    @Test
    void practiceOrderFromBeforeTheCurrentSale_isDropped() {
        Long practiceId = event(true);
        Instant expires = saleStart.plus(1, ChronoUnit.MINUTES);

        int stale = orderRepository.insertPendingIfAbsent(UUID.randomUUID(), practiceId, userId, "queue", expires,
                saleStart.minus(1, ChronoUnit.HOURS));
        int current = orderRepository.insertPendingIfAbsent(UUID.randomUUID(), practiceId, userId, "queue", expires,
                saleStart.plusSeconds(1));

        assertThat(stale).as("old run's order").isZero();
        assertThat(current).as("this run's order").isEqualTo(1);
        assertThat(orderRepository.countByEventId(practiceId)).isEqualTo(1);
    }

    @Test
    void realEvents_alwaysAcceptTheOrder() {
        Long realId = event(false);
        int inserted = orderRepository.insertPendingIfAbsent(UUID.randomUUID(), realId, userId, "queue",
                saleStart.plus(10, ChronoUnit.MINUTES), saleStart.minus(1, ChronoUnit.HOURS));
        assertThat(inserted).isEqualTo(1);
    }

    private Long event(boolean practice) {
        Event e = new Event("Guard Test " + UUID.randomUUID(), "Test Hall", saleStart.plus(30, ChronoUnit.DAYS),
                saleStart, 10);
        if (practice) {
            e.markPractice();
        }
        return eventRepository.saveAndFlush(e).getId();
    }
}
