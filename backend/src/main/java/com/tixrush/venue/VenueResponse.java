package com.tixrush.venue;

import java.util.List;

/** JSON for a venue: its map, its zones, and how many events use it (those block deleting). */
public record VenueResponse(
        Long id,
        String name,
        String mapUrl, // null = no seating-plan picture yet
        List<Zone> zones,
        int totalCapacity,
        long eventCount
) {
    /** rows/seatsPerRow: the seat grid of a seated zone (null for standing zones). */
    public record Zone(Long id, String name, String kind, int capacity, String color, Integer rows, Integer seatsPerRow) {
        static Zone from(VenueZone z) {
            return new Zone(z.getId(), z.getName(), z.getKind(), z.getCapacity(), z.getColor(),
                    z.getSeatRows(), z.getSeatsPerRow());
        }
    }

    static VenueResponse from(Venue venue, String mapUrl, List<VenueZone> zones, long eventCount) {
        return new VenueResponse(
                venue.getId(),
                venue.getName(),
                mapUrl,
                zones.stream().map(Zone::from).toList(),
                zones.stream().mapToInt(VenueZone::getCapacity).sum(),
                eventCount);
    }
}
