package com.tixrush.seat;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The seat map of a numbered ticket type. Public (GET /api/events/** is open to everyone).
 *   .../seats        the layout: rows and seat numbers. Never changes for a ticket type, so browsers may keep it.
 *   .../seats/taken  which seats are taken right now. Small, live, polled every few seconds.
 */
@RestController
@RequestMapping("/api/events/{eventId}/ticket-types/{ticketTypeId}/seats")
public class SeatController {

    public record SeatMapResponse(long ticketTypeId, String name, List<RowView> rows) {
    }

    public record RowView(String label, List<SeatView> seats) {
    }

    public record SeatView(long id, int number) {
    }

    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @GetMapping
    public ResponseEntity<SeatMapResponse> layout(@PathVariable Long eventId, @PathVariable Long ticketTypeId) {
        SeatLayout layout = seatService.layout(eventId, ticketTypeId);
        SeatMapResponse body = new SeatMapResponse(layout.ticketTypeId(), layout.typeName(), layout.rows().stream()
                .map(r -> new RowView(r.label(), r.seats().stream().map(s -> new SeatView(s.id(), s.number())).toList()))
                .toList());
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(body);
    }

    @GetMapping("/taken")
    public ResponseEntity<List<Long>> taken(@PathVariable Long eventId, @PathVariable Long ticketTypeId) {
        List<Long> ids = seatService.taken(eventId, ticketTypeId).stream().sorted().toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ids);
    }
}
