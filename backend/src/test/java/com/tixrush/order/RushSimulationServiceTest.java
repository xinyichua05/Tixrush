package com.tixrush.order;

import org.springframework.context.annotation.Import;
import com.tixrush.TestcontainersConfiguration;

import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The server-side rush used by the visualizer page: a pessimistic rush must sell exactly every seat. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tixrush.dev-tools.enabled=true")
class RushSimulationServiceTest {

    private static final int TICKETS = 30;
    private static final int BUYERS = 150;

    @Autowired RushSimulationService rushService;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;

    private Long eventId;

    @BeforeEach
    void createEvent() {
        Instant now = Instant.now();
        eventId = eventRepository.save(new Event("Rush Sim Test", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), TICKETS)).getId();
    }

    @AfterEach
    void cleanUp() {
        eventRepository.deleteById(eventId); // orders go too: ON DELETE CASCADE
    }

    @Test
    void pessimisticRush_sellsExactlyAllSeats() throws Exception {
        assertThat(rushService.start(eventId, "pessimistic", BUYERS, 20)).isTrue();
        assertThat(rushService.start(eventId, "pessimistic", BUYERS, 20))
                .as("a second rush can't start while one is running").isFalse();

        RushSimulationService.RushProgress progress = waitUntilFinished();

        assertThat(progress.done()).isEqualTo(BUYERS);
        assertThat(progress.sold()).isEqualTo(TICKETS);
        assertThat(progress.soldOut()).isEqualTo(BUYERS - TICKETS);
        assertThat(progress.errors()).isZero();
        assertThat(progress.p95Ms()).isNotNull();
        assertThat(orderRepository.countByEventId(eventId)).isEqualTo(TICKETS);
        assertThat(eventRepository.findById(eventId).orElseThrow().getAvailableTickets()).isZero();
    }

    private RushSimulationService.RushProgress waitUntilFinished() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (rushService.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(rushService.isRunning()).as("rush finished within 60 s").isFalse();
        return rushService.progress();
    }
}
