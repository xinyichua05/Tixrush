package com.tixrush.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * The JSON body of POST /api/events. Jackson turns the JSON into this record, then
 * Bean Validation checks the annotations (only if the controller parameter has @Valid).
 *
 * Tickets, two ways:
 *   ticketTypes given  -> the event sells those types; totalTickets is their sum (ignored if sent)
 *                         (+ venueId when the types are zones of a saved venue)
 *   no ticketTypes     -> the old single pool of totalTickets ("Standard ticket")
 */
public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String venue,
        @NotNull @Future Instant startsAt,
        @NotNull @Future Instant saleStartsAt,
        @Positive @Max(1_000_000) Integer totalTickets,
        Long venueId,
        @Size(max = 12, message = "An event can have at most 12 ticket types") List<@Valid @NotNull TicketTypeInput> ticketTypes
) {
    /** Old shape (single pool), still used by tests and the demo code. */
    public CreateEventRequest(String name, String venue, Instant startsAt, Instant saleStartsAt, int totalTickets) {
        this(name, venue, startsAt, saleStartsAt, totalTickets, null, null);
    }

    /**
     * Cross-field rule: one annotation can't compare two fields, so we write a method.
     * Bean Validation treats "isXxx()" methods as properties, so @AssertTrue works on it.
     * If either date is null, return true and let @NotNull report that error instead.
     * @JsonIgnore keeps Jackson from treating "saleBeforeShow" as a JSON field.
     */
    @JsonIgnore
    @AssertTrue(message = "saleStartsAt must be before startsAt")
    public boolean isSaleBeforeShow() {
        return startsAt == null || saleStartsAt == null || saleStartsAt.isBefore(startsAt);
    }

    /** Either ticket types, or a number of tickets for the single pool. */
    @JsonIgnore
    @AssertTrue(message = "must be at least 1")
    public boolean isTotalTicketsGiven() {
        return ticketTypes != null || totalTickets != null;
    }
}
