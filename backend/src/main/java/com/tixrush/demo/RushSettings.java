package com.tixrush.demo;

import java.time.Duration;
import java.util.List;

/**
 * Everything that shapes one Live Rush run. The scenarios in RushScenario fill these in;
 * tests pass a tiny, fast version.
 *
 * @param zones       what's on sale, like a real concert: seated zones (numbered seats) and standing zones
 * @param lobbyShare  part of the bot fans already waiting before the sale opens (they get random places)
 * @param buyChance   chance an admitted bot really tries to buy (the rest give up / leave)
 * @param payChance   chance a bot that got tickets pays (the rest let the hold run out)
 * @param lineStyle   random lobby (lottery) or fastest click (your number = your click speed)
 */
public record RushSettings(String scenario, String label, String eventName,
                           int fans, List<PracticeZone> zones, int admitPerSecond,
                           Duration lobby, Duration lateWindow, Duration pass, Duration hold,
                           double lobbyShare, double buyChance, double payChance,
                           Duration maxReaction, Duration minPayDelay, Duration maxPayDelay,
                           LineStyle lineStyle) {

    /** All tickets of all zones. */
    public int tickets() {
        return zones.stream().mapToInt(PracticeZone::tickets).sum();
    }
}
