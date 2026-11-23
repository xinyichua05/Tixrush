package com.tixrush.venue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A place where events happen (Axiata Arena, Zepp KL...). Set up ONCE by an admin, with its
 * seating-plan picture and zones, and then reused by every event held there.
 */
@Entity
@Table(name = "venues")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, unique = true)
    private String name;

    /** Key of the seating-plan picture (null = none). Written only by VenueRepository.setMapKey(). */
    @Column(name = "map_key", insertable = false, updatable = false)
    private String mapKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Venue() {
    }

    public Venue(String name) {
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getMapKey() { return mapKey; }

    /** Where the browser loads the map. The key in the URL changes with every upload (cache-buster). */
    public String getMapUrl() {
        return mapKey == null ? null : "/api/venues/" + id + "/map?v=" + mapKey;
    }
}
