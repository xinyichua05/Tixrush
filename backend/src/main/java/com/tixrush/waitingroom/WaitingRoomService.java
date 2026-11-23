package com.tixrush.waitingroom;

import com.tixrush.common.ForbiddenException;
import com.tixrush.order.OrderResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A virtual waiting room, like the "You are in line" page of a big ticket site.
 * Everything lives in Redis, so several app servers would share ONE line.
 *
 *   tixrush:wr:{eventId}:line            sorted set: member = fan, score = place in line (lowest = front)
 *   tixrush:wr:{eventId}:pass:{member}   "you may buy now", deletes itself after the pass time
 *
 * The scores do the fairness:
 *   - fans already waiting before the sale opens get a RANDOM score in [0, 1): a lottery, so refreshing
 *     early or having a faster connection gives no advantage
 *   - fans who arrive after opening get 1 + (ms since opening): always behind the lobby, first come first served
 *
 * Buying a ticket for an event with an active waiting room needs a pass (checked in PurchaseController).
 */
@Service
public class WaitingRoomService {

    private static final RedisScript<List> ADMIT =
            RedisScript.of(new ClassPathResource("redis/admit_batch.lua"), List.class);

    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    /** In-memory on purpose: normal events skip the waiting-room check without any Redis call. */
    private final Set<Long> activeEvents = ConcurrentHashMap.newKeySet();

    public WaitingRoomService(StringRedisTemplate redis, ApplicationEventPublisher events) {
        this.redis = redis;
        this.events = events;
    }

    /** Published after someone bought a ticket with their pass (the Live Rush demo listens for it). */
    public record PassUsed(Long eventId, String member, OrderResponse order) {
    }

    public static String userMember(Long userId) {
        return "u:" + userId;
    }

    public static String botMember(int n) {
        return "bot:" + n;
    }

    // ---- turning the gate on/off ----

    public void activate(Long eventId) {
        activeEvents.add(eventId);
    }

    public void deactivate(Long eventId) {
        activeEvents.remove(eventId);
    }

    public boolean isActive(Long eventId) {
        return activeEvents.contains(eventId);
    }

    // ---- the line ----

    /** Adds many fans in ONE round trip (ZADD with many members). Scores: see the class comment. */
    public void addAll(Long eventId, Map<String, Double> memberScores) {
        if (memberScores.isEmpty()) {
            return;
        }
        Set<TypedTuple<String>> tuples = new HashSet<>();
        memberScores.forEach((member, score) -> tuples.add(TypedTuple.of(member, score)));
        redis.opsForZSet().add(lineKey(eventId), tuples);
    }

    public void add(Long eventId, String member, double score) {
        redis.opsForZSet().add(lineKey(eventId), member, score);
    }

    /** How many fans are in front of this one (0 = you're next), or null if not in line. */
    public Long ahead(Long eventId, String member) {
        return redis.opsForZSet().rank(lineKey(eventId), member);
    }

    public long size(Long eventId) {
        Long size = redis.opsForZSet().zCard(lineKey(eventId));
        return size == null ? 0 : size;
    }

    /** Lets the next `count` fans in and gives each a pass. Returns them, front of the line first. */
    @SuppressWarnings("unchecked")
    public List<String> admit(Long eventId, int count, Duration passTime) {
        List<String> admitted = redis.execute(ADMIT, List.of(lineKey(eventId)),
                String.valueOf(count), passPrefix(eventId), String.valueOf(passTime.toSeconds()));
        return admitted == null ? List.of() : admitted;
    }

    // ---- passes ----

    public boolean hasPass(Long eventId, String member) {
        return Boolean.TRUE.equals(redis.hasKey(passPrefix(eventId) + member));
    }

    /** Throws 403 unless this user is allowed to buy now. Normal events (no waiting room) always pass. */
    public void checkPass(Long eventId, Long userId) {
        if (isActive(eventId) && !hasPass(eventId, userMember(userId))) {
            throw new ForbiddenException("It's not your turn yet. Please wait in the line.");
        }
    }

    /** One pass = one ticket: delete it after a successful buy, and tell whoever is listening. */
    public void passUsed(Long eventId, Long userId, OrderResponse order) {
        if (!isActive(eventId)) {
            return;
        }
        String member = userMember(userId);
        redis.delete(passPrefix(eventId) + member);
        events.publishEvent(new PassUsed(eventId, member, order));
    }

    /** Empties the line and removes the passes of the given members (other passes expire on their own). */
    public void clear(Long eventId, Collection<String> members) {
        redis.delete(lineKey(eventId));
        if (!members.isEmpty()) {
            redis.delete(members.stream().map(m -> passPrefix(eventId) + m).toList());
        }
    }

    private static String lineKey(Long eventId) {
        return "tixrush:wr:" + eventId + ":line";
    }

    private static String passPrefix(Long eventId) {
        return "tixrush:wr:" + eventId + ":pass:";
    }
}
