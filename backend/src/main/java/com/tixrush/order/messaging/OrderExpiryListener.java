package com.tixrush.order.messaging;

import com.tixrush.order.OrderRepository;
import com.tixrush.order.TicketRelease;
import com.tixrush.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background worker #2: the payment hold is over. If the order is STILL unpaid, expire it
 * and put the ticket back on sale, in both Postgres and Redis (TicketRelease does that part).
 *
 * "Only if still unpaid" is one conditional UPDATE (expireIfPending). If the buyer paid, or
 * cancelled, one millisecond earlier, that UPDATE matches 0 rows and we do nothing.
 */
@Component
public class OrderExpiryListener {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryListener.class);

    private final OrderRepository orderRepository;
    private final TicketRelease release;
    private final PaymentService paymentService;

    public OrderExpiryListener(OrderRepository orderRepository, TicketRelease release, PaymentService paymentService) {
        this.orderRepository = orderRepository;
        this.release = release;
        this.paymentService = paymentService;
    }

    @RabbitListener(queues = OrderQueues.EXPIRED, concurrency = "2-4")
    @Transactional
    public void onHoldExpired(OrderRequested message) {
        if (orderRepository.expireIfPending(message.reservationId()) == 0) {
            return; // paid in time, cancelled, already expired, or deleted by an admin reset
        }
        release.giveBack(message.eventId(), message.ticketTypeId(), message.userId(), message.reservationId(),
                message.tickets(), message.hasSeats() ? message.seatIds() : null);
        paymentService.closeOpenAfterCommit(message.reservationId()); // close its Stripe page, if one is open
        log.debug("Order {} expired unpaid, ticket for event {} is back on sale",
                message.reservationId(), message.eventId());
    }
}
