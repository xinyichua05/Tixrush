package com.tixrush.event;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.config.RedisConfig;
import com.tixrush.order.OrderRepository;
import com.tixrush.order.RedisStockService;
import com.tixrush.order.TicketStockService;
import com.tixrush.seat.SeatRepository;
import com.tixrush.venue.Venue;
import com.tixrush.venue.VenueRepository;
import com.tixrush.venue.VenueZone;
import com.tixrush.venue.VenueZoneRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service layer = business rules. The controller is only HTTP plumbing, and the
 * repository is only data access. Everything in between lives here.
 *
 * Constructor injection: Spring sees that this constructor needs an EventRepository,
 * finds the bean it generated, and passes it in. You never call "new EventService(...)".
 */
@Service
public class EventService {

    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final VenueRepository venueRepository;
    private final VenueZoneRepository zoneRepository;
    private final OrderRepository orderRepository;
    private final RedisStockService redisStock;
    private final TicketStockService ticketStock;
    private final SeatRepository seatRepository;
    private final EventViews views;

    public EventService(EventRepository eventRepository, TicketTypeRepository ticketTypeRepository,
                        VenueRepository venueRepository, VenueZoneRepository zoneRepository,
                        OrderRepository orderRepository, RedisStockService redisStock,
                        TicketStockService ticketStock, SeatRepository seatRepository, EventViews views) {
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.venueRepository = venueRepository;
        this.zoneRepository = zoneRepository;
        this.orderRepository = orderRepository;
        this.redisStock = redisStock;
        this.ticketStock = ticketStock;
        this.seatRepository = seatRepository;
        this.views = views;
    }

    // readOnly = true is a hint to Hibernate/DB: no dirty-checking, no writes expected.
    @Transactional(readOnly = true)
    public List<EventResponse> listEvents() {
        return views.of(eventRepository.findByPracticeFalse());
    }

    /**
     * Cache-aside: first look in Redis ("tixrush:cache:event::42"). On a hit, return it and
     * never touch Postgres. On a miss, run this method and store the result for 5s.
     * Trade-off: availableTickets on this page can be up to 5s out of date. Buying
     * itself always checks the real stock.
     */
    @Cacheable(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional(readOnly = true)
    public EventResponse getEvent(Long id) {
        return views.of(findOrThrow(id));
    }

    @Transactional
    public EventResponse createEvent(CreateEventRequest request) {
        Venue venue = venueOrNull(request.venueId());
        List<Row> rows = resolveRows(venue, request.ticketTypes());
        Event event = new Event(
                request.name(),
                venue != null ? venue.getName() : request.venue(),
                request.startsAt(),
                request.saleStartsAt(),
                rows == null ? request.totalTickets() : sumOf(rows)
        );
        if (rows != null) {
            event.useTicketTypes(venue == null ? null : venue.getId(), sumOf(rows));
        }
        // save() runs INSERT and returns the entity with its generated id filled in.
        Event saved = eventRepository.save(event);
        if (rows != null) {
            saveRows(saved.getId(), rows);
        }
        return views.of(saved);
    }

    /**
     * Details always change. Tickets (when sent):
     *   - same types and numbers as now -> only the prices are updated (allowed at any time)
     *   - anything else                 -> replaced, but ONLY while nothing is sold
     */
    // @CachePut: always run the method, then overwrite the cache with the new result.
    @CachePut(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional
    public EventResponse updateEvent(Long id, UpdateEventRequest request) {
        Event event = findOrThrow(id);
        String venueText = request.venue();
        if (request.ticketTypes() != null) {
            Venue venue = venueOrNull(request.venueId());
            if (venue != null) {
                venueText = venue.getName();
            }
            updateTickets(event, venue, resolveRows(venue, request.ticketTypes()));
        }
        event.updateDetails(request.name(), venueText, request.startsAt(), request.saleStartsAt());
        // No save() call! "event" is a MANAGED entity: it was loaded inside this transaction,
        // so Hibernate remembers its original state. When the method returns and the
        // transaction commits, Hibernate compares old vs new ("dirty checking") and runs
        // the UPDATE itself. Look for "update events" in the SQL log.
        eventRepository.flush();
        return views.of(event);
    }

    // @CacheEvict: remove it from the cache, otherwise GET would still show a deleted event.
    @CacheEvict(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional
    public void deleteEvent(Long id) {
        // Load first so a missing id gives a clear 404, the same as GET.
        Event event = findOrThrow(id);
        List<Long> typeIds = ticketTypeRepository.findByEventIdOrderBySortOrderAsc(id).stream().map(TicketType::getId).toList();
        eventRepository.delete(event); // ticket types + orders go too (ON DELETE CASCADE)
        clearStockAfterCommit(id, typeIds); // and its Redis counters, so nothing stale is left behind
    }

    // ---- ticket types ----

    /**
     * One resolved row: what will be stored in ticket_types. kind/color only for venue zones;
     * seatRows/seatsPerRow only for numbered (seated) zones, whose tickets are always every seat.
     */
    private record Row(String name, String kind, String color, BigDecimal price, int tickets,
                       Integer seatRows, Integer seatsPerRow) {
    }

    private void updateTickets(Event event, Venue venue, List<Row> rows) {
        Long venueId = venue == null ? null : venue.getId();
        List<TicketType> current = ticketTypeRepository.findByEventIdOrderBySortOrderAsc(event.getId());

        if (Objects.equals(event.getVenueId(), venueId) && sameShape(current, rows)) {
            // Only prices (maybe) changed: safe even in the middle of a sale.
            Map<String, Row> byName = rows.stream().collect(Collectors.toMap(Row::name, Function.identity()));
            current.forEach(t -> t.changePrice(byName.get(t.getName()).price()));
            return;
        }
        if (orderRepository.countByEventId(event.getId()) > 0) {
            throw new ConflictException("Tickets are already sold, so the ticket types can't change any more. Prices can.");
        }
        ticketTypeRepository.deleteByEventId(event.getId());
        saveRows(event.getId(), rows);
        event.useTicketTypes(venueId, sumOf(rows));

        // The old Redis counters describe the old tickets: drop them once this is committed.
        clearStockAfterCommit(event.getId(), current.stream().map(TicketType::getId).toList());
    }

    /** Redis isn't part of the DB transaction: only forget its counters once the change is really saved. */
    private void clearStockAfterCommit(Long eventId, List<Long> ticketTypeIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redisStock.clear(eventId);
                ticketStock.clear(eventId, ticketTypeIds);
            }
        });
    }

