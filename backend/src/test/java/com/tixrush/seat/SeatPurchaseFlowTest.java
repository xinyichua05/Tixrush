package com.tixrush.seat;

import org.springframework.context.annotation.Import;
import com.tixrush.RequiresServices;
import com.tixrush.TestcontainersConfiguration;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.event.EventRepository;
import com.tixrush.event.EventResponse;
import com.tixrush.event.EventService;
import com.tixrush.event.CreateEventRequest;
import com.tixrush.event.TicketTypeInput;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.order.Order;
import com.tixrush.order.OrderRepository;
import com.tixrush.order.OrderResponse;
import com.tixrush.order.OrderService;
import com.tixrush.order.TicketTypePurchaseService;
import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
import com.tixrush.venue.VenueRequest;
import com.tixrush.venue.VenueResponse;
import com.tixrush.venue.VenueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Numbered seats end to end, against REAL Postgres + Redis + RabbitMQ, with a 5-second payment hold:
 * pick exact seats, the race for the same seat, best available, and seats coming back on expiry.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tixrush.orders.payment-hold=5s")
@RequiresServices(rabbit = true)
class SeatPurchaseFlowTest {

    @Autowired VenueService venueService;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired TicketTypeRepository ticketTypeRepository;
    @Autowired SeatService seatService;
    @Autowired SeatRepository seatRepository;
    @Autowired TicketTypePurchaseService purchase;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired com.tixrush.order.TicketStockService ticketStock;

