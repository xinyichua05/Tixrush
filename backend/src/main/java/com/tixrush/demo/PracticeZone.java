package com.tixrush.demo;

import java.math.BigDecimal;

/**
 * One zone of the practice concert, like a real venue zone:
 *   seated   -> rows x seatsPerRow numbered seats (you can pick them on the seat map)
 *   standing -> just a number of tickets
 */
public record PracticeZone(String name, String kind, String color, BigDecimal price,
                           Integer rows, Integer seatsPerRow, Integer standing) {

    public static PracticeZone seated(String name, String color, int price, int rows, int seatsPerRow) {
        return new PracticeZone(name, "SEATED", color, BigDecimal.valueOf(price), rows, seatsPerRow, null);
    }

    public static PracticeZone standing(String name, String color, int price, int tickets) {
        return new PracticeZone(name, "STANDING", color, BigDecimal.valueOf(price), null, null, tickets);
    }

    public boolean isSeated() {
        return rows != null;
    }

    public int tickets() {
        return isSeated() ? rows * seatsPerRow : standing;
    }
}