    /** Same ticket types (names) with the same numbers of tickets, ignoring the prices. */
    private static boolean sameShape(List<TicketType> current, List<Row> rows) {
        if (current.size() != rows.size()) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            TicketType t = current.get(i);
            Row r = rows.get(i);
            if (!t.getName().equals(r.name()) || t.getTotalTickets() != r.tickets()
                    || !Objects.equals(t.getSeatRows(), r.seatRows()) || !Objects.equals(t.getSeatsPerRow(), r.seatsPerRow())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks the form rows and turns them into what gets stored. null in = null out (no ticket types).
     *   With a venue:  every row must be one of ITS zones; name, kind and colour come from the zone.
     *   Without:       every row needs its own, unique name.
     */
    private List<Row> resolveRows(Venue venue, List<TicketTypeInput> inputs) {
        if (inputs == null) {
            if (venue != null) {
                throw new BadRequestException("Choose which zones of the venue to sell.");
            }
            return null;
        }
        if (inputs.isEmpty()) {
            throw new BadRequestException("Add at least one ticket type.");
        }
        List<Row> rows = new ArrayList<>();
        if (venue != null) {
            Map<Long, VenueZone> zones = zoneRepository.findByVenueIdOrderBySortOrderAsc(venue.getId()).stream()
                    .collect(Collectors.toMap(VenueZone::getId, Function.identity()));
            Set<Long> used = new HashSet<>();
            List<TicketTypeInput> sorted = new ArrayList<>(inputs);
            for (TicketTypeInput in : sorted) {
                VenueZone zone = in.venueZoneId() == null ? null : zones.get(in.venueZoneId());
                if (zone == null) {
                    throw new BadRequestException("That zone isn't part of " + venue.getName() + ". Reload the form and try again.");
                }
                if (!used.add(zone.getId())) {
                    throw new BadRequestException("The zone \"" + zone.getName() + "\" is listed twice.");
                }
            }
            // Keep the venue's own zone order, whatever order the form sent.
            sorted.sort(Comparator.comparingInt(in -> zones.get(in.venueZoneId()).getSortOrder()));
            for (TicketTypeInput in : sorted) {
                VenueZone zone = zones.get(in.venueZoneId());
                boolean numbered = zone.getSeatRows() != null && zone.getSeatsPerRow() != null;
                // Numbered seats: every seat of the zone is sold, whatever number the form sent.
                int tickets = numbered ? zone.getSeatRows() * zone.getSeatsPerRow() : in.tickets();
                rows.add(new Row(zone.getName(), zone.getKind(), zone.getColor(), in.price(), tickets,
                        numbered ? zone.getSeatRows() : null, numbered ? zone.getSeatsPerRow() : null));
            }
        } else {
            Set<String> names = new HashSet<>();
            for (TicketTypeInput in : inputs) {
                String name = in.name() == null ? "" : in.name().trim();
                if (name.isEmpty()) {
                    throw new BadRequestException("Every ticket type needs a name, like \"Adult\" or \"General admission\".");
                }
                if (!names.add(name.toLowerCase())) {
                    throw new BadRequestException("Two ticket types are both called \"" + name + "\". Names must be different.");
                }
                rows.add(new Row(name, null, null, in.price(), in.tickets(), null, null));
            }
        }
        return rows;
    }

    private void saveRows(Long eventId, List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            TicketType saved = ticketTypeRepository.save(new TicketType(eventId, r.name(), r.kind(), r.color(), r.price(),
                    r.tickets(), i, r.seatRows(), r.seatsPerRow()));
            if (r.seatRows() != null) {
                seatRepository.generate(saved.getId(), r.seatRows(), r.seatsPerRow()); // A1, A2 ... every seat
            }
        }
    }

    private static int sumOf(List<Row> rows) {
        return rows.stream().mapToInt(Row::tickets).sum();
    }

    private Venue venueOrNull(Long venueId) {
        if (venueId == null) {
            return null;
        }
        return venueRepository.findById(venueId)
                .orElseThrow(() -> new BadRequestException("We couldn't find that venue. Reload the form and try again."));
    }

    /** Shared helper: load an event or fail with 404. */
    private Event findOrThrow(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("We couldn't find that event."));
    }
}
