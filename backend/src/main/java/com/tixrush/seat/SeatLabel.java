package com.tixrush.seat;

/** How a seat is printed on a ticket: row "F", number 12 -> "Row F, Seat 12". */
public record SeatLabel(String row, int number) {
}
