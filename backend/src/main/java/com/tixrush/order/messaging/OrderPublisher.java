package com.tixrush.order.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends OrderRequested messages and WAITS until RabbitMQ confirms it has them.
 *
 * Without the confirm, "send" only means "handed to the network". If the broker crashed a
 * millisecond later, the buyer would have a 202 "you're in the queue" and no order would ever
 * appear. With publisher confirms + "mandatory", we only answer 202 after RabbitMQ says
 * "stored in a queue". The cost is one network round trip.
 */
@Component
public class OrderPublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbit;
    private final AmqpAdmin admin;
    private final Set<Long> declaredHoldQueues = ConcurrentHashMap.newKeySet();

    public OrderPublisher(RabbitTemplate rabbit, AmqpAdmin admin) {
        this.rabbit = rabbit;
        this.admin = admin;
    }

    /** Step 1: "please create this order". */
    public void sendToCreateQueue(OrderRequested message) {
        sendConfirmed(OrderQueues.CREATE, message);
    }

    /** Step 2: start the payment timer. The hold queue is declared on first use for this duration. */
    public void sendToHoldQueue(OrderRequested message) {
        ensureHoldQueue(message.holdSeconds());
        sendConfirmed(OrderQueues.holdName(message.holdSeconds()), message);
    }

    /**
     * Declares the hold queue for this duration once (a no-op if it already exists).
     * Declaring on CloudAMQP takes a moment, so the Live Rush demo calls this before the sale opens.
     */
    public void ensureHoldQueue(long seconds) {
        if (declaredHoldQueues.add(seconds)) {
            try {
                admin.declareQueue(OrderQueues.hold(seconds)); // no-op if it already exists
            } catch (RuntimeException e) {
                declaredHoldQueues.remove(seconds);
                throw e;
            }
        }
    }

    private void sendConfirmed(String queue, OrderRequested message) {
        CorrelationData correlation = new CorrelationData(message.reservationId().toString());
        rabbit.convertAndSend("", queue, message, correlation); // "" = default exchange: routes by queue name
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!confirm.ack()) {
                throw new AmqpException("RabbitMQ refused the message: " + confirm.reason());
            }
            if (correlation.getReturned() != null) { // "mandatory": no queue took it
                throw new AmqpException("No queue named " + queue);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for RabbitMQ", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AmqpException("No confirm from RabbitMQ within " + CONFIRM_TIMEOUT_SECONDS + "s", e);
        }
    }
}
