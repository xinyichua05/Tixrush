package com.tixrush.order;

import com.tixrush.common.NotFoundException;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ONE try of the optimistic purchase, in its own transaction.
 *
 * Why a separate class? @Transactional works through a Spring "proxy" that wraps the bean.
 * If OptimisticPurchaseStrategy called a @Transactional method on ITSELF, the call would
 * skip the proxy and no new transaction would start. Every retry needs a FRESH transaction
 * (to re-read the latest version), so the attempt lives in its own bean.
 */
@Component
class OptimisticPurchaseAttempt {

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;

    OptimisticPurchaseAttempt(EventRepository eventRepository, OrderRepository orderRepository) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse attempt(Long eventId, Long userId, String strategyName) {
        // Normal read, NO lock. Hibernate remembers the version it saw, e.g. 41.
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
        event.reserveOne();

        // Force the UPDATE now:  UPDATE events SET available_tickets=?, version=42 WHERE id=? AND version=41
        // If another buyer already moved it to 42, zero rows match and this throws
        // OptimisticLockingFailureException. The transaction rolls back and nothing is saved.
        eventRepository.flush();

        Order order = orderRepository.save(new Order(eventId, userId, strategyName));
        return OrderResponse.from(order);
    }
}
