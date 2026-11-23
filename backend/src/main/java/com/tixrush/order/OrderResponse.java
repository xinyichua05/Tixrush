package com.tixrush.order;

import com.tixrush.seat.SeatLabel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON returned for an order.
 *
 * v1-v3 / v4 part 1: orderId is set, status CONFIRMED, reservationId/expiresAt null.
 * v4 queue: the first reply has orderId = null and status QUEUED (the order isn't in the DB yet),
 *           plus the reservationId to track it and the payment deadline (expiresAt).
 * Ticket types: ticketTypeId + quantity (1-4) + unitPrice (null in the first QUEUED reply,
 *           because the price is read from the DB when the worker creates the order).
 * Numbered seats: seats = [{row: "F", number: 12}, ...] (empty for everything else).
 * payWith: "STRIPE" = real payment on Stripe's page, "TEST" = the instant test button.
 * refunded: a payment arrived after the order could no longer be paid, so it was given back.
 */
public record OrderResponse(Long orderId, UUID reservationId, Long eventId, String status, String strategy,
                            Instant expiresAt, Long ticketTypeId, int quantity, BigDecimal unitPrice,
                            List<SeatLabel> seats, String payWith, boolean refunded) {

    public static final String QUEUED = "QUEUED";

    /** An order without numbered seats (or when the seats aren't needed). */
    public static OrderResponse from(Order order) {
        return from(order, List.of());
    }

    public static OrderResponse from(Order order, List<SeatLabel> seats) {
        return new OrderResponse(order.getId(), order.getReservationId(), order.getEventId(), order.getStatus(),
                order.getStrategy(), order.getExpiresAt(), order.getTicketTypeId(), order.getQuantity(),
                order.getUnitPrice(), seats, "TEST", false);
    }

    /** The same order, saying how it is paid for (see PaymentService.payWith). */
    public OrderResponse withPayment(String payWith, boolean refunded) {
        return new OrderResponse(orderId, reservationId, eventId, status, strategy, expiresAt, ticketTypeId, quantity,
                unitPrice, seats, payWith, refunded);
    }

    public static OrderResponse queued(UUID reservationId, Long eventId, String strategy, Instant expiresAt) {
        return queued(reservationId, eventId, strategy, expiresAt, null, 1, List.of());
    }

    public static OrderResponse queued(UUID reservationId, Long eventId, String strategy, Instant expiresAt,
                                       Long ticketTypeId, int quantity, List<SeatLabel> seats) {
        return new OrderResponse(null, reservationId, eventId, QUEUED, strategy, expiresAt, ticketTypeId, quantity, null, seats,
                "TEST", false);
    }
}
