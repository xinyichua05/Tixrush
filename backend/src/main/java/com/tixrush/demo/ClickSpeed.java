package com.tixrush.demo;

import java.util.random.RandomGenerator;

/**
 * How fast a virtual fan clicks "Join" after the sale opens, for the Fastest-click line style.
 *
 * Human reaction times are not spread evenly: nobody beats about a quarter of a second, most people
 * land around one second, and a long tail is slow (distracted, slow connection, still refreshing).
 * A log-normal curve has exactly that shape:
 *
 *   click time = 250 ms + e^(ln(900) + 0.8 * z)     z = a standard normal random number
 *
 * which gives roughly: 7% of fans under 0.55 s, a third under 0.95 s, half under 1.15 s.
 * So in a Mega rush (100 seats, about 150 served) you must click in about half a second.
 */
public final class ClickSpeed {

    static final long FASTEST_MS = 250;
    private static final double MEDIAN_EXTRA_MS = 900;
    private static final double SPREAD = 0.8;

    private ClickSpeed() {
    }

    /** One fan's click time in ms after opening, never above windowMs. */
    public static long sample(RandomGenerator random, long windowMs) {
        double extra = Math.exp(Math.log(MEDIAN_EXTRA_MS) + SPREAD * random.nextGaussian());
        return Math.min(windowMs, FASTEST_MS + Math.round(extra));
    }
}
