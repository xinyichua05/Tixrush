package com.tixrush.common;

/**
 * The Redis key names for ticket-type stock, in one place: the buy path (order package) and the
 * seat code (seat package) must agree on them exactly.
 */
public final class StockKeys {

    private StockKeys() {
    }

    /** Tickets left of one ticket type: "tixrush:stock:event:42:type:7" = "1600". */
    public static String stock(Long eventId, Long ticketTypeId) {
        return "tixrush:stock:event:" + eventId + ":type:" + ticketTypeId;
    }

    /** Tickets one person holds for one event (the 4-per-person limit): "tixrush:bought:event:42:user:5" = "2". */
    public static String bought(Long eventId, Long userId) {
        return "tixrush:bought:event:" + eventId + ":user:" + userId;
    }

    /** A SCAN pattern matching every person's "bought" counter of one event. */
    public static String boughtByAnyone(Long eventId) {
        return "tixrush:bought:event:" + eventId + ":user:*";
    }

    /** Numbered seats that are taken (held or sold): a hash { seatId: reservationId }. */
    public static String seats(Long eventId, Long ticketTypeId) {
        return "tixrush:seats:event:" + eventId + ":type:" + ticketTypeId;
    }
}
