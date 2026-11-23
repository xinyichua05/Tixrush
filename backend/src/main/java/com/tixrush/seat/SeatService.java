package com.tixrush.seat;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.common.StockKeys;
import com.tixrush.event.SoldOutException;
import com.tixrush.event.TicketType;
import com.tixrush.event.TicketTypeRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Numbered seats: the seat map, "which seats are taken", and holding exact seats for a buyer.
 *
 * Taken seats live in a Redis hash per ticket type { seatId: reservationId }, next to the stock
 * counter. One Lua step checks stock + the per-person limit + "are ALL these seats free?" and then
 * takes them all, or none. The database follows a moment later (the order worker writes
 * seats.reservation_id), the same way the stock numbers do.
 */
@Service
public class SeatService {

    /** Same limit as TicketStockService.MAX_PER_PERSON (one limit per event, seats or not). */
    public static final int MAX_PER_PERSON = 4;
    private static final Duration BOUGHT_TTL = Duration.ofDays(90);
    /** Best available: if someone grabs our chosen seats first, look again (a few times). */
    private static final int BEST_AVAILABLE_TRIES = 5;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(new ClassPathResource("redis/reserve_seats.lua"), List.class);
    private static final RedisScript<Long> INIT = RedisScript.of(new ClassPathResource("redis/init_seats.lua"), Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("redis/release_seats.lua"), Long.class);

    /** Seats held for one reservation: the ids (for the order message) and how they're printed. */
    public record Held(List<Long> seatIds, List<SeatLabel> labels) {
    }

    private final StringRedisTemplate redis;
    private final SeatRepository seatRepository;
    private final TicketTypeRepository ticketTypeRepository;
    /** ticketTypeId -> its seats (empty = a type without numbered seats). Never changes for an id. */
    private final Map<Long, Optional<SeatLayout>> layouts = new ConcurrentHashMap<>();

    public SeatService(StringRedisTemplate redis, SeatRepository seatRepository, TicketTypeRepository ticketTypeRepository) {
        this.redis = redis;
        this.seatRepository = seatRepository;
        this.ticketTypeRepository = ticketTypeRepository;
    }

    /** The seats of this type, or empty if it has no numbered seats. 404 if the type isn't part of the event. */
    public Optional<SeatLayout> layoutIfSeated(Long eventId, Long ticketTypeId) {
        Optional<SeatLayout> layout = layouts.computeIfAbsent(ticketTypeId, this::loadLayout);
        if (layout.isPresent() && layout.get().eventId() != eventId) {
            throw new NotFoundException("We couldn't find that ticket type for this event.");
        }
        return layout;
    }

    public SeatLayout layout(Long eventId, Long ticketTypeId) {
        return layoutIfSeated(eventId, ticketTypeId)
                .orElseThrow(() -> new NotFoundException("This ticket type has no numbered seats."));
    }

    /** The seat ids that are taken right now (held or sold). Read from Redis: fast enough to poll. */
    public Set<Long> taken(Long eventId, Long ticketTypeId) {
        layout(eventId, ticketTypeId);
        ensureLoaded(eventId, ticketTypeId);
        return redis.<String, String>opsForHash().keys(StockKeys.seats(eventId, ticketTypeId)).stream()
                .map(Long::valueOf).collect(Collectors.toSet());
    }

    /**
     * Hold seats for a buyer. seatIds = the ones they clicked, or null for "best available".
     * Throws 409 if a seat was just taken, not enough are left, or they'd go over 4 tickets.
     */
    public Held reserve(SeatLayout layout, Long userId, UUID reservationId, List<Long> seatIds, int quantity) {
        long eventId = layout.eventId();
        long typeId = layout.ticketTypeId();
        ensureLoaded(eventId, typeId);

        if (seatIds != null && !seatIds.isEmpty()) {
            List<Long> chosen = checkChoice(layout, seatIds);
            Outcome outcome = run(layout, userId, reservationId, chosen);
            if (outcome.code == -3) {
                SeatLabel s = layout.byId().get(outcome.number).label();
                throw new ConflictException("Row " + s.row() + ", Seat " + s.number()
                        + " was just taken. Please choose another seat.");
            }
            failIfNotOk(outcome, layout);
            return new Held(chosen, layout.labelsOf(chosen));
        }

        // Best available: pick the best free seats side by side, then try to take exactly those.
        if (quantity < 1 || quantity > MAX_PER_PERSON) {
            throw new BadRequestException("You can buy 1 to " + MAX_PER_PERSON + " tickets at a time.");
        }
        for (int attempt = 0; attempt < BEST_AVAILABLE_TRIES; attempt++) {
            List<Long> best = BestSeats.find(layout, taken(eventId, typeId), quantity);
            if (best.isEmpty()) {
                if (Long.valueOf(0).equals(stockLeft(eventId, typeId))) {
                    throw new SoldOutException(eventId, "Sorry, " + layout.typeName() + " is sold out. Try another zone.");
                }
                throw new ConflictException("We couldn't find " + quantity + " seats together in " + layout.typeName()
                        + ". Try fewer, or pick your seats on the map.");
            }
            Outcome outcome = run(layout, userId, reservationId, best);
            if (outcome.code == -3) {
                continue; // someone was faster on one of those seats: look again
            }
            failIfNotOk(outcome, layout);
            return new Held(best, layout.labelsOf(best));
        }
        throw new ConflictException("Seats in " + layout.typeName() + " are going very fast. Please try again.");
    }

