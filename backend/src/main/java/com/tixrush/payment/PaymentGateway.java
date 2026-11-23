package com.tixrush.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything TixRush needs from a payment provider, in four calls. StripeGateway does them for real;
 * tests use a fake, so they never touch the network. (And swapping Stripe for another provider
 * would only mean a new class behind this interface.)
 */
public interface PaymentGateway {

    /** What to charge for: "2 × CAT 1 · Neon Monsoon", RM 688.00 each = 68800 sen. */
    record CheckoutRequest(UUID reservationId, String name, String description, long unitAmountSen, int quantity,
                           String currency, String successUrl, String cancelUrl, Instant expiresAt,
                           String idempotencyKey) {
    }

    /** The provider's id for this payment page, and its address to send the buyer to. */
    record Checkout(String sessionId, String url) {
    }

    /** What the provider says now. paid = the money is really there. */
    record Status(boolean paid, boolean expired, String paymentIntentId) {
    }

    /** False when no key is set: nothing goes to Stripe, orders keep the test Pay button. */
    boolean enabled();

    Checkout createCheckout(CheckoutRequest request);

    Status retrieve(String sessionId);

    /** Close a payment page so nobody can pay on it any more. Harmless if it's already closed or paid. */
    void expire(String sessionId);

    /** Give the money back (all of it). */
    void refund(String paymentIntentId);
}
