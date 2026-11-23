package com.tixrush.order;

import com.tixrush.common.NotFoundException;
import com.tixrush.common.BadRequestException;
import com.tixrush.event.EventRepository;
import com.tixrush.event.TicketTypeRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The ticket counter that lives in Redis instead of the events table.
 * Key: "tixrush:stock:event:{id}", value: number of tickets left, e.g. "500".
 *
 * Why it's fast: Redis keeps everything in memory and runs commands one at a time
 * on a single thread. So there are no locks and no transactions, and every reserve
 * is one tiny atomic operation.
 */
@Service
public class RedisStockService {

    private static final RedisScript<Long> RESERVE =
            RedisScript.of(new ClassPathResource("redis/reserve_ticket.lua"), Long.class);
    private static final RedisScript<Long> RELEASE =
            RedisScript.of(new ClassPathResource("redis/release_ticket.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;

    public RedisStockService(StringRedisTemplate redis, EventRepository eventRepository,
                             TicketTypeRepository ticketTypeRepository) {
        this.redis = redis;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
    }

    /** @return true = got a ticket, false = sold out */
    public boolean tryReserve(Long eventId) {
        long result = runReserve(eventId);
        if (result == -1) {          // first buyer for this event: copy stock from the DB
            loadStockFromDatabase(eventId);
            result = runReserve(eventId);
        }
        return result == 1;
    }

    /**
     * Give a ticket back: saving/queueing the order failed after Redis said yes, or an
     * unpaid order expired. Only adds 1 if the counter is loaded (see release_ticket.lua).
     */
    public void release(Long eventId) {
        redis.execute(RELEASE, List.of(key(eventId)));
    }

    public Integer getStock(Long eventId) {
        String value = redis.opsForValue().get(key(eventId));
        return value == null ? null : Integer.valueOf(value);
    }

    /** Load the counter from the DB now instead of on the first purchase (no-op if already loaded). */
    public void prime(Long eventId) {
        loadStockFromDatabase(eventId);
    }

    /** Remove the counter; the next purchase reloads it from the DB. */
    public void clear(Long eventId) {
        redis.delete(key(eventId));
    }

    private long runReserve(Long eventId) {
        Long result = redis.execute(RESERVE, List.of(key(eventId)));
        return result == null ? -1 : result;
    }

    /**
     * SET ... NX = "only set if the key doesn't exist yet". If 200 buyers arrive at the
     * same moment and all see -1, only the FIRST load wins. The others are ignored,
     * so the stock is never loaded twice (which would hand out extra tickets).
     */
    private void loadStockFromDatabase(Long eventId) {
        // An event that sells ticket types has no single pool: its stock lives per type
        // (TicketStockService). Without this check, buying "a ticket" would bypass the types.
        if (ticketTypeRepository.countByEventId(eventId) > 0) {
            throw new BadRequestException("Please choose a ticket type.");
        }
        int available = eventRepository.findAvailableTickets(eventId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that event."));
        redis.opsForValue().setIfAbsent(key(eventId), String.valueOf(available));
    }

    private static String key(Long eventId) {
        return "tixrush:stock:event:" + eventId;
    }
}
