package com.tixrush.order;

import org.springframework.context.annotation.Import;
import com.tixrush.RequiresServices;
import com.tixrush.TestcontainersConfiguration;

import com.tixrush.common.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5 attempts per minute are fine, and the 6th is refused. Needs real Redis. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@RequiresServices
class RateLimiterTest {

    @Autowired
    RateLimiter rateLimiter;

    @Test
    void sixthAttemptInSameMinute_isRejected() throws Exception {
        // Avoid starting right before a minute boundary, where the window would reset mid-test.
        if (Instant.now().getEpochSecond() % 60 >= 55) {
            Thread.sleep(6_000);
        }
        // A made-up user id nobody else uses, so earlier runs don't interfere.
        long userId = -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

        for (int i = 1; i <= 5; i++) {
            rateLimiter.checkBuyAllowed(userId); // must not throw
        }

        assertThatThrownBy(() -> rateLimiter.checkBuyAllowed(userId))
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> assertThat(((RateLimitedException) e).getRetryAfterSeconds()).isBetween(1L, 60L));
    }
}
