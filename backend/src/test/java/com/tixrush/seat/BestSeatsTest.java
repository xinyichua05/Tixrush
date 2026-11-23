package com.tixrush.seat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** "Best available" on its own: no database, no Redis. Seat ids here are row * 100 + number. */
class BestSeatsTest {

    /** rows x seats grid: row A = ids 1..n, row B = 101..100+n, ... */
    private static SeatLayout grid(int rows, int seats) {
        List<SeatRepository.SeatRow> stored = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int s = 1; s <= seats; s++) {
                stored.add(new SeatRepository.SeatRow(r * 100L + s, r, String.valueOf((char) ('A' + r)), s, null));
            }
        }
        return SeatLayout.of(1, 1, "CAT 1", stored);
    }

    @Test
    void emptyHall_frontRow_rightInTheMiddle() {
        // 10 seats: the middle pair is 5-6, the middle three are 4-5-6 (tie: the left one wins).
        assertThat(BestSeats.find(grid(3, 10), Set.of(), 2)).containsExactly(5L, 6L);
        assertThat(BestSeats.find(grid(3, 10), Set.of(), 3)).containsExactly(4L, 5L, 6L);
        assertThat(BestSeats.find(grid(3, 9), Set.of(), 1)).containsExactly(5L);
    }

    @Test
    void skipsTakenSeats_staysAsCentralAsPossible() {
        // Row A: 5 and 6 are taken. Free pairs closest to the middle: 3-4 or 7-8 (equally close: left wins).
        assertThat(BestSeats.find(grid(3, 10), Set.of(5L, 6L), 2)).containsExactly(3L, 4L);
        // Only 8-9-10 are free together in row A (1..7 taken): still row A, because the front row comes first.
        assertThat(BestSeats.find(grid(3, 10), Set.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), 3)).containsExactly(8L, 9L, 10L);
    }

    @Test
    void frontRowTooBrokenUp_goesToTheNextRow() {
        // Row A has free seats, but never 2 side by side (every other seat taken).
        Set<Long> everyOther = Set.of(2L, 4L, 6L, 8L, 10L);
        assertThat(BestSeats.find(grid(3, 10), everyOther, 2)).containsExactly(105L, 106L);
        assertThat(BestSeats.find(grid(3, 10), everyOther, 1)).containsExactly(5L); // a single seat still fits in A
    }

    @Test
    void nothingTogetherAnywhere_returnsEmpty() {
        assertThat(BestSeats.find(grid(1, 4), Set.of(2L), 3)).isEmpty();
        assertThat(BestSeats.find(grid(2, 2), Set.of(1L, 2L, 101L, 102L), 1)).isEmpty();
    }
}
