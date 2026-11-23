package com.tixrush.event;

import com.tixrush.venue.Venue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * DTO (Data Transfer Object) = the JSON shape the API sends back.
 * We never return the Event entity directly, because:
 *  - the API shape would change whenever the table changes
 *  - lazy-loaded relations can blow up or leak data during JSON serialization
 *
 * A Java "record" is an immutable data class: constructor, getters, equals/hashCode
 * and toString are generated for you.
 *
 * Build it with EventViews (it loads the ticket types and venue too), not by hand.
 */
public record EventResponse(
        Long id,
        String name,
        String venue,
        Instant startsAt,
        Instant saleStartsAt,
        int totalTickets,
        int availableTickets,
        String imageUrl,                 // null = no picture; the URL changes whenever the picture does
        Long venueId,                    // null = no saved venue (simple tickets, or the old single pool)
        String mapUrl,                   // the venue's seating plan, or null
        List<TicketTypeView> ticketTypes, // empty = the old single "Standard ticket" pool
        BigDecimal minPrice              // cheapest ticket type ("From RM 25"), or null
) {
    /** seatRows/seatsPerRow set = numbered seats: buyers can pick them on a seat map. */
    public record TicketTypeView(Long id, String name, String kind, String color, BigDecimal price,
                                 int totalTickets, int availableTickets, Integer seatRows, Integer seatsPerRow) {
        static TicketTypeView from(TicketType t) {
            return new TicketTypeView(t.getId(), t.getName(), t.getKind(), t.getColor(), t.getPrice(),
                    t.getTotalTickets(), t.getAvailableTickets(), t.getSeatRows(), t.getSeatsPerRow());
        }
    }

    static EventResponse from(Event event, List<TicketType> types, Venue venue) {
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getVenue(),
                event.getStartsAt(),
                event.getSaleStartsAt(),
                event.getTotalTickets(),
                event.getAvailableTickets(),
                event.getImageKey() == null ? null
                        : "/api/events/" + event.getId() + "/image?v=" + event.getImageKey(),
                event.getVenueId(),
                venue == null ? null : venue.getMapUrl(),
                types.stream().map(TicketTypeView::from).toList(),
                types.stream().map(TicketType::getPrice).min(Comparator.naturalOrder()).orElse(null)
        );
    }
}
