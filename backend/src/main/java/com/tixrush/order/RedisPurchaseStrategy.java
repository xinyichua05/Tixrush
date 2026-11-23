package com.tixrush.order;

import com.tixrush.event.SoldOutException;
import org.springframework.stereotype.Component;

/**
 * VERSION 4 (part 1): REDIS ATOMIC COUNTER. Like a bank's number-ticket machine.
 *
 *   1. Ask Redis for a ticket (one atomic Lua script, no DB, no lock)
 *   2. Redis said yes -> INSERT the order into Postgres
 *   3. If the INSERT fails -> give the ticket back to Redis ("compensation")
 *
 * The hot "events" row is never updated during the sale. Each buyer only INSERTs
 * their own new order row, and inserts of different rows don't block each other.
 *
 * Kept as-is for comparison. QueuePurchaseStrategy ("queue", v4 complete) replaces step 2
 * with "send a message to RabbitMQ", and its background worker also updates the DB stock.
 *
 * Known gap of THIS version: during the sale the DB's available_tickets isn't updated,
 * so Redis is the source of truth for stock. If the Redis key were lost, it would reload
 * the (too high) DB value.
 */
@Component
public class RedisPurchaseStrategy implements PurchaseStrategy {

    private final RedisStockService stock;
    private final OrderRepository orderRepository;

    public RedisPurchaseStrategy(RedisStockService stock, OrderRepository orderRepository) {
        this.stock = stock;
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "redis";
    }

    // Not @Transactional: save() runs in its own short transaction. No DB lock is held.
    @Override
    public OrderResponse purchase(Long eventId, Long userId) {
        if (!stock.tryReserve(eventId)) {
            throw new SoldOutException(eventId);
        }
        try {
            Order order = orderRepository.save(new Order(eventId, userId, name()));
            return OrderResponse.from(order);
        } catch (RuntimeException e) {
            stock.release(eventId); // don't lose the ticket if the DB write failed
            throw e;
        }
    }
}
