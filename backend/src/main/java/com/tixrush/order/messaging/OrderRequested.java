package com.tixrush.order.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The message sent through RabbitMQ: "user X got a ticket for event Y, please create the order".
 * It travels as JSON. The SAME message is reused for the payment hold: it sits in the hold
 * queue, and when its time is up RabbitMQ moves it to the "expired" queue.
 *
 * holdSeconds rides along so ANY worker (even one started with a different setting) puts it
 * in the right hold queue.
 *
 * ticketTypeId + quantity: set for events that sell ticket types. A message written before these
 * fields existed arrives with null for both, which means "old single pool, 1 ticket".
 * seatIds: the exact seats, for ticket types with numbered seats (null otherwise).
 */
public record OrderRequested(UUID reservationId, Long eventId, Long userId, String strategy,
                             Instant requestedAt, Instant expiresAt, long holdSeconds,
                             Long ticketTypeId, Integer quantity, List<Long> seatIds) {

    /** Old single-pool order: no ticket type, 1 ticket. */
    public OrderRequested(UUID reservationId, Long eventId, Long userId, String strategy,
                          Instant requestedAt, Instant expiresAt, long holdSeconds) {
        this(reservationId, eventId, userId, strategy, requestedAt, expiresAt, holdSeconds, null, 1, null);
    }

    /** Has numbered seats (held in Redis already; the worker writes them to the seats table). */
    public boolean hasSeats() {
        return seatIds != null && !seatIds.isEmpty();
    }

    /** How many tickets, treating a missing value (older message) as 1. Not a JSON field (no "get"). */
    public int tickets() {
        return quantity == null || quantity < 1 ? 1 : quantity;
    }
}
