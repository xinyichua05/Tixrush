package com.tixrush.order;

import com.tixrush.common.NotFoundException;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * VERSION 2: PESSIMISTIC LOCK. "Assume there WILL be a fight, so queue everyone."
 *
 *   Buyer A: SELECT ... FOR UPDATE  -> gets the row lock
 *   Buyer B: SELECT ... FOR UPDATE  -> WAITS (blocked by the database)
 *   Buyer A: stock 10 -> 9, insert order, COMMIT -> lock released
 *   Buyer B: now reads 9 (fresh!), 9 -> 8, insert order, COMMIT
 *
 * Always correct. But only one buyer at a time can touch the row, so throughput is
 * limited by how fast one transaction runs, and everyone else holds a DB connection
 * while they wait.
 */
@Component
public class PessimisticPurchaseStrategy implements PurchaseStrategy {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;

    public PessimisticPurchaseStrategy(EventRepository eventRepository, OrderRepository orderRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    @Transactional // the lock lives as long as this transaction
    public OrderResponse purchase(Long eventId, Long userId) {
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
        event.reserveOne(); // throws SoldOutException at 0; the UPDATE runs at commit (dirty checking)
        Order order = orderRepository.save(new Order(eventId, userId, name()));
        return OrderResponse.from(order);
    }
}
