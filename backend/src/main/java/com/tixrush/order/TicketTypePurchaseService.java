package com.tixrush.order;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.TooBusyException;
import com.tixrush.event.SoldOutException;
import com.tixrush.order.messaging.OrderPublisher;
import com.tixrush.order.messaging.OrderRequested;
import com.tixrush.seat.SeatLayout;
import com.tixrush.seat.SeatService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Buying 1-4 tickets of ONE ticket type ("2 x CAT 1"). The same production path as
 * QueuePurchaseStrategy (v4), only counted per ticket type:
 *
 *   1. Redis: "enough CAT 1 left, and still within your 4? take them"  (one atomic Lua step)
 *   2. RabbitMQ: "please create this order"
 *   3. Answer 202 with the reservation id right away; a worker writes the order to Postgres.
 *
 * No database work happens here, so a rush on one zone is just as fast as before.
 */
@Service
public class TicketTypePurchaseService {

    private static final String STRATEGY = "queue";

    private final TicketStockService stock;
    private final SeatService seats;
    private final OrderPublisher publisher;
    private final PaymentHoldPolicy holdPolicy;

    public TicketTypePurchaseService(TicketStockService stock, SeatService seats, OrderPublisher publisher,
                                     PaymentHoldPolicy holdPolicy) {
        this.stock = stock;
        this.seats = seats;
        this.publisher = publisher;
        this.holdPolicy = holdPolicy;
    }

    public OrderResponse purchase(Long eventId, Long userId, Long ticketTypeId, int quantity) {
        return purchase(eventId, userId, ticketTypeId, quantity, null);
    }

    /**
     * seatIds: for ticket types with numbered seats, the seats the buyer clicked (null or empty =
     * "best available": we pick the best seats side by side). Ignored for other ticket types.
     */
    public OrderResponse purchase(Long eventId, Long userId, Long ticketTypeId, int quantity, List<Long> seatIds) {
        Optional<SeatLayout> seated = seats.layoutIfSeated(eventId, ticketTypeId);
        if (seated.isPresent()) {
            return purchaseSeats(seated.get(), userId, seatIds, quantity);
        }
        if (quantity < 1 || quantity > TicketStockService.MAX_PER_PERSON) {
            throw new BadRequestException("You can buy 1 to " + TicketStockService.MAX_PER_PERSON + " tickets at a time.");
        }

        TicketStockService.Result result = stock.tryReserve(eventId, ticketTypeId, userId, quantity);
        switch (result.outcome()) {
            case OK -> { }
            case OVER_LIMIT -> throw new ConflictException("You can buy up to " + TicketStockService.MAX_PER_PERSON
                    + " tickets for this event, and you already have " + result.number() + ".");
            case NOT_ENOUGH -> {
                if (result.number() == 0) {
                    throw new SoldOutException(eventId, "Sorry, these tickets are sold out. Try another ticket type.");
                }
                throw new ConflictException("Only " + result.number() + (result.number() == 1 ? " ticket" : " tickets")
                        + " of this type left. Please choose fewer.");
            }
        }

        UUID reservationId = UUID.randomUUID();
        Instant now = Instant.now();
        Duration hold = holdPolicy.holdFor(eventId);
        Instant expiresAt = now.plus(hold);
        OrderRequested message = new OrderRequested(reservationId, eventId, userId, STRATEGY, now, expiresAt,
                hold.toSeconds(), ticketTypeId, quantity, null);
        try {
            publisher.sendToCreateQueue(message);
        } catch (RuntimeException e) {
            stock.release(eventId, ticketTypeId, userId, quantity); // RabbitMQ didn't take it: give them back
            throw new TooBusyException("We're very busy right now. Please try again in a moment.");
        }
        return OrderResponse.queued(reservationId, eventId, STRATEGY, expiresAt, ticketTypeId, quantity, List.of());
    }

    /** Numbered seats: hold the exact seats in Redis (or the best ones), then queue the order with them. */
    private OrderResponse purchaseSeats(SeatLayout layout, Long userId, List<Long> seatIds, int quantity) {
        Long eventId = layout.eventId();
        Long ticketTypeId = layout.ticketTypeId();
        UUID reservationId = UUID.randomUUID(); // made first: Redis records it as the holder of the seats
        SeatService.Held held = seats.reserve(layout, userId, reservationId, seatIds, quantity);

        Instant now = Instant.now();
        Duration hold = holdPolicy.holdFor(eventId);
        Instant expiresAt = now.plus(hold);
        int count = held.seatIds().size();
        OrderRequested message = new OrderRequested(reservationId, eventId, userId, STRATEGY, now, expiresAt,
                hold.toSeconds(), ticketTypeId, count, held.seatIds());
        try {
            publisher.sendToCreateQueue(message);
        } catch (RuntimeException e) {
            seats.release(eventId, ticketTypeId, userId, reservationId, held.seatIds()); // give the seats back
            throw new TooBusyException("We're very busy right now. Please try again in a moment.");
        }
        return OrderResponse.queued(reservationId, eventId, STRATEGY, expiresAt, ticketTypeId, count, held.labels());
    }
}
