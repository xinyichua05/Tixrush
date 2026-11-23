package com.tixrush.seat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seats of one numbered ticket type, front row first, left to right. It never changes for a
 * given ticket type id (changing the zones makes NEW ticket types), so it is safe to keep in memory.
 */
public record SeatLayout(long eventId, long ticketTypeId, String typeName, List<Row> rows, Map<Long, Seat> byId) {

    public record Row(int index, String label, List<Seat> seats) {
    }

    public record Seat(long id, int rowIndex, String rowLabel, int number) {
        public SeatLabel label() {
            return new SeatLabel(rowLabel, number);
        }
    }

    static SeatLayout of(long eventId, long ticketTypeId, String typeName, List<SeatRepository.SeatRow> stored) {
        Map<Integer, List<Seat>> byRow = new LinkedHashMap<>();
        Map<Long, Seat> byId = new LinkedHashMap<>();
        Map<Integer, String> labels = new LinkedHashMap<>();
        for (SeatRepository.SeatRow s : stored) {
            Seat seat = new Seat(s.id(), s.rowIndex(), s.rowLabel(), s.seatNumber());
            byRow.computeIfAbsent(s.rowIndex(), k -> new ArrayList<>()).add(seat);
            labels.put(s.rowIndex(), s.rowLabel());
            byId.put(s.id(), seat);
        }
        List<Row> rows = byRow.entrySet().stream()
                .map(e -> new Row(e.getKey(), labels.get(e.getKey()), List.copyOf(e.getValue())))
                .toList();
        return new SeatLayout(eventId, ticketTypeId, typeName, rows, Map.copyOf(byId));
    }

    public List<SeatLabel> labelsOf(List<Long> seatIds) {
        return seatIds.stream().map(byId::get).sorted((a, b) -> a.rowIndex() != b.rowIndex()
                ? Integer.compare(a.rowIndex(), b.rowIndex()) : Integer.compare(a.number(), b.number()))
                .map(Seat::label).toList();
    }
}
