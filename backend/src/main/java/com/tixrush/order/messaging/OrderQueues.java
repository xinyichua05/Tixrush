package com.tixrush.order.messaging;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * The queue names and shapes, in one place.
 *
 *   buy ──► [create] ──worker──► INSERT order ──► [hold.600s] ──(TTL runs out)──► [expired] ──worker──► unpaid? release ticket
 *              │ fails 3x                                                              │ fails 3x
 *              ▼                                                                       ▼
 *        [create.dlq]                                                           [expired.dlq]
 *
 * We send straight to queues through RabbitMQ's built-in "default exchange" (exchange name ""),
 * which routes a message to the queue whose name equals the routing key. Simple and enough here.
 */
public final class OrderQueues {

    public static final String CREATE = "tixrush.orders.create";
    public static final String CREATE_DLQ = "tixrush.orders.create.dlq";
    public static final String EXPIRED = "tixrush.orders.expired";
    public static final String EXPIRED_DLQ = "tixrush.orders.expired.dlq";
    private static final String HOLD_PREFIX = "tixrush.orders.hold.";

    private OrderQueues() {
    }

    /** "Dead letter queue": messages that failed 3 times are parked here so nothing is silently lost. */
    public static Queue create() {
        return QueueBuilder.durable(CREATE).deadLetterExchange("").deadLetterRoutingKey(CREATE_DLQ).build();
    }

    public static Queue expired() {
        return QueueBuilder.durable(EXPIRED).deadLetterExchange("").deadLetterRoutingKey(EXPIRED_DLQ).build();
    }

    /**
     * The payment timer. Nobody consumes this queue. Every message waits here for `seconds`
     * (x-message-ttl), then RabbitMQ "dead-letters" it into the EXPIRED queue.
     *
     * The duration is part of the name: RabbitMQ refuses to redeclare a queue with different
     * settings, so "hold.600s" and "hold.30s" are simply two different queues.
     */
    public static Queue hold(long seconds) {
        return QueueBuilder.durable(holdName(seconds))
                .ttl((int) Math.min(Integer.MAX_VALUE, seconds * 1000))
                .deadLetterExchange("")
                .deadLetterRoutingKey(EXPIRED)
                .build();
    }

    public static String holdName(long seconds) {
        return HOLD_PREFIX + seconds + "s";
    }
}
