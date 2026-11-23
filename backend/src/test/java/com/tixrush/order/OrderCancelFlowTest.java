package com.tixrush.order;

import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.CreateEventRequest;
import com.tixrush.event.EventRepository;
import com.tixrush.event.EventResponse;
import com.tixrush.event.EventService;
import com.tixrush.event.TicketTypeInput;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.seat.SeatLayout;
import com.tixrush.seat.SeatRepository;
import com.tixrush.seat.SeatService;
import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
import com.tixrush.venue.VenueRequest;
import com.tixrush.venue.VenueResponse;
import com.tixrush.venue.VenueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Cancel order" on the payment step, against REAL Postgres + Redis + RabbitMQ, with a 6-second hold:
 * the tickets go back on sale at once (DB, Redis, seats, the 4-per-person allowance), and the
 * payment timer firing later changes nothing.
 */
@SpringBootTest(properties = "tixrush.orders.payment-hold=6s")
@EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = "rediss?://.+")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_URL", matches = "amqps?://.+")
class OrderCancelFlowTest {

    @Autowired VenueService venueService;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired TicketTypeRepository ticketTypeRepository;
    @Autowired SeatService seatService;
    @Autowired SeatRepository seatRepository;
    @Autowired TicketTypePurchaseService purchase;
    @Autowired TicketStockService ticketStock;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;

    private Long venueId;
    private Long eventId;
    private Long floor;  // standing, 100
    private Long cat1;   // seated, 5 x 10 = 50
    private SeatLayout layout;
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        VenueResponse venue = venueService.create(new VenueRequest("Cancel Test Hall " + UUID.randomUUID(), List.of(
                new VenueRequest.Zone("Floor", "STANDING", 100, "#e11d48"),
                new VenueRequest.Zone("CAT 1", "SEATED", null, "#2563eb", 5, 10))));
        venueId = venue.id();
        Instant now = Instant.now();
        EventResponse event = eventService.createEvent(new CreateEventRequest("Cancel Flow Test", "x",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), null, venueId,
                venue.zones().stream().map(z -> new TicketTypeInput(z.id(), null, new BigDecimal("100.00"), z.capacity())).toList()));
        eventId = event.id();
        floor = event.ticketTypes().get(0).id();
        cat1 = event.ticketTypes().get(1).id();
        layout = seatService.layout(eventId, cat1);
    }

    @AfterEach
    void cleanUp() {
        eventService.deleteEvent(eventId);
        users.forEach(u -> ticketStock.clearBought(eventId, u));
        userRepository.deleteAllById(users);
        venueService.delete(venueId);
    }

    private Long newUser() {
        Long id = userRepository.save(new User("cancel-" + UUID.randomUUID() + "@test.local", "x", Role.USER)).getId();
        users.add(id);
        return id;
    }

    private long seat(String row, int number) {
        return layout.rows().stream().filter(r -> r.label().equals(row)).findFirst().orElseThrow()
                .seats().stream().filter(s -> s.number() == number).findFirst().orElseThrow().id();
    }

    private void awaitPending(UUID reservationId) throws InterruptedException {
        awaitTrue("order created", Duration.ofSeconds(10), () -> orderRepository.findByReservationId(reservationId)
                .map(o -> Order.PENDING_PAYMENT.equals(o.getStatus())).orElse(false));
    }

    @Test
    void cancelSeats_theyAreFreeAtOnce_andTheTimerLaterChangesNothing() throws Exception {
        Long fan = newUser();
        OrderResponse queued = purchase.purchase(eventId, fan, cat1, 0, List.of(seat("A", 5), seat("A", 6)));
        awaitPending(queued.reservationId());
        assertThat(ticketTypeRepository.findById(cat1).orElseThrow().getAvailableTickets()).isEqualTo(48);

        OrderResponse cancelled = orderService.cancel(queued.reservationId(), fan);

        assertThat(cancelled.status()).isEqualTo(Order.CANCELLED);
        assertThat(cancelled.seats()).isEmpty();
        assertThat(seatService.taken(eventId, cat1)).isEmpty();                       // Redis: seats free
        assertThat(seatRepository.seatIdsOf(queued.reservationId())).isEmpty();       // DB: seats free
        assertThat(ticketStock.getStock(eventId, cat1)).isEqualTo(50);                // Redis stock
        assertThat(ticketTypeRepository.findById(cat1).orElseThrow().getAvailableTickets()).isEqualTo(50);
        assertThat(eventRepository.findById(eventId).orElseThrow().getAvailableTickets()).isEqualTo(150);

        // The 6 s payment timer still fires. It must NOT give the 2 seats back a second time.
        Thread.sleep(9_000);
        assertThat(orderRepository.findByReservationId(queued.reservationId()).orElseThrow().getStatus()).isEqualTo(Order.CANCELLED);
        assertThat(ticketStock.getStock(eventId, cat1)).isEqualTo(50);
        assertThat(ticketTypeRepository.findById(cat1).orElseThrow().getAvailableTickets()).isEqualTo(50);
        assertThat(eventRepository.findById(eventId).orElseThrow().getAvailableTickets()).isEqualTo(150);
    }

    @Test
    void cancelStanding_givesTheAllowanceBack_andCannotBeUndone() throws Exception {
        Long fan = newUser();
        OrderResponse queued = purchase.purchase(eventId, fan, floor, 4, null);
        awaitPending(queued.reservationId());

        orderService.cancel(queued.reservationId(), fan);
        assertThat(ticketStock.getStock(eventId, floor)).isEqualTo(100);

        // Cancelled is final: no paying, no cancelling twice.
        assertThatThrownBy(() -> orderService.pay(queued.reservationId(), fan)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> orderService.cancel(queued.reservationId(), fan))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already cancelled");

        // Their 4-per-person allowance came back: 4 more is fine.
        assertThat(purchase.purchase(eventId, fan, floor, 4, null).quantity()).isEqualTo(4);
    }

    @Test
    void paidOrders_andOtherPeoplesOrders_cannotBeCancelled() throws Exception {
        Long fan = newUser();
        OrderResponse queued = purchase.purchase(eventId, fan, floor, 1, null);
        awaitPending(queued.reservationId());

        assertThatThrownBy(() -> orderService.cancel(queued.reservationId(), newUser()))
                .isInstanceOf(NotFoundException.class); // someone else's order: as if it doesn't exist

        orderService.pay(queued.reservationId(), fan);
        assertThatThrownBy(() -> orderService.cancel(queued.reservationId(), fan))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already paid");
        assertThat(ticketStock.getStock(eventId, floor)).isEqualTo(99); // still sold
    }

    private static void awaitTrue(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as("timed out waiting for: " + what).isTrue();
            Thread.sleep(100);
        }
    }
}
