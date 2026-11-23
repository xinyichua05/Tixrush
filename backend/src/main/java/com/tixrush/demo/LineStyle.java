package com.tixrush.demo;

/**
 * How fans get their place in line. Real ticket sites use one of these two:
 *
 *   RANDOM_LOBBY   Ticketmaster / Queue-it style: wait in a lobby before the sale; when it opens,
 *                  everyone in the lobby is shuffled into a random order. Later fans join the back.
 *   FASTEST_CLICK  "Ticket war" style (Interpark, many Asian sites): nobody can queue early; the Join
 *                  button unlocks when the sale opens and your number is how fast you clicked.
 */
public enum LineStyle {
    RANDOM_LOBBY,
    FASTEST_CLICK
}
