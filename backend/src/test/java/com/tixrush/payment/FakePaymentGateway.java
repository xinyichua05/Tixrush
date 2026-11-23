package com.tixrush.payment;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stripe, faked for tests: no network. The test decides when someone "pays" (pay(sessionId)),
 * and can check what we asked Stripe to do (created / expired / refunded).
 */
public class FakePaymentGateway implements PaymentGateway {

    private final AtomicInteger next = new AtomicInteger();
    private final Map<String, Checkout> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    public final List<CheckoutRequest> created = new CopyOnWriteArrayList<>();
    public final List<String> expired = new CopyOnWriteArrayList<>();
    public final List<String> refunded = new CopyOnWriteArrayList<>();

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Checkout createCheckout(CheckoutRequest request) {
        // Like Stripe: the same idempotency key gives back the same session.
        return byIdempotencyKey.computeIfAbsent(request.idempotencyKey(), k -> {
            created.add(request);
            String id = "cs_test_" + next.incrementAndGet();
            statuses.put(id, new Status(false, false, null));
            return new Checkout(id, "https://checkout.stripe.test/" + id);
        });
    }

    @Override
    public Status retrieve(String sessionId) {
        return statuses.getOrDefault(sessionId, new Status(false, false, null));
    }

    @Override
    public void expire(String sessionId) {
        expired.add(sessionId);
        statuses.computeIfPresent(sessionId, (k, s) -> s.paid() ? s : new Status(false, true, null));
    }

    @Override
    public void refund(String paymentIntentId) {
        refunded.add(paymentIntentId);
    }

    /** The buyer pays on "Stripe's page". */
    public void pay(String sessionId) {
        statuses.put(sessionId, new Status(true, false, "pi_" + sessionId));
    }

    public void reset() {
        byIdempotencyKey.clear();
        statuses.clear();
        created.clear();
        expired.clear();
        refunded.clear();
    }
}
