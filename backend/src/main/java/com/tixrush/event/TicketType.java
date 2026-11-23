package com.tixrush.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;

/**
 * One kind of ticket an event sells, with its own price and stock:
 *   concert zone   "CAT 1"  RM 688, 1600 tickets  (kind + colour copied from the venue's zone)
 *   exhibition     "Adult"  RM 25,   800 tickets  (no kind, no colour)
 *
 * availableTickets is changed by the order workers with plain SQL ("x = x - 2"), never here.
 * @DynamicUpdate: when an admin changes the PRICE, Hibernate writes only the price column, so it
 * can't overwrite a stock number that a worker changed in the meantime.
 */
@Entity
@Table(name = "ticket_types")
@DynamicUpdate
public class TicketType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 10)
    private String kind; // SEATED / STANDING, or null for simple tickets

    @Column(length = 7)
    private String color; // "#e11d48", or null for simple tickets

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "total_tickets", nullable = false)
    private int totalTickets;

    @Column(name = "available_tickets", nullable = false)
    private int availableTickets;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Numbered seats (copied from a seated venue zone): a grid of rows x seats. null = no seat map. */
    @Column(name = "seat_rows")
    private Integer seatRows;

    @Column(name = "seats_per_row")
    private Integer seatsPerRow;

    protected TicketType() {
    }

    public TicketType(Long eventId, String name, String kind, String color, BigDecimal price, int tickets, int sortOrder,
                      Integer seatRows, Integer seatsPerRow) {
        this.eventId = eventId;
        this.name = name;
        this.kind = kind;
        this.color = color;
        this.price = price;
        this.totalTickets = tickets;
        this.availableTickets = tickets;
        this.sortOrder = sortOrder;
        this.seatRows = seatRows;
        this.seatsPerRow = seatsPerRow;
    }

    /** Allowed even after tickets are sold: orders keep the price they were made with (orders.unit_price). */
    public void changePrice(BigDecimal price) {
        this.price = price;
    }

    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getColor() { return color; }
    public BigDecimal getPrice() { return price; }
    public int getTotalTickets() { return totalTickets; }
    public int getAvailableTickets() { return availableTickets; }
    public int getSortOrder() { return sortOrder; }
    public Integer getSeatRows() { return seatRows; }
    public Integer getSeatsPerRow() { return seatsPerRow; }
}
