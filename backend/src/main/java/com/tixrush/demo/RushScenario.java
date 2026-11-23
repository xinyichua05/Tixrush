package com.tixrush.demo;

import java.time.Duration;
import java.util.List;

import static com.tixrush.demo.PracticeZone.seated;
import static com.tixrush.demo.PracticeZone.standing;

/**
 * The three scenarios you can pick on the Practice rush page. Each one sells real-looking zones:
 * numbered seats you can pick on a seat map, and standing tickets.
 */
public enum RushScenario {

    /** Way more fans than seats: you'll usually miss out. 100 tickets. */
    MEGA("Mega star", 2000, 50, List.of(
            seated("VIP", "#eab308", 1288, 2, 10),
            seated("CAT 1", "#2563eb", 688, 4, 10),
            standing("Rock Zone", "#e11d48", 488, 40))),
    /** About 4 fans per ticket: a coin flip, plus second chances from unpaid holds. 150 tickets. */
    POPULAR("Popular show", 600, 25, List.of(
            seated("VIP", "#eab308", 988, 3, 10),
            seated("CAT 1", "#2563eb", 588, 6, 10),
            standing("Rock Zone", "#e11d48", 388, 60))),
    /** More tickets than fans: everyone who tries gets in. 150 tickets. */
    INDIE("Indie night", 120, 20, List.of(
            seated("Front seats", "#7c3aed", 258, 5, 10),
            standing("Standing", "#16a34a", 158, 100)));

    public static final String DEMO_EVENT_NAME = "Stellar Eclipse: World Tour 2027";

    private final String label;
    private final int fans;
    private final int admitPerSecond;
    private final List<PracticeZone> zones;

    RushScenario(String label, int fans, int admitPerSecond, List<PracticeZone> zones) {
        this.label = label;
        this.fans = fans;
        this.admitPerSecond = admitPerSecond;
        this.zones = zones;
    }

    public RushSettings settings(LineStyle lineStyle) {
        boolean fastestClick = lineStyle == LineStyle.FASTEST_CLICK;
        return new RushSettings(name(), label, DEMO_EVENT_NAME, fans, zones, admitPerSecond,
                Duration.ofSeconds(8),    // lobby countdown before the sale opens
                Duration.ofSeconds(15),   // late fans keep arriving for this long after opening
                Duration.ofSeconds(90),   // your turn: this long to choose a zone / seats and buy
                Duration.ofSeconds(30),   // payment hold after you got tickets
                fastestClick ? 0 : 0.70,  // fastest click: nobody can be in line before it opens
                0.85, 0.80,
                Duration.ofMillis(4000),  // bots react within 0.2 - 4 s of their turn
                Duration.ofSeconds(3), Duration.ofSeconds(15), // bots that pay do it after 3 - 15 s
                lineStyle);
    }
}
