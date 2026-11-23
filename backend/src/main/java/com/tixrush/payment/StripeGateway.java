package com.tixrush.payment;

import com.stripe.StripeClient;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.tixrush.common.TooBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real thing: Stripe Checkout (Stripe's own payment page). With a test key (sk_test_...) no real
 * money moves; you pay with test cards like 4242 4242 4242 4242.
 *
 * The card number is typed on Stripe's page, never on ours, so card data never reaches our server.
 */
public class StripeGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

    private final StripeClient stripe;

    public StripeGateway(String secretKey) {
        this.stripe = new StripeClient(secretKey.trim());
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Checkout createCheckout(CheckoutRequest r) {
        SessionCreateParams.LineItem.PriceData.ProductData.Builder product =
                SessionCreateParams.LineItem.PriceData.ProductData.builder().setName(r.name());
        if (r.description() != null && !r.description().isBlank()) {
            product.setDescription(r.description());
        }
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setClientReferenceId(r.reservationId().toString()) // so every payment can be traced to its order
                .putMetadata("reservationId", r.reservationId().toString())
                .setSuccessUrl(r.successUrl())
                .setCancelUrl(r.cancelUrl())
                .setExpiresAt(r.expiresAt().getEpochSecond())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity((long) r.quantity())
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(r.currency())
                                .setUnitAmount(r.unitAmountSen())
                                .setProductData(product.build())
                                .build())
                        .build())
                .build();
        try {
            // Same idempotency key = same session: a double click (or a retry after a timeout) can't
            // create two payment pages for one order.
            Session session = stripe.v1().checkout().sessions().create(params,
                    RequestOptions.builder().setIdempotencyKey(r.idempotencyKey()).build());
            return new Checkout(session.getId(), session.getUrl());
        } catch (StripeException e) {
            log.warn("Stripe: could not create a checkout session: {}", e.getMessage());
            throw new TooBusyException("We couldn't reach the payment provider. Please try again.");
        }
    }

    @Override
    public Status retrieve(String sessionId) {
        try {
            Session s = stripe.v1().checkout().sessions().retrieve(sessionId);
            // "paid" also covers delayed methods (FPX online banking) once the bank confirms.
            return new Status("paid".equals(s.getPaymentStatus()), "expired".equals(s.getStatus()), s.getPaymentIntent());
        } catch (StripeException e) {
            throw new TooBusyException("We couldn't reach the payment provider. Please try again.");
        }
    }

    @Override
    public void expire(String sessionId) {
        try {
            stripe.v1().checkout().sessions().expire(sessionId);
        } catch (InvalidRequestException e) {
            // Already expired, or already paid: nothing to close. (A paid one is handled by sync -> refund.)
            log.debug("Stripe: session {} not expired: {}", sessionId, e.getMessage());
        } catch (StripeException e) {
            log.warn("Stripe: could not expire session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void refund(String paymentIntentId) {
        try {
            stripe.v1().refunds().create(RefundCreateParams.builder().setPaymentIntent(paymentIntentId).build(),
                    RequestOptions.builder().setIdempotencyKey("refund-" + paymentIntentId).build());
        } catch (StripeException e) {
            throw new TooBusyException("We couldn't reach the payment provider to refund. We'll try again.");
        }
    }
}
