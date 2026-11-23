package com.tixrush.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.order.messaging.OrderQueues;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only helpers for load testing (protected by the /api/admin/** rule in SecurityConfig).
 *
 * "oversold" = orders that exist beyond the tickets the stock counter gave out.
 * The DB counter is used by naive/pessimistic/optimistic, the Redis counter by redis,
 * so we take whichever one recorded more sales:
 *   oversold = activeOrders - max(soldByDb, soldByRedis)     (active = not EXPIRED or CANCELLED)
 * 0 means every order is backed by a real ticket.
 */
/** Developer tool: only exists when tixrush.dev-tools.enabled=true. */
@ConditionalOnProperty(name = "tixrush.dev-tools.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/admin")
public class BenchmarkAdminController {

    /**
     * ordersCount = orders that still hold a seat (not EXPIRED or CANCELLED).
     * queueBacklog = messages waiting in tixrush.orders.create (null if RabbitMQ is unreachable).
     */
    public record PurchaseStats(Long eventId, int totalTickets, int availableTickets, Integer redisStock,
                                long ordersCount, long pendingPayment, long paid, long expired,
                                Long queueBacklog, long oversold, long optimisticRetries) {
    }

    public record RedisPing(int samples, double avgMillis, double minMillis, double maxMillis) {
    }

    private final EventRepository eventRepository;
    private final OrderRepository orderRepository;
    private final OptimisticPurchaseStrategy optimisticStrategy;
    private final RedisStockService redisStock;
    private final StringRedisTemplate redis;
    private final AmqpAdmin amqpAdmin;

    public BenchmarkAdminController(EventRepository eventRepository, OrderRepository orderRepository,
                                    OptimisticPurchaseStrategy optimisticStrategy, RedisStockService redisStock,
                                    StringRedisTemplate redis, AmqpAdmin amqpAdmin) {
        this.eventRepository = eventRepository;
        this.orderRepository = orderRepository;
        this.optimisticStrategy = optimisticStrategy;
        this.redisStock = redisStock;
        this.redis = redis;
        this.amqpAdmin = amqpAdmin;
    }

    @GetMapping("/events/{eventId}/stats")
    @Transactional(readOnly = true)
    public PurchaseStats stats(@PathVariable Long eventId) {
        Event event = find(eventId);
        long activeOrders = orderRepository.countByEventIdAndStatusNotIn(eventId, Order.RELEASED);
        Integer stockInRedis = redisStock.getStock(eventId);

        long soldByDb = event.getTotalTickets() - event.getAvailableTickets();
        long soldByRedis = stockInRedis == null ? 0 : event.getTotalTickets() - stockInRedis;
        // With the queue, Redis runs AHEAD of the DB for a moment (orders still in the queue),
        // which would make this negative. Negative just means "not caught up yet", so clamp to 0.
        long oversold = Math.max(0, activeOrders - Math.max(soldByDb, soldByRedis));

        return new PurchaseStats(eventId, event.getTotalTickets(), event.getAvailableTickets(), stockInRedis,
                activeOrders,
                orderRepository.countByEventIdAndStatus(eventId, Order.PENDING_PAYMENT),
                orderRepository.countByEventIdAndStatus(eventId, Order.PAID),
                orderRepository.countByEventIdAndStatusIn(eventId, Order.RELEASED),
                queueBacklog(), oversold, optimisticStrategy.retriesSinceReset());
    }

    @PostMapping("/events/{eventId}/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void reset(@PathVariable Long eventId) {
        purgeCreateQueue();                  // drop not-yet-processed orders (dev tool: affects all events)
        orderRepository.deleteByEventId(eventId);
        find(eventId).resetStock();
        redisStock.clear(eventId);           // next redis purchase reloads the (reset) stock
        optimisticStrategy.resetRetries();
    }

    private Long queueBacklog() {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(OrderQueues.CREATE);
            return info == null ? null : info.getMessageCount();
        } catch (RuntimeException e) {
            return null; // RabbitMQ not reachable: stats still work for the other versions
        }
    }

    private void purgeCreateQueue() {
        try {
            amqpAdmin.purgeQueue(OrderQueues.CREATE, false);
        } catch (RuntimeException ignored) {
            // RabbitMQ not reachable: nothing queued to purge anyway
        }
    }

    /** How long one round trip to Redis takes (laptop -> Upstash -> laptop). */
    @GetMapping("/redis/ping")
    public RedisPing redisPing() {
        int samples = 20;
        double total = 0, min = Double.MAX_VALUE, max = 0;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            redis.execute((RedisCallback<String>) connection -> connection.ping());
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            total += ms;
            min = Math.min(min, ms);
            max = Math.max(max, ms);
        }
        return new RedisPing(samples, round(total / samples), round(min), round(max));
    }

    private static double round(double ms) {
        return Math.round(ms * 10) / 10.0;
    }

    private Event find(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event " + eventId + " not found"));
    }
}
