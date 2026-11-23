package com.tixrush.venue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of POST /api/venues and PUT /api/venues/{id}: the name and ALL zones, in display order.
 * (Saving replaces the zones; events already copied theirs, so they don't change.)
 */
public record VenueRequest(
        @NotBlank @Size(max = 200) String name,
        // @Valid also checks every zone inside the list.
        @NotEmpty(message = "Add at least one zone") @Size(max = 12, message = "A venue can have at most 12 zones")
        List<@Valid @NotNull Zone> zones
) {
    /**
     * STANDING: give the capacity.
     * SEATED:   give rows + seatsPerRow; every seat gets a row letter and a number, and the
     *           capacity is rows x seatsPerRow (a capacity sent along is ignored).
     */
    public record Zone(
            @NotBlank @Size(max = 60) String name,
            @NotNull @Pattern(regexp = "SEATED|STANDING", message = "must be SEATED or STANDING") String kind,
            @Min(1) @Max(100_000) Integer capacity,
            @NotNull @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "must be a colour like #e11d48") String color,
            @Min(1) @Max(60) Integer rows,
            @Min(1) @Max(80) Integer seatsPerRow
    ) {
        /** Old shape: a zone with just a capacity. */
        public Zone(String name, String kind, int capacity, String color) {
            this(name, kind, capacity, color, null, null);
        }
    }
}
