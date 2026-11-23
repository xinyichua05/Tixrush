package com.tixrush.seat;

import java.util.List;
import java.util.Set;

/**
 * "Best available": the best n free seats SIDE BY SIDE, the way box offices pick them.
 *
 *   1. the front-most row that has n free seats next to each other
 *   2. in that row, the block whose middle is closest to the middle of the row (best view)
 *
 * A pure function (no database, no Redis), so it is easy to test on its own.
 */
public final class BestSeats {

    private BestSeats() {
    }

    /** @return the seat ids, left to right, or an empty list if no row has n free seats together. */
    public static List<Long> find(SeatLayout layout, Set<Long> taken, int n) {
        for (SeatLayout.Row row : layout.rows()) {
            List<SeatLayout.Seat> seats = row.seats();
            double rowMiddle = (seats.getFirst().number() + seats.getLast().number()) / 2.0;
            int bestStart = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int start = 0; start + n <= seats.size(); start++) {
                if (!freeTogether(seats, start, n, taken)) {
                    continue;
                }
                double blockMiddle = (seats.get(start).number() + seats.get(start + n - 1).number()) / 2.0;
                double distance = Math.abs(blockMiddle - rowMiddle);
                if (distance < bestDistance) { // strictly closer: on a tie the left block wins
                    bestDistance = distance;
                    bestStart = start;
                }
            }
            if (bestStart >= 0) {
                return seats.subList(bestStart, bestStart + n).stream().map(SeatLayout.Seat::id).toList();
            }
        }
        return List.of();
    }

    /** n seats from `start` are all free AND really next to each other (no gap in the numbers). */
    private static boolean freeTogether(List<SeatLayout.Seat> seats, int start, int n, Set<Long> taken) {
        for (int i = start; i < start + n; i++) {
            if (taken.contains(seats.get(i).id())) {
                return false;
            }
            if (i > start && seats.get(i).number() != seats.get(i - 1).number() + 1) {
                return false;
            }
        }
        return true;
    }
}