    /** Give the seats back (expired order, or the order message couldn't be queued). */
    public void release(Long eventId, Long ticketTypeId, Long userId, UUID reservationId, List<Long> seatIds) {
        List<String> args = new ArrayList<>(List.of(String.valueOf(seatIds.size()), reservationId.toString()));
        seatIds.forEach(id -> args.add(String.valueOf(id)));
        redis.execute(RELEASE, List.of(StockKeys.stock(eventId, ticketTypeId), StockKeys.bought(eventId, userId),
                StockKeys.seats(eventId, ticketTypeId)), args.toArray());
    }

    // ---- helpers ----

    private record Outcome(long code, long number) {
    }

    @SuppressWarnings("unchecked")
    private Outcome run(SeatLayout layout, Long userId, UUID reservationId, List<Long> seatIds) {
        List<String> args = new ArrayList<>(List.of(String.valueOf(seatIds.size()), String.valueOf(MAX_PER_PERSON),
                String.valueOf(BOUGHT_TTL.toSeconds()), reservationId.toString()));
        seatIds.forEach(id -> args.add(String.valueOf(id)));
        List<String> keys = List.of(StockKeys.stock(layout.eventId(), layout.ticketTypeId()),
                StockKeys.bought(layout.eventId(), userId), StockKeys.seats(layout.eventId(), layout.ticketTypeId()));
        List<Long> r = redis.execute(RESERVE, keys, args.toArray());
        if (r != null && r.getFirst() == -1) { // the counters were cleared in between: load and try once more
            ensureLoaded(layout.eventId(), layout.ticketTypeId());
            r = redis.execute(RESERVE, keys, args.toArray());
        }
        return r == null ? new Outcome(-1, 0) : new Outcome(r.get(0), r.get(1));
    }

    private void failIfNotOk(Outcome outcome, SeatLayout layout) {
        if (outcome.code == 1) {
            return;
        }
        if (outcome.code == -2) {
            throw new ConflictException("You can buy up to " + MAX_PER_PERSON + " tickets for this event, and you already have "
                    + outcome.number + ".");
        }
        if (outcome.code == 0 && outcome.number == 0) {
            throw new SoldOutException(layout.eventId(), "Sorry, " + layout.typeName() + " is sold out. Try another zone.");
        }
        if (outcome.code == 0) {
            throw new ConflictException("Only " + outcome.number + " seats left in " + layout.typeName() + ". Please choose fewer.");
        }
        throw new ConflictException("We couldn't hold those seats. Please try again.");
    }

    /** 1-4 different seats, all from this ticket type. Returned in seat order. */
    private static List<Long> checkChoice(SeatLayout layout, List<Long> seatIds) {
        Set<Long> unique = new LinkedHashSet<>(seatIds);
        if (unique.size() != seatIds.size()) {
            throw new BadRequestException("The same seat was chosen twice.");
        }
        if (unique.isEmpty() || unique.size() > MAX_PER_PERSON) {
            throw new BadRequestException("Choose 1 to " + MAX_PER_PERSON + " seats.");
        }
        if (!layout.byId().keySet().containsAll(unique)) {
            throw new BadRequestException("Those seats aren't part of " + layout.typeName() + ". Reload the seat map and try again.");
        }
        return layout.rows().stream().flatMap(r -> r.seats().stream()).map(SeatLayout.Seat::id)
                .filter(unique::contains).toList();
    }

    private Long stockLeft(Long eventId, Long ticketTypeId) {
        String v = redis.opsForValue().get(StockKeys.stock(eventId, ticketTypeId));
        return v == null ? null : Long.valueOf(v);
    }

    /** Copy stock + taken seats from the DB into Redis, unless they're already there (one atomic step). */
    private void ensureLoaded(Long eventId, Long ticketTypeId) {
        if (Boolean.TRUE.equals(redis.hasKey(StockKeys.stock(eventId, ticketTypeId)))) {
            return;
        }
        int available = ticketTypeRepository.findAvailable(ticketTypeId, eventId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that ticket type for this event."));
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(available));
        for (SeatRepository.SeatRow s : seatRepository.findByTicketType(ticketTypeId)) {
            if (s.reservationId() != null) {
                args.add(String.valueOf(s.id()));
                args.add(s.reservationId().toString());
            }
        }
        redis.execute(INIT, List.of(StockKeys.stock(eventId, ticketTypeId), StockKeys.seats(eventId, ticketTypeId)),
                args.toArray());
    }

    private Optional<SeatLayout> loadLayout(Long ticketTypeId) {
        TicketType type = ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that ticket type for this event."));
        if (type.getSeatRows() == null) {
            return Optional.empty();
        }
        return Optional.of(SeatLayout.of(type.getEventId(), ticketTypeId, type.getName(),
                seatRepository.findByTicketType(ticketTypeId)));
    }
}
