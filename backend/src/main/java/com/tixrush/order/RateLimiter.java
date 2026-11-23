package com.tixrush.order;

import com.tixrush.common.RateLimitedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * "Max N purchase attempts per user per minute", counted in Redis.
 *
 * Fixed window: every clock minute has its own counter key. 10:00:00–10:00:59 is one
 * window, and the count restarts at 10:01:00. Simple and cheap.
 * Weakness: a user can do 5 at 10:00:59 and 5 more at 10:01:00 (10 within 2 seconds).
 * A "sliding window" fixes that, at the cost of more Redis work.
 *
 * Why in Redis and not a HashMap in Java? With several app servers behind a load
 * balancer, each would have its own HashMap. Redis is shared by all of them.
 */
@Component
public class RateLimiter {

    private static final RedisScript<Long> RATE_LIMIT =
            RedisScript.of(new ClassPathResource("redis/rate_limit.lua"), Long.class);
    private static final int WINDOW_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final int buysPerMinute;

    public RateLimiter(StringRedisTemplate redis, @Value("${tixrush.rate-limit.buys-per-minute:5}") int buysPerMinute) {
        this.redis = redis;
        this.buysPerMinute = buysPerMinute;
    }

    /** Throws RateLimitedException (HTTP 429) when the user is over the limit. */
    public void checkBuyAllowed(Long userId) {
        if (buysPerMinute <= 0) {
            return; // disabled (bench profile)
        }
        long now = Instant.now().getEpochSecond();
        long window = now / WINDOW_SECONDS;
        String key = "tixrush:ratelimit:buy:" + userId + ":" + window;

        Long count = redis.execute(RATE_LIMIT, List.of(key), String.valueOf(WINDOW_SECONDS));
        if (count != null && count > buysPerMinute) {
            long secondsUntilNextWindow = WINDOW_SECONDS - (now % WINDOW_SECONDS);
            throw new RateLimitedException(secondsUntilNextWindow);
        }
    }
}
