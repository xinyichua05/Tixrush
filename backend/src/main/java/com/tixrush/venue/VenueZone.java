package com.tixrush.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One area of a venue: "Rock Zone" (standing, 2000 people), "CAT 1" (seated, 1600)...
 * The colour matches that area on the seating-plan picture, so buyers can find it.
 *
 * venueId is a plain id (not @ManyToOne), the same style as Order.eventId.
 */
@Entity
@Table(name = "venue_zones")
public class VenueZone {

    public static final String SEATED = "SEATED";
    public static final String STANDING = "STANDING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Seated zones: a grid of seatRows rows (A, B, C...) x seatsPerRow seats. null for standing zones. */
    @Column(name = "seat_rows")
    private Integer seatRows;

    @Column(name = "seats_per_row")
    private Integer seatsPerRow;

    protected VenueZone() {
    }

    public VenueZone(Long venueId, String name, String kind, int capacity, String color, int sortOrder,
                     Integer seatRows, Integer seatsPerRow) {
        this.venueId = venueId;
        this.name = name;
        this.kind = kind;
        this.capacity = capacity;
        this.color = color;
        this.sortOrder = sortOrder;
        this.seatRows = seatRows;
        this.seatsPerRow = seatsPerRow;
    }

    public Long getId() { return id; }
    public Long getVenueId() { return venueId; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public int getCapacity() { return capacity; }
    public String getColor() { return color; }
    public int getSortOrder() { return sortOrder; }
    public Integer getSeatRows() { return seatRows; }
    public Integer getSeatsPerRow() { return seatsPerRow; }
}
