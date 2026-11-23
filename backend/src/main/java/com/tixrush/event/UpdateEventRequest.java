package com.tixrush.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The JSON body of PUT /api/events/{id}. No totalTickets: see Event.updateDetails().
 * A separate record from CreateEventRequest because create and update have different
 * rules, and sharing one class tends to end in "this field is required, except when...".
 *
 * ticketTypes = null -> the tickets stay as they are (only the details change).
 * ticketTypes given  -> before any sale: replaces them. After a sale: only the prices may change.
 */
public record UpdateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Future Instant startsAt,
        @NotNull @Future Instant saleStartsAt,
        Long venueId,
        @Size(max = 12, message = "An event can have at most 12 ticket types") List<@Valid @NotNull TicketTypeInput> ticketTypes
) {
    /** Details only (the tickets stay as they are). */
    public UpdateEventRequest(String name, String venue, Instant startsAt, Instant saleStartsAt) {
        this(name, venue, startsAt, saleStartsAt, null, null);
    }

    @JsonIgnore
    @AssertTrue(message = "saleStartsAt must be before startsAt")
    public boolean isSaleBeforeShow() {
        return startsAt == null || saleStartsAt == null || saleStartsAt.isBefore(startsAt);
    }
}
