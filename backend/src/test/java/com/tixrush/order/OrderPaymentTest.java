package com.tixrush.order;

import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.user.Role;
import com.tixrush.user.User;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Paying for a held order: only once, only your own, only in time. Database only (rolled back). */
@SpringBootTest
@Transactional
class OrderPaymentTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    private Long eventId;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        eventId = eventRepository.save(new Event("Payment Test", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 10)).getId();
        ownerId = userRepository.save(new User("owner-" + UUID.randomUUID() + "@test.local", "x", Role.USER)).getId();
    }

    @Test
    void payOnce_thenSecondPayIsRejected() {
        UUID reservation = pendingOrder(Instant.now().plus(10, ChronoUnit.MINUTES));

        assertThat(orderService.pay(reservation, ownerId).status()).isEqualTo(Order.PAID);
        assertThatThrownBy(() -> orderService.pay(reservation, ownerId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("already paid");
    }

    @Test
    void someoneElsesOrder_isNotFound() {
        UUID reservation = pendingOrder(Instant.now().plus(10, ChronoUnit.MINUTES));
        Long strangerId = userRepository.save(new User("stranger-" + UUID.randomUUID() + "@test.local", "x", Role.USER)).getId();

        assertThatThrownBy(() -> orderService.pay(reservation, strangerId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void afterTheHold_payingIsTooLate() {
        UUID reservation = pendingOrder(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> orderService.pay(reservation, ownerId))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Too late");
    }

    @Test
    void sameMessageTwice_createsOneOrder() {
        UUID reservation = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES);

        assertThat(orderRepository.insertPendingIfAbsent(reservation, eventId, ownerId, "queue", expiresAt, Instant.now())).isEqualTo(1);
        assertThat(orderRepository.insertPendingIfAbsent(reservation, eventId, ownerId, "queue", expiresAt, Instant.now())).isZero();
        assertThat(orderRepository.countByEventId(eventId)).isEqualTo(1);
    }

    private UUID pendingOrder(Instant expiresAt) {
        UUID reservation = UUID.randomUUID();
        orderRepository.insertPendingIfAbsent(reservation, eventId, ownerId, "queue", expiresAt, Instant.now());
        return reservation;
    }
}
