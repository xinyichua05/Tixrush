package com.tixrush.demo;

import com.jayway.jsonpath.JsonPath;
import com.tixrush.event.EventRepository;
import com.tixrush.event.EventService;
import com.tixrush.common.StockKeys;
import com.tixrush.seat.SeatService;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.tixrush.order.Order;
import com.tixrush.order.OrderRepository;
import com.tixrush.user.UserRepository;
import com.tixrush.common.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A tiny Live Rush against REAL Redis + RabbitMQ + Postgres: 40 bot fans, 10 tickets, 3 s holds.
 * Checks the waiting-room gate (403 without a pass), that the run ends on its own,
 * and that it never sells more seats than exist.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = "rediss?://.+")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_URL", matches = "amqps?://.+")
class RushDemoFlowTest {

    private static final int TICKETS = 10;
    /** Like a real concert, small: 5 numbered seats (1 row x 5) and 5 standing tickets. */
    private static final List<PracticeZone> ZONES = List.of(
            PracticeZone.seated("Seats", "#2563eb", 100, 1, 5),
            PracticeZone.standing("Floor", "#e11d48", 50, 5));
    private static final RushSettings TINY = new RushSettings("TEST", "Test rush", "Rush Demo Flow Test",
            40, ZONES, 20, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10),
            Duration.ofSeconds(3), 0.7, 0.9, 0.5, Duration.ofMillis(400), Duration.ofMillis(300),
            Duration.ofMillis(800), LineStyle.RANDOM_LOBBY);
    private static final RushSettings TINY_FASTEST = new RushSettings("TEST", "Test rush", "Rush Demo Flow Test",
            40, ZONES, 20, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(10),
            Duration.ofSeconds(3), 0, 0.9, 0.5, Duration.ofMillis(400), Duration.ofMillis(300),
            Duration.ofMillis(800), LineStyle.FASTEST_CLICK);

    @Autowired RushDemoService demo;
    @Autowired MockMvc mockMvc;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired EventService eventService;
    @Autowired UserRepository userRepository;
    @Autowired SeatService seatService;
    @Autowired StringRedisTemplate redis;

    private Long eventId;

    @AfterEach
    void cleanUp() throws InterruptedException {
        waitUntil(() -> !demo.isRunning(), Duration.ofSeconds(30));
        if (eventId != null) {
            eventService.deleteEvent(eventId); // zones, seats, orders and the Redis counters
        }
    }

    @Test
    void tinyRush_gatesBuyers_finishes_andNeverOversells() throws Exception {
        String token = login();
        RushDemoService.State started = demo.start(TINY, null);
        eventId = started.eventId();
        assertThat(started.phase()).isEqualTo(RushDemoService.Phase.LOBBY);

        // Not your turn (not even in line): the normal buy endpoint says 403.
        mockMvc.perform(post("/api/events/" + eventId + "/purchases?strategy=queue")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // Joining works while it runs.
        mockMvc.perform(post("/api/rush-demo/join").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(waitUntil(() -> !demo.isRunning(), Duration.ofSeconds(60))).as("run finished").isTrue();
        RushDemoService.State end = demo.state(null);

        assertThat(end.phase()).isEqualTo(RushDemoService.Phase.FINISHED);
        assertThat(end.sold() - end.returned()).isLessThanOrEqualTo(TICKETS);
        assertThat(end.sold()).isGreaterThan(0);
        assertThat(end.crowd()).doesNotContain("W"); // nobody is left waiting
        assertThat(orderRepository.sumQuantityByEventIdAndStatusNotIn(eventId, Order.RELEASED))
                .as("tickets held by orders never exceed the tickets").isLessThanOrEqualTo(TICKETS);

        // Fans bought zones, like at a real concert: every order has a zone, and no seat was sold twice.
        assertThat(end.zones()).extracting(RushDemoService.ZoneLeft::name).containsExactly("Seats", "Floor");
        assertThat(orderRepository.findByEventId(eventId)).isNotEmpty()
                .allSatisfy(o -> assertThat(o.getTicketTypeId()).isNotNull());
        Long seatsZone = end.zones().getFirst().ticketTypeId();
        assertThat(seatService.taken(eventId, seatsZone)).hasSizeLessThanOrEqualTo(5).doesNotHaveDuplicates();
    }

    @Test
    void fastestClick_tooEarlyIsRefused_thenYourNumberComesFromYourClick() throws Exception {
        Long userId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId();
        RushDemoService.State started = demo.start(TINY_FASTEST, null);
        eventId = started.eventId();
        assertThat(started.lineStyle()).isEqualTo(LineStyle.FASTEST_CLICK);
        assertThat(started.lobby()).as("nobody can queue before it opens").isZero();

        assertThatThrownBy(() -> demo.join(userId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Too early");

        Instant clickAt = started.opensAt().plusMillis(400);
        waitUntil(() -> Instant.now().isAfter(clickAt), Duration.ofSeconds(10));
        RushDemoService.Me me = demo.join(userId).me();
        assertThat(me.clickedAfterMs()).isBetween(400L, 3_000L);
        assertThat(me.number()).isGreaterThanOrEqualTo(1);
        assertThat(demo.join(userId).me().number()).as("clicking again keeps your place").isEqualTo(me.number());

        assertThat(waitUntil(() -> !demo.isRunning(), Duration.ofSeconds(60))).as("run finished").isTrue();
        assertThat(orderRepository.sumQuantityByEventIdAndStatusNotIn(eventId, Order.RELEASED)).isLessThanOrEqualTo(TICKETS);
    }

    @Test
    void stopMidSale_thenStartAgainRightAway_stillNeverOversells() throws Exception {
        RushDemoService.State first = demo.start(TINY, null);
        eventId = first.eventId();
        Instant midSale = first.opensAt().plusMillis(800);
        waitUntil(() -> Instant.now().isAfter(midSale), Duration.ofSeconds(10)); // bots are busy buying now

        RushDemoService.State stopped = demo.stop(null);
        assertThat(stopped.phase()).isEqualTo(RushDemoService.Phase.FINISHED);
        assertThat(stopped.endReason()).isEqualTo("stopped");
        assertThat(demo.isRunning()).isFalse();
        assertThat(demo.stop(null).endReason()).as("stopping twice is harmless").isEqualTo("stopped");

        // Straight into a new run: old orders still in the queue must not take its seats.
        RushDemoService.State second = demo.start(TINY, null);
        assertThat(second.zones()).as("every zone is full again")
                .extracting(RushDemoService.ZoneLeft::left).containsExactly(5, 5);
        assertThat(redis.keys(StockKeys.boughtByAnyone(eventId))).as("nobody's 4-ticket count is left over").isEmpty();
        assertThat(waitUntil(() -> !demo.isRunning(), Duration.ofSeconds(60))).as("second run finished").isTrue();
        assertThat(waitUntil(() -> orderRepository.countByEventIdAndStatus(eventId, Order.PENDING_PAYMENT) == 0,
                Duration.ofSeconds(15))).isTrue();
        assertThat(orderRepository.sumQuantityByEventIdAndStatusNotIn(eventId, Order.RELEASED))
                .as("seats held never exceed the tickets").isLessThanOrEqualTo(TICKETS);
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@tixrush.local\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }
}
