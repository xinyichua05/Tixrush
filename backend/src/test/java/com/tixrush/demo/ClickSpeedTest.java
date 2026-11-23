package com.tixrush.demo;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** The virtual fans' click speeds look human: nobody under 0.25 s, most around a second, a slow tail. */
class ClickSpeedTest {

    @Test
    void clickTimesHaveAHumanShape() {
        SplittableRandom random = new SplittableRandom(42); // fixed seed: same numbers every run
        long[] samples = new long[10_000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = ClickSpeed.sample(random, 15_000);
        }
        Arrays.sort(samples);

        assertThat(samples[0]).isGreaterThanOrEqualTo(ClickSpeed.FASTEST_MS);
        assertThat(samples[samples.length - 1]).isLessThanOrEqualTo(15_000);
        assertThat(samples[samples.length / 2]).as("median").isBetween(1_000L, 1_400L);
        long under550 = Arrays.stream(samples).filter(ms -> ms < 550).count();
        assertThat(under550 / (double) samples.length).as("very fast clickers").isBetween(0.04, 0.12);
    }
}
