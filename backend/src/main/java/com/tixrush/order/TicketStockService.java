package com.tixrush.order;

import com.tixrush.common.NotFoundException;
import com.tixrush.common.StockKeys;
import com.tixrush.event.TicketTypeRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The Redis ticket counters for events that sell ticket types: ONE counter per type
 * ("tixrush:stock:event:42:type:7" = "1600"), plus how many tickets each person already holds
 * for the event ("tixrush:bought:event:42:user:5" = "2"), for the 4-per-person limit.
 *
 * Same idea as RedisStockService (which serves the old single-pool events): everything happens
 * in memory, in one atomic Lua step, so a rush never waits for Postgres.
 */
@Service
public class TicketStockService {

    /** Per-person limit for one event, over all ticket types and orders together. */
    public static final int MAX_PER_PERSON = 4;

    /** The per-person counter cleans itself up after this long (long after any sale is over). */
    private static final Duration BOUGHT_TTL = Duration.ofDays(90);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE =
            RedisScript.of(new ClassPathResource("redis/reserve_ticket_type.lua"), List.class);
    private static final RedisScript<Long> RELEASE =
            RedisScript.of(new ClassPathResource("redis/release_ticket_type.lua"), Long.class);

    /** What happened. "number" = tickets left (OK / NOT_ENOUGH) or tickets already held (OVER_LIMIT). */
    public record Result(Outcome outcome, long number) {
    }

    public enum Outcome { OK, NOT_ENOUGH, OVER_LIMIT }

    private final StringRedisTemplate redis;
    private final TicketTypeRepository ticketTypeRepository;

    public TicketStockService(StringRedisTemplate redis, TicketTypeRepository ticketTypeRepository) {
        this.redis = redis;
        this.ticketTypeRepository = ticketTypeRepository;
    }

    public Result tryReserve(Long eventId, Long ticketTypeId, Long userId, int quantity) {
        List<Long> r = runReserve(eventId, ticketTypeId, userId, quantity);
        if (r.get(0) == -1) { // first buyer of this type: copy its stock from the DB, then try again
            loadStockFromDatabase(eventId, ticketTypeId);
            r = runReserve(eventId, ticketTypeId, userId, quantity);
        }
        long code = r.get(0);
        long number = r.get(1);
        if (code == 1) {
            return new Result(Outcome.OK, number);
        }
        return new Result(code == -2 ? Outcome.OVER_LIMIT : Outcome.NOT_ENOUGH, number);
    }

    /** Give tickets back and lower the person's count (expired order, or queueing failed). */
    public void release(Long eventId, Long ticketTypeId, Long userId, int quantity) {
        redis.execute(RELEASE, List.of(stockKey(eventId, ticketTypeId), boughtKey(eventId, userId)),
                String.valueOf(quantity));
    }

    public Integer getStock(Long eventId, Long ticketTypeId) {
        String value = redis.opsForValue().get(stockKey(eventId, ticketTypeId));
        return value == null ? null : Integer.valueOf(value);
    }

    /** Forget these counters (and taken-seat lists); the next purchase reloads them from the DB. */
    public void clear(Long eventId, Collection<Long> ticketTypeIds) {
        if (!ticketTypeIds.isEmpty()) {
            redis.delete(ticketTypeIds.stream()
                    .flatMap(t -> java.util.stream.Stream.of(stockKey(eventId, t), StockKeys.seats(eventId, t)))
                    .toList());
        }
    }

    public void clearBought(Long eventId, Long userId) {
        redis.delete(boughtKey(eventId, userId));
    }

    @SuppressWarnings("unchecked")
    private List<Long> runReserve(Long eventId, Long ticketTypeId, Long userId, int quantity) {
        List<Long> result = redis.execute(RESERVE,
                List.of(stockKey(eventId, ticketTypeId), boughtKey(eventId, userId)),
                String.valueOf(quantity), String.valueOf(MAX_PER_PERSON), String.valueOf(BOUGHT_TTL.toSeconds()));
        return result == null ? List.of(-1L, 0L) : result;
    }

    /** Load the counter now instead of on the first purchase (the Practice rush shows live counts from second 0). */
    public void prime(Long eventId, Long ticketTypeId) {
        loadStockFromDatabase(eventId, ticketTypeId);
    }

    /**
     * Forget EVERYONE's "tickets I already hold" count for this event. The Practice rush reuses the
     * same event and the same fans every run; without this they'd hit the 4-per-person limit after
     * a few runs. SCAN walks the keys in small steps, so Redis is never blocked.
     */
    public void clearAllBought(Long eventId) {
        List<String> keys = new ArrayList<>();
        ScanOptions match = ScanOptions.scanOptions().match(StockKeys.boughtByAnyone(eventId)).count(1000).build();
        try (Cursor<String> cursor = redis.scan(match)) {
            cursor.forEachRemaining(keys::add);
        }
        for (int i = 0; i < keys.size(); i += 500) {
            redis.delete(keys.subList(i, Math.min(keys.size(), i + 500)));
        }
    }

    /** SET NX, like RedisStockService: if many buyers load at once, only the first load counts. */
    private void loadStockFromDatabase(Long eventId, Long ticketTypeId) {
        int available = ticketTypeRepository.findAvailable(ticketTypeId, eventId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that ticket type for this event."));
        redis.opsForValue().setIfAbsent(stockKey(eventId, ticketTypeId), String.valueOf(available));
    }

    private static String stockKey(Long eventId, Long ticketTypeId) {
        return StockKeys.stock(eventId, ticketTypeId);
    }

    private static String boughtKey(Long eventId, Long userId) {
        return StockKeys.bought(eventId, userId);
    }
}
