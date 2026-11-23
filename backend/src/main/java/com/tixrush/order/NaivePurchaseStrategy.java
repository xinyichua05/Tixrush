package com.tixrush.order;

import com.tixrush.common.NotFoundException;
import com.tixrush.event.EventRepository;
import com.tixrush.event.SoldOutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * VERSION 1: NAIVE. DELIBERATELY BROKEN. It oversells under load.
 *
 * Two buyers arriving at the same moment:
 *
 *   Buyer A: read stock -> 10        Buyer B: read stock -> 10
 *   Buyer A: 10 > 0, OK              Buyer B: 10 > 0, OK
 *   Buyer A: write stock = 9         Buyer B: write stock = 9   <- overwrites A's write!
 *   Buyer A: insert order            Buyer B: insert order
 *
 * Result: 2 orders created, but stock only went down by 1. This is a "lost update".
 * With hundreds of buyers, we end up with far more orders than tickets.
 */
@Component
public class NaivePurchaseStrategy implements PurchaseStrategy {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;

    public NaivePurchaseStrategy(EventRepository eventRepository, OrderRepository orderRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public String name() {
        return "naive";
    }

    @Override
    @Transactional
    public OrderResponse purchase(Long eventId, Long userId) {
        // 1. READ
        int available = eventRepository.findAvailableTickets(eventId)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
        // 2. CHECK
        if (available <= 0) {
            throw new SoldOutException(eventId);
        }
        // 3. WRITE: but "available" may already be stale here
        eventRepository.setAvailableTickets(eventId, available - 1);

        Order order = orderRepository.save(new Order(eventId, userId, name()));
        return OrderResponse.from(order);
    }
}
