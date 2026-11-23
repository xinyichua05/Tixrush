package com.tixrush.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/**
 * JPA entity = a Java class mapped to one table row.
 * Hibernate reads/writes these fields for you; you never write INSERT/UPDATE by hand.
 *
 */
@Entity
@Table(name = "events")
// Only write the columns that really changed. Without this, editing an event's NAME during a sale
// would also write back the stock number Hibernate loaded a moment ago, undoing the workers'
// "available_tickets - 1" updates that happened in between (they don't bump @Version).
@DynamicUpdate
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // the DB generates the id
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "sale_starts_at", nullable = false)
    private Instant saleStartsAt;

    @Column(name = "total_tickets", nullable = false)
    private int totalTickets;

    @Column(name = "available_tickets", nullable = false)
    private int availableTickets;

    /**
     * Optimistic-lock counter. Hibernate adds "AND version = ?" to every UPDATE of this row
     * and bumps the number. If someone else updated the row first, 0 rows match and
     * Hibernate throws an OptimisticLockingFailureException instead of overwriting their change.
     */
    /** true = only for the Practice rush page: hidden from the event list and from "My tickets". */
    @Column(nullable = false)
    private boolean practice;

    @Version
    private long version;

    /**
     * Key of the uploaded picture (null = none). READ-ONLY for JPA (insertable/updatable = false):
     * it is only written by EventRepository.setImageKey(), so changing a picture never bumps
     * @Version or races with ticket sales on this row.
     */
    @Column(name = "image_key", insertable = false, updatable = false)
    private String imageKey;

    /** The saved venue whose zones this event sells (null = simple tickets or the old single pool). */
    @Column(name = "venue_id")
    private Long venueId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA needs a no-args constructor to build objects from query results. Not for your code. */
    protected Event() {
    }

    /** Use this one to create a brand-new event. All tickets start as available. */
    public Event(String name, String venue, Instant startsAt, Instant saleStartsAt, int totalTickets) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.saleStartsAt = saleStartsAt;
        this.totalTickets = totalTickets;
        this.availableTickets = totalTickets;
    }

    /**
     * Changes the descriptive details of an event.
     *
     * Why a method instead of public setters? With setters anyone could do
     * event.setAvailableTickets(-3). A named method only exposes the changes that make
     * business sense, and the entity stays in charge of its own rules.
     *
     * totalTickets is deliberately NOT changeable here: once tickets are sold, changing
     * the total would also have to adjust availableTickets. That's a separate operation.
     */
    public void updateDetails(String name, String venue, Instant startsAt, Instant saleStartsAt) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.saleStartsAt = saleStartsAt;
    }

    /** Takes one ticket from the pool, or refuses when none are left. */
    public void reserveOne() {
        if (availableTickets <= 0) {
            throw new SoldOutException(id);
        }
        availableTickets--;
    }

    /** Puts every ticket back (used only to rerun benchmarks on the same event). */
    public void resetStock() {
        availableTickets = totalTickets;
    }

    /** The Live Rush demo reuses one event: every run starts fresh with this many tickets. */
    public void restartSale(int tickets, Instant saleStartsAt) {
        this.totalTickets = tickets;
        this.availableTickets = tickets;
        this.saleStartsAt = saleStartsAt;
    }

    /**
     * The event now sells these ticket types: the totals become the sum of the types.
     * Only allowed while nothing is sold (EventService checks), so resetting "available" is safe.
     */
    public void useTicketTypes(Long venueId, int totalTickets) {
        this.venueId = venueId;
        this.totalTickets = totalTickets;
        this.availableTickets = totalTickets;
    }

    public void markPractice() {
        this.practice = true;
    }

    public boolean isPractice() { return practice; }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getSaleStartsAt() { return saleStartsAt; }
    public int getTotalTickets() { return totalTickets; }
    public int getAvailableTickets() { return availableTickets; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVenueId() { return venueId; }

    public String getImageKey() {
        return imageKey;
    }
}
