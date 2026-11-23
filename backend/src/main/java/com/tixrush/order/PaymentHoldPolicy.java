package com.tixrush.order;

import com.tixrush.config.OrderProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long a queued order waits for payment. Normally tixrush.orders.payment-hold for every event,
 * but the Live Rush demo gives its event a short hold so you can watch unpaid tickets come back.
 * The lookup is an in-memory map, so it adds no network trip to the hot buy path.
 */
@Component
public class PaymentHoldPolicy {

    private final OrderProperties props;
    private final Map<Long, Duration> overrides = new ConcurrentHashMap<>();

    public PaymentHoldPolicy(OrderProperties props) {
        this.props = props;
    }

    public Duration holdFor(Long eventId) {
        return overrides.getOrDefault(eventId, props.paymentHold());
    }

    public void override(Long eventId, Duration hold) {
        overrides.put(eventId, hold);
    }
}
