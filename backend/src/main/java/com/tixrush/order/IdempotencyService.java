package com.tixrush.order;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * "A double-click never creates two orders."
 *
 * The browser makes up a random Idempotency-Key per click of "Buy" and sends it as a header.
 * A double-click, a retry after a timeout, or a refresh all re-send the SAME key.
 *
 *   first request with key K  -> SET K "IN_PROGRESS" NX  (only the first one can win this)
 *                                -> buy -> SET K <the JSON answer> (kept 24h)
 *   any later request with K  -> don't buy again, just return the stored answer
 *
 * Like a coat-check ticket: showing the same ticket twice gets you the same coat, not two coats.
 * Keys are per user, so two users can't read each other's answers.
 */
@Service
public class IdempotencyService {

    public record Outcome(OrderResponse response, boolean replayed) {
    }

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(30);
    private static final Duration WAIT_FOR_FIRST = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public IdempotencyService(StringRedisTemplate redis, JsonMapper json,
                              @Value("${tixrush.idempotency.ttl:24h}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    public Outcome execute(Long userId, String idempotencyKey, Supplier<OrderResponse> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new Outcome(action.get(), false); // no key: plain request
        }
        if (idempotencyKey.length() > 100) {
            throw new BadRequestException("Invalid request. Please refresh the page and try again.");
        }
        String key = "tixrush:idem:" + userId + ":" + idempotencyKey;

        for (int attempt = 0; attempt < 2; attempt++) {
            Boolean first = redis.opsForValue().setIfAbsent(key, IN_PROGRESS, IN_PROGRESS_TTL);
            if (Boolean.TRUE.equals(first)) {
                return new Outcome(runAndRemember(key, action), false);
            }
            OrderResponse stored = waitForStoredAnswer(key);
            if (stored != null) {
                return new Outcome(stored, true);
            }
            // The first request failed (e.g. sold out) and removed the key: try once more ourselves.
        }
        throw new ConflictException("Something went wrong. Please try again.");
    }

    private OrderResponse runAndRemember(String key, Supplier<OrderResponse> action) {
        try {
            OrderResponse response = action.get();
            redis.opsForValue().set(key, json.writeValueAsString(response), ttl);
            return response;
        } catch (RuntimeException e) {
            redis.delete(key); // failed: the user may retry with the same key
            throw e;
        }
    }

    /** A double-click's 2nd request usually arrives while the 1st is still running: wait for its answer. */
    private OrderResponse waitForStoredAnswer(String key) {
        long deadline = System.nanoTime() + WAIT_FOR_FIRST.toNanos();
        while (true) {
            String value = redis.opsForValue().get(key);
            if (value == null) {
                return null;
            }
            if (!IN_PROGRESS.equals(value)) {
                return json.readValue(value, OrderResponse.class);
            }
            if (System.nanoTime() > deadline) {
                throw new ConflictException("We're still working on your last click. Please wait a moment.");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConflictException("Something went wrong. Please try again.");
            }
        }
    }
}
