package com.tixrush.seat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The seats table, with plain SQL (JdbcClient): thousands of tiny rows that are only ever
 * created, listed and marked taken/free in bulk. That's simpler and much faster than JPA entities.
 */
@Repository
public class SeatRepository {

    /** One seat as stored. reservationId = the order holding it, or null when free. */
    public record SeatRow(long id, int rowIndex, String rowLabel, int seatNumber, UUID reservationId) {
    }

    private final JdbcClient jdbc;

    public SeatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates every seat of a ticket type in ONE statement: generate_series makes the grid
     * (rows 0..R-1 x seats 1..N). Row labels: A..Z, then AA, AB... like real venues.
     */
    public int generate(long ticketTypeId, int rows, int seatsPerRow) {
        return jdbc.sql("""
                        insert into seats (ticket_type_id, row_index, row_label, seat_number)
                        select :typeId, r,
                               case when r < 26 then chr(65 + r)
                                    else chr(64 + r / 26) || chr(65 + r % 26) end,
                               s
                        from generate_series(0, :rows - 1) r, generate_series(1, :seats) s
                        """)
                .param("typeId", ticketTypeId).param("rows", rows).param("seats", seatsPerRow)
                .update();
    }

    /** All seats of a ticket type, front row first, left to right. */
    public List<SeatRow> findByTicketType(long ticketTypeId) {
        return jdbc.sql("""
                        select id, row_index, row_label, seat_number, reservation_id from seats
                        where ticket_type_id = :typeId order by row_index, seat_number
                        """)
                .param("typeId", ticketTypeId)
                .query((rs, n) -> new SeatRow(rs.getLong("id"), rs.getInt("row_index"), rs.getString("row_label"),
                        rs.getInt("seat_number"), rs.getObject("reservation_id", UUID.class)))
                .list();
    }

    public long countByTicketType(long ticketTypeId) {
        return jdbc.sql("select count(*) from seats where ticket_type_id = :typeId")
                .param("typeId", ticketTypeId).query(Long.class).single();
    }

    /**
     * The order worker writes who holds these seats. "reservation_id is null" = only free seats,
     * so even a mistake elsewhere can never give one seat to two orders. Returns how many it took.
     */
    public int assign(UUID reservationId, long ticketTypeId, Collection<Long> seatIds) {
        return jdbc.sql("""
                        update seats set reservation_id = :rid
                        where id in (:ids) and ticket_type_id = :typeId and reservation_id is null
                        """)
                .param("rid", reservationId).param("ids", seatIds).param("typeId", ticketTypeId)
                .update();
    }

    /** An unpaid order expired: its seats are free again. */
    public int release(UUID reservationId) {
        return jdbc.sql("update seats set reservation_id = null where reservation_id = :rid")
                .param("rid", reservationId).update();
    }

    /** The seat ids held by one order (to free them in Redis when the order is cancelled). */
    public List<Long> seatIdsOf(UUID reservationId) {
        return jdbc.sql("select id from seats where reservation_id = :rid order by id")
                .param("rid", reservationId).query(Long.class).list();
    }

    /** The seats of many orders in ONE query (for "My tickets"), in seat order. */
    public Map<UUID, List<SeatLabel>> labelsFor(Collection<UUID> reservationIds) {
        Map<UUID, List<SeatLabel>> result = new HashMap<>();
        if (reservationIds.isEmpty()) {
            return result;
        }
        jdbc.sql("""
                        select reservation_id, row_label, seat_number from seats
                        where reservation_id in (:ids) order by row_index, seat_number
                        """)
                .param("ids", reservationIds)
                .query(rs -> {
                    result.computeIfAbsent(rs.getObject("reservation_id", UUID.class), k -> new ArrayList<>())
                            .add(new SeatLabel(rs.getString("row_label"), rs.getInt("seat_number")));
                });
        return result;
    }
}
