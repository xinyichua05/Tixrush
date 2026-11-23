package com.tixrush.order;

import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.payment.PaymentService;
import com.tixrush.seat.SeatLabel;
import com.tixrush.seat.SeatRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Looking up, paying for and cancelling your own orders. */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;
    private final TicketRelease release;
    private final PaymentService paymentService;

    public OrderService(OrderRepository orderRepository, SeatRepository seatRepository, TicketRelease release,
                        PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.seatRepository = seatRepository;
        this.release = release;
        this.paymentService = paymentService;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> myOrders(Long userId) {
        List<Order> orders = orderRepository.findMine(userId, PageRequest.of(0, 20));
        // The seats of ALL these orders in one query (not one query per order).
        Map<UUID, List<SeatLabel>> seats = seatRepository.labelsFor(orders.stream()
                .map(Order::getReservationId).filter(Objects::nonNull).toList());
        // findMine already leaves out Practice rush orders, so every order here is a real one.
        return orders.stream().map(o -> OrderResponse.from(o, seats.getOrDefault(o.getReservationId(), List.of()))
                .withPayment(paymentService.payWith(o, false), false)).toList();
    }

    private OrderResponse withSeats(Order order) {
        if (order.getReservationId() == null) {
            return OrderResponse.from(order);
        }
        return OrderResponse.from(order, seatRepository.labelsFor(List.of(order.getReservationId()))
                        .getOrDefault(order.getReservationId(), List.of()))
                .withPayment(paymentService.payWith(order), paymentService.wasRefunded(order.getReservationId()));
    }

    /** 404 also covers "not in the DB YET": a queued order shows up a moment after the 202. */
    @Transactional(readOnly = true)
    public OrderResponse myOrder(UUID reservationId, Long userId) {
        return orderRepository.findByReservationIdAndUserId(reservationId, userId)
                .map(this::withSeats)
                .orElseThrow(() -> new NotFoundException(
                        "We couldn't find that order. If you just bought it, give it a few seconds."));
    }

    /**
     * Pretend payment: PENDING_PAYMENT -> PAID, but only before expires_at.
     * The conditional UPDATE is what makes "pay" and "expire" safe against each other.
     */
    @Transactional
    public OrderResponse pay(UUID reservationId, Long userId) {
        // Real orders are paid on Stripe's page once Stripe is on: this instant button must not be a shortcut.
        orderRepository.findByReservationIdAndUserId(reservationId, userId)
                .filter(o -> PaymentService.PAY_WITH_STRIPE.equals(paymentService.payWith(o)))
                .ifPresent(o -> {
                    throw new ConflictException("Please pay with Stripe.");
                });
        int updated = orderRepository.payIfPending(reservationId, userId, Instant.now());
        Order order = orderRepository.findByReservationIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that order."));
        if (updated == 0) {
            throw switch (order.getStatus()) {
                case Order.PAID -> new ConflictException("This order is already paid.");
                case Order.EXPIRED -> new ConflictException("Too late: the time to pay ran out and the seat went back on sale.");
                case Order.PENDING_PAYMENT -> new ConflictException("Too late: the time to pay has run out.");
                default -> new ConflictException("This order doesn't need payment.");
            };
        }
        return withSeats(order);
    }

    /**
     * "Cancel order" on the payment step: PENDING_PAYMENT -> CANCELLED, and the tickets go back on
     * sale right away (they don't wait for the 10-minute timer). The timer message still arrives
     * later, but its expireIfPending then matches 0 rows, so nothing is given back twice.
     */
    @Transactional
    public OrderResponse cancel(UUID reservationId, Long userId) {
        // The seat ids, read BEFORE the update (giveBack frees them, after which they're unknown).
        List<Long> seatIds = seatRepository.seatIdsOf(reservationId);
        int updated = orderRepository.cancelIfPending(reservationId, userId);
        Order order = orderRepository.findByReservationIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that order."));
        if (updated == 0) {
            throw switch (order.getStatus()) {
                case Order.PAID -> new ConflictException("This order is already paid, so it can't be cancelled here.");
                case Order.EXPIRED -> new ConflictException("Too late: the time to pay ran out and the tickets went back on sale.");
                case Order.CANCELLED -> new ConflictException("This order is already cancelled.");
                default -> new ConflictException("This order can't be cancelled.");
            };
        }
        release.giveBack(order.getEventId(), order.getTicketTypeId(), userId, reservationId,
                order.getQuantity(), seatIds);
        paymentService.closeOpenAfterCommit(reservationId); // nobody may pay on its Stripe page any more
        return OrderResponse.from(order); // CANCELLED, and no seats any more
    }
}
