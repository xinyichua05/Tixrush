package com.tixrush.event;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * One row of the "Tickets" part of the event form.
 *   From a venue:    venueZoneId is set; the name, kind and colour are copied from that zone.
 *   Simple tickets:  no venueZoneId; the admin types the name ("Adult", "Student"...).
 */
public record TicketTypeInput(
        Long venueZoneId,
        @Size(max = 60) String name,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @Min(1) @Max(100_000) int tickets
) {
}
