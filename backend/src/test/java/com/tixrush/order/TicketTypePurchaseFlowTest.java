package com.tixrush.order;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.CreateEventRequest;
import com.tixrush.event.EventRepository;
import com.tixrush.event.EventResponse;
import com.tixrush.event.EventService;
import com.tixrush.event.SoldOutException;
import com.tixrush.event.TicketTypeInput;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
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
 * Buying ticket types ("2 x CAT 1") through the production path, against REAL Redis + RabbitMQ +
 * Postgres, with a 5-second payment hold so expiry can be watched.
 */
@SpringBootTest(properties = "tixrush.orders.payment-hold=5s")
@EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = "rediss?://.+")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_URL", matches = "amqps?://.+")
class TicketTypePurchaseFlowTest {

    @Autowired TicketTypePurchaseService purchase;
    @Autowired PurchaseService purchaseService;
    @Autowired OrderService orderService;
    @Autowired EventService eventService;
    @Autowired EventRepository eventRepository;
    @Autowired TicketTypeRepository ticketTypeRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired TicketStockService ticketStock;
    @Autowired RedisStockService redisStock;

    private Long eventId;
    private Long cat1;   // 10 tickets, RM 688
    private Long cat2;   // 3 tickets, RM 388
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        EventResponse event = eventService.createEvent(new CreateEventRequest("Ticket Type Flow Test", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), null, null, List.of(
                new TicketTypeInput(null, "CAT 1", new BigDecimal("688.00"), 10),
                new TicketTypeInput(null, "CAT 2", new BigDecimal("388.00"), 3))));
        eventId = event.id();
        cat1 = event.ticketTypes().get(0).id();
        cat2 = event.ticketTypes().get(1).id();
    }

    @AfterEach
    void cleanUp() {
        ticketStock.clear(eventId, List.of(cat1, cat2));
        users.forEach(u -> ticketStock.clearBought(eventId, u));
        redisStock.clear(eventId);
        eventRepository.deleteById(eventId); // ticket types + orders go too: ON DELETE CASCADE
        userRepository.deleteAllById(users);
    }

    private Long newUser() {
        Long id = userRepository.save(new User("buyer-" + UUID.randomUUID() + "@test.local", "x", Role.USER)).getId();
        users.add(id);
        return id;
    }

    @Test
    void buyTwo_orderHoldsTwo_andBothStockNumbersDropByTwo() throws Exception {
        Long buyer = newUser();
        OrderResponse queued = purchase.purchase(eventId, buyer, cat1, 2);
        assertThat(queued.status()).isEqualTo(OrderResponse.QUEUED);
        assertThat(queued.ticketTypeId()).isEqualTo(cat1);
        assertThat(queued.quantity()).isEqualTo(2);
        assertThat(ticketStock.getStock(eventId, cat1)).isEqualTo(8); // Redis answered right away

        // The worker writes the order: 2 tickets at the price of that moment, and the DB stock follows.
        awaitTrue("order created", Duration.ofSeconds(10),
                () -> orderRepository.findByReservationId(queued.reservationId()).isPresent());
        Order order = orderRepository.findByReservationId(queued.reservationId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.PENDING_PAYMENT);
        assertThat(order.getQuantity()).isEqualTo(2);
        assertThat(order.getTicketTypeId()).isEqualTo(cat1);
        assertThat(order.getUnitPrice()).isEqualByComparingTo("688.00");
        assertThat(typeAvailable(cat1)).isEqualTo(8);
        assertThat(typeAvailable(cat2)).isEqualTo(3);
        assertThat(eventAvailable()).isEqualTo(11);

        assertThat(orderService.pay(queued.reservationId(), buyer).status()).isEqualTo(Order.PAID);
    }

    @Test
    void cantTakeMoreThanIsLeft_orMoreThanFourPerPerson() {
        // CAT 2 has 3 tickets: asking 4 takes nothing.
        assertThatThrownBy(() -> purchase.purchase(eventId, newUser(), cat2, 4))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only 3 tickets");
        assertThat(ticketStock.getStock(eventId, cat2)).isEqualTo(3);

        // 3 + 1 = this person's 4 (over two types)... the 5th is refused.
        Long fan = newUser();
        purchase.purchase(eventId, fan, cat2, 3);
        purchase.purchase(eventId, fan, cat1, 1);
        assertThatThrownBy(() -> purchase.purchase(eventId, fan, cat1, 1))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have 4");

        // CAT 2 is now gone for everybody.
        assertThatThrownBy(() -> purchase.purchase(eventId, newUser(), cat2, 1))
                .isInstanceOf(SoldOutException.class);
        // Quantity outside 1-4.
        assertThatThrownBy(() -> purchase.purchase(eventId, newUser(), cat1, 5)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> purchase.purchase(eventId, newUser(), cat1, 0)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void unpaidOrderExpires_ticketsAndThePersonsAllowanceComeBack() throws Exception {
        Long buyer = newUser();
        OrderResponse queued = purchase.purchase(eventId, buyer, cat1, 4); // all 4 of their allowance
        assertThatThrownBy(() -> purchase.purchase(eventId, buyer, cat2, 1)).isInstanceOf(ConflictException.class);

        // Not paid: after the 5 s hold it expires, and all 4 go back, in Postgres AND Redis.
        awaitTrue("order expired", Duration.ofSeconds(30), () -> orderRepository.findByReservationId(queued.reservationId())
                .map(o -> Order.EXPIRED.equals(o.getStatus())).orElse(false));
        awaitTrue("stock back to 10 in DB and Redis", Duration.ofSeconds(10),
                () -> typeAvailable(cat1) == 10 && eventAvailable() == 13
                        && Integer.valueOf(10).equals(ticketStock.getStock(eventId, cat1)));

        // ...and the buyer may buy again.
        assertThat(purchase.purchase(eventId, buyer, cat2, 1).status()).isEqualTo(OrderResponse.QUEUED);
    }

    @Test
    void wrongWaysToBuy_areRefused() {
        // A ticket type of ANOTHER event.
        Instant now = Instant.now();
        EventResponse other = eventService.createEvent(new CreateEventRequest("Other Event", "Elsewhere",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), null, null,
                List.of(new TicketTypeInput(null, "Adult", BigDecimal.TEN, 5))));
        Long otherType = other.ticketTypes().getFirst().id();
        try {
            assertThatThrownBy(() -> purchase.purchase(eventId, newUser(), otherType, 1))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            eventRepository.deleteById(other.id());
        }

        // "Just a ticket" (the old single-pool way) on an event that sells ticket types.
        assertThatThrownBy(() -> purchaseService.purchase("queue", eventId, newUser()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("choose a ticket type");
    }

    private int typeAvailable(Long typeId) {
        return ticketTypeRepository.findById(typeId).orElseThrow().getAvailableTickets();
    }

    private int eventAvailable() {
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