    private Long venueId;
    private Long eventId;
    private Long cat1;      // 5 rows (A-E) x 10 seats, RM 688
    private SeatLayout layout;
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        VenueResponse venue = venueService.create(new VenueRequest("Seat Test Hall " + UUID.randomUUID(), List.of(
                new VenueRequest.Zone("CAT 1", "SEATED", null, "#2563eb", 5, 10))));
        venueId = venue.id();
        Instant now = Instant.now();
        EventResponse event = eventService.createEvent(new CreateEventRequest("Seat Flow Test", "x",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), null, venueId,
                List.of(new TicketTypeInput(venue.zones().getFirst().id(), null, new BigDecimal("688.00"), 1))));
        eventId = event.id();
        cat1 = event.ticketTypes().getFirst().id();
        layout = seatService.layout(eventId, cat1);
    }

    @AfterEach
    void cleanUp() {
        eventService.deleteEvent(eventId); // clears the Redis stock + taken seats too
        users.forEach(u -> ticketStock.clearBought(eventId, u));
        userRepository.deleteAllById(users);
        venueService.delete(venueId);
    }

    private Long newUser() {
        Long id = userRepository.save(new User("seat-" + UUID.randomUUID() + "@test.local", "x", Role.USER)).getId();
        users.add(id);
        return id;
    }

    /** Seat id of e.g. ("C", 4). */
    private long seat(String row, int number) {
        return layout.rows().stream().filter(r -> r.label().equals(row)).findFirst().orElseThrow()
                .seats().stream().filter(s -> s.number() == number).findFirst().orElseThrow().id();
    }

    @Test
    void everySeatExists_andTheTicketTypeSellsThemAll() {
        assertThat(seatRepository.countByTicketType(cat1)).isEqualTo(50);
        assertThat(ticketTypeRepository.findById(cat1).orElseThrow().getTotalTickets()).isEqualTo(50); // not the "1" we sent
        assertThat(layout.rows()).extracting(SeatLayout.Row::label).containsExactly("A", "B", "C", "D", "E");
    }

    @Test
    void pickExactSeats_orderHoldsThem_andNobodyElseCanTakeThem() throws Exception {
        Long fan = newUser();
        OrderResponse queued = purchase.purchase(eventId, fan, cat1, 0, List.of(seat("C", 5), seat("C", 4)));
        assertThat(queued.quantity()).isEqualTo(2);
        assertThat(queued.seats()).containsExactly(new SeatLabel("C", 4), new SeatLabel("C", 5)); // in seat order
        assertThat(seatService.taken(eventId, cat1)).containsExactlyInAnyOrder(seat("C", 4), seat("C", 5));

        // Someone else clicks C5 a moment later: refused, and NOTHING of theirs is taken (not even C6).
        assertThatThrownBy(() -> purchase.purchase(eventId, newUser(), cat1, 0, List.of(seat("C", 6), seat("C", 5))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Row C, Seat 5 was just taken");
        assertThat(seatService.taken(eventId, cat1)).doesNotContain(seat("C", 6));

        // The worker writes the order and the seats to Postgres; paying keeps them.
        awaitTrue("order + seats in the DB", Duration.ofSeconds(10), () -> orderRepository.findByReservationId(queued.reservationId())
                .isPresent() && seatRepository.labelsFor(List.of(queued.reservationId())).containsKey(queued.reservationId()));
        OrderResponse paid = orderService.pay(queued.reservationId(), fan);
        assertThat(paid.status()).isEqualTo(Order.PAID);
        assertThat(paid.seats()).containsExactly(new SeatLabel("C", 4), new SeatLabel("C", 5));
        assertThat(orderService.myOrders(fan).getFirst().seats()).hasSize(2);
        assertThat(ticketTypeRepository.findById(cat1).orElseThrow().getAvailableTickets()).isEqualTo(48);
    }

    @Test
    void twentyFansClickTheSameTwoSeats_exactlyOneGetsThem() throws Exception {
        List<Long> fans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fans.add(newUser());
        }
        List<Long> sameSeats = List.of(seat("A", 5), seat("A", 6));
        List<OrderResponse> winners = new CopyOnWriteArrayList<>();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        for (Long fan : fans) {
            pool.submit(() -> {
                go.await();
                try {
                    winners.add(purchase.purchase(eventId, fan, cat1, 0, sameSeats));
                } catch (ConflictException e) {
                    refused.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown(); // everyone clicks at the same moment
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(winners).hasSize(1);
        assertThat(refused).hasValue(19);
        assertThat(seatService.taken(eventId, cat1)).containsExactlyInAnyOrderElementsOf(sameSeats);
        assertThat(ticketStock.getStock(eventId, cat1)).isEqualTo(48); // only 2 seats left the stock
    }

    @Test
    void bestAvailable_givesTheFrontRowMiddle_thenTheNextBest() {
        OrderResponse first = purchase.purchase(eventId, newUser(), cat1, 2, null);
        assertThat(first.seats()).containsExactly(new SeatLabel("A", 5), new SeatLabel("A", 6));
        OrderResponse second = purchase.purchase(eventId, newUser(), cat1, 2, null);
        assertThat(second.seats()).containsExactly(new SeatLabel("A", 3), new SeatLabel("A", 4)); // next to them, left wins a tie
    }

    @Test
    void unpaidSeatsComeBackWhenTheHoldRunsOut() throws Exception {
        Long fan = newUser();
        OrderResponse queued = purchase.purchase(eventId, fan, cat1, 0, List.of(seat("B", 1), seat("B", 2)));
        awaitTrue("order expired", Duration.ofSeconds(30), () -> orderRepository.findByReservationId(queued.reservationId())
                .map(o -> Order.EXPIRED.equals(o.getStatus())).orElse(false));
        awaitTrue("seats free again in DB and Redis", Duration.ofSeconds(10),
                () -> seatService.taken(eventId, cat1).isEmpty()
                        && seatRepository.labelsFor(List.of(queued.reservationId())).isEmpty()
                        && Integer.valueOf(50).equals(ticketStock.getStock(eventId, cat1)));
        // Free for anyone, including the same fan (their allowance came back too).
        assertThat(purchase.purchase(eventId, fan, cat1, 0, List.of(seat("B", 1), seat("B", 2), seat("B", 3), seat("B", 4))).quantity())
                .isEqualTo(4);
    }

    @Test
    void wrongChoices_areRefused() {
        Long fan = newUser();
        assertThatThrownBy(() -> purchase.purchase(eventId, fan, cat1, 0, List.of(999_999_999L)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("aren't part of");
        assertThatThrownBy(() -> purchase.purchase(eventId, fan, cat1, 0, List.of(seat("A", 1), seat("A", 1))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> purchase.purchase(eventId, fan, cat1, 0,
                List.of(seat("A", 1), seat("A", 2), seat("A", 3), seat("A", 4), seat("A", 7))))
                .isInstanceOf(BadRequestException.class);
        // 4 per person, also with seats: 3 now + 2 later = refused.
        purchase.purchase(eventId, fan, cat1, 0, List.of(seat("D", 1), seat("D", 2), seat("D", 3)));
        assertThatThrownBy(() -> purchase.purchase(eventId, fan, cat1, 0, List.of(seat("E", 1), seat("E", 2))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already have 3");
    }

    private static void awaitTrue(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() < deadline).as("timed out waiting for: " + what).isTrue();
            Thread.sleep(100);
        }
    }
}
