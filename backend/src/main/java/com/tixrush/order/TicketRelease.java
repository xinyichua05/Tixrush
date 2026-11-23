package com.tixrush.order;

import com.tixrush.event.EventRepository;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.seat.SeatRepository;
import com.tixrush.seat.SeatService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Puts the tickets of an unpaid order back on sale, in Postgres AND Redis. Used by:
 *   - the expiry worker (the 10-minute payment hold ran out)
 *   - OrderService.cancel (the buyer pressed "Cancel order")
 *
 * Call it right AFTER your conditional UPDATE (expireIfPending / cancelIfPending) returned 1,
 * inside the same transaction: that UPDATE is what guarantees we give the tickets back only once.
 */
@Component
public class TicketRelease {

    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final SeatRepository seatRepository;
    private final RedisStockService redisStock;
    private final TicketStockService ticketStock;
    private final SeatService seatService;

    public TicketRelease(EventRepository eventRepository, TicketTypeRepository ticketTypeRepository,
                         SeatRepository seatRepository, RedisStockService redisStock,
                         TicketStockService ticketStock, SeatService seatService) {
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.seatRepository = seatRepository;
        this.redisStock = redisStock;
        this.ticketStock = ticketStock;
        this.seatService = seatService;
    }

    /**
     * typeId null = old single-pool event (1 ticket). seatIds empty = no numbered seats.
     */
    @Transactional(propagation = Propagation.MANDATORY) // must join the caller's transaction
    public void giveBack(Long eventId, Long typeId, Long userId, UUID reservationId, int quantity, List<Long> seatIds) {
        boolean hasSeats = seatIds != null && !seatIds.isEmpty();
        if (typeId == null) {
            eventRepository.incrementAvailable(eventId);
        } else {
            // Same lock order as the create worker: event first, then the ticket type, then seats.
            eventRepository.incrementAvailableBy(eventId, quantity);
            ticketTypeRepository.incrementAvailable(typeId, quantity);
            if (hasSeats) {
                seatRepository.release(reservationId); // the seats are free again
            }
        }

        // Redis is not part of the DB transaction. Only touch it once the DB change is really
        // committed. Otherwise a rollback + retry could give the same ticket back twice.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (typeId == null) {
                    redisStock.release(eventId);
                } else if (hasSeats) {
                    seatService.release(eventId, typeId, userId, reservationId, seatIds);
                } else {
                    ticketStock.release(eventId, typeId, userId, quantity);
                }
            }
        });
    }
}
