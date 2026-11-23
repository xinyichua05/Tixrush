package com.tixrush.order.messaging;

import com.tixrush.event.EventRepository;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.order.OrderRepository;
import com.tixrush.seat.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Background worker #1: takes "please create this order" messages off the queue and writes them
 * to Postgres. The buyer already got their 202 answer and isn't waiting for any of this.
 *
 * concurrency = "10-20": 10 to 20 threads take messages in parallel (most of their time is spent
 * waiting for RabbitMQ's confirm over the network, not using the DB). This also caps how many DB
 * connections the workers use, no matter how big the rush is. The queue absorbs the spike and
 * the workers drain it at a steady pace (that's "load levelling").
 *
 * If this method throws, Spring retries it (3 attempts, see application.yml). Then the message
 * goes to tixrush.orders.create.dlq instead of being lost or retried forever.
 *
 * ORDER MATTERS (lesson learned): "stock - 1" LOCKS the hot events row until the transaction
 * commits. The first version sent the hold message INSIDE the transaction, so every worker kept
 * the row locked during a ~100-200 ms round trip to RabbitMQ (Tokyo), and the 5 workers ended up
 * queueing behind each other (~6 orders/s). Now the slow network call happens BEFORE the
 * transaction, and the lock is held for only a few milliseconds.
 */
@Component
public class OrderCreateListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateListener.class);

    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final SeatRepository seatRepository;
    private final OrderPublisher publisher;
    private final TransactionTemplate tx;

    public OrderCreateListener(OrderRepository orderRepository, EventRepository eventRepository,
                               TicketTypeRepository ticketTypeRepository, SeatRepository seatRepository,
                               OrderPublisher publisher, TransactionTemplate tx) {
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.seatRepository = seatRepository;
        this.publisher = publisher;
        this.tx = tx;
    }

    @RabbitListener(queues = OrderQueues.CREATE, concurrency = "10-20")
    public void onOrderRequested(OrderRequested message) {
        // 1. Start the payment timer (slow: network round trip). No DB lock is held yet.
        //    If the DB step below fails, RabbitMQ redelivers and we send a 2nd timer:
        //    harmless, because expiring is "only if still PENDING_PAYMENT".
        publisher.sendToHoldQueue(message);

        // 2. Short transaction: insert the order + stock - 1, then commit right away.
        tx.executeWithoutResult(status -> {
            if (message.ticketTypeId() == null) {
                int inserted = orderRepository.insertPendingIfAbsent(message.reservationId(), message.eventId(),
                        message.userId(), message.strategy(), message.expiresAt(), message.requestedAt());
                if (inserted == 1) { // 0 = duplicate delivery, or an order from a stopped practice run
                    // Keep the DB stock in step with Redis (this was the gap in v4 part 1).
                    eventRepository.decrementAvailable(message.eventId());
                }
                return;
            }
            // Ticket types: N tickets of one type. The insert locks the event row first (for no key update),
            // then we update the event, then the ticket type. The expiry worker uses the same order
            // (event, then type), so the two can never deadlock waiting for each other.
            int qty = message.tickets();
            int inserted = orderRepository.insertPendingTicketsIfAbsent(message.reservationId(), message.eventId(),
                    message.userId(), message.strategy(), message.expiresAt(), message.requestedAt(),
                    message.ticketTypeId(), qty);
            if (inserted == 1) {
                eventRepository.decrementAvailableBy(message.eventId(), qty);
                ticketTypeRepository.decrementAvailable(message.ticketTypeId(), qty);
                if (message.hasSeats()) { // ...and finally the seats (same lock order everywhere: event, type, seats)
                    int taken = seatRepository.assign(message.reservationId(), message.ticketTypeId(), message.seatIds());
                    if (taken != message.seatIds().size()) {
                        log.warn("Order {}: only {} of {} seats were free in the DB", message.reservationId(), taken,
                                message.seatIds().size());
                    }
                }
            }
        });
    }
}
