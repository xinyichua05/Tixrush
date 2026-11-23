package com.tixrush.order;

import com.tixrush.common.TooBusyException;
import com.tixrush.event.SoldOutException;
import com.tixrush.order.messaging.OrderPublisher;
import com.tixrush.order.messaging.OrderRequested;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * VERSION 4 (complete): REDIS + QUEUE. Like a restaurant that hands you a buzzer.
 *
 *   1. Redis: "any ticket left? take one"   (atomic Lua, ~1 network trip)
 *   2. RabbitMQ: "please create this order" (wait for the broker's confirm, ~1 network trip)
 *   3. Answer 202 "you got it, here's your reservation id" right away
 *
 * The buyer never waits for Postgres. A background worker (OrderCreateListener) inserts the
 * order a moment later, and the order then waits for payment (OrderExpiryListener).
 */
@Component
public class QueuePurchaseStrategy implements PurchaseStrategy {

    private final RedisStockService stock;
    private final OrderPublisher publisher;
    private final PaymentHoldPolicy holdPolicy;

    public QueuePurchaseStrategy(RedisStockService stock, OrderPublisher publisher, PaymentHoldPolicy holdPolicy) {
        this.stock = stock;
        this.publisher = publisher;
        this.holdPolicy = holdPolicy;
    }

    @Override
    public String name() {
        return "queue";
    }

    @Override
    public OrderResponse purchase(Long eventId, Long userId) {
        if (!stock.tryReserve(eventId)) {
            throw new SoldOutException(eventId);
        }

        UUID reservationId = UUID.randomUUID(); // made HERE, so the buyer can track the order right away
        Instant now = Instant.now();
        Duration hold = holdPolicy.holdFor(eventId);
        Instant expiresAt = now.plus(hold);
        OrderRequested message = new OrderRequested(reservationId, eventId, userId, name(), now, expiresAt,
                hold.toSeconds());
        try {
            publisher.sendToCreateQueue(message);
        } catch (RuntimeException e) {
            stock.release(eventId); // RabbitMQ didn't take it: give the ticket back
            throw new TooBusyException("We're very busy right now. Please try again in a moment.");
        }
        return OrderResponse.queued(reservationId, eventId, name(), expiresAt);
    }
}
