package com.tixrush.event;

import com.tixrush.venue.Venue;
import com.tixrush.venue.VenueRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns Event entities into EventResponse JSON, together with their ticket types and venue map.
 *
 * For a list it runs 3 queries in total (events, ALL their ticket types, ALL their venues),
 * not 1 + 2 per event. That trap is called the "N+1 query problem".
 */
@Component
public class EventViews {

    private final TicketTypeRepository ticketTypeRepository;
    private final VenueRepository venueRepository;

    public EventViews(TicketTypeRepository ticketTypeRepository, VenueRepository venueRepository) {
        this.ticketTypeRepository = ticketTypeRepository;
        this.venueRepository = venueRepository;
    }

    public EventResponse of(Event event) {
        return of(List.of(event)).getFirst();
    }

    public List<EventResponse> of(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TicketType>> types = ticketTypeRepository
                .findByEventIdInOrderBySortOrderAsc(events.stream().map(Event::getId).toList()).stream()
                .collect(Collectors.groupingBy(TicketType::getEventId));
        List<Long> venueIds = events.stream().map(Event::getVenueId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Venue> venues = venueIds.isEmpty() ? Map.of()
                : venueRepository.findAllById(venueIds).stream().collect(Collectors.toMap(Venue::getId, Function.identity()));
        return events.stream()
                .map(e -> EventResponse.from(e, types.getOrDefault(e.getId(), List.of()),
                        e.getVenueId() == null ? null : venues.get(e.getVenueId())))
                .toList();
    }
}
