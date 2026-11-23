package com.tixrush.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Stripe on or off, decided once at startup by whether STRIPE_SECRET_KEY is set.
 * The key itself is never logged, only "on (test mode)" / "off".
 */
@Configuration
@EnableScheduling // PaymentReconciler's background check
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfig.class);

    @Bean
    PaymentGateway paymentGateway(PaymentProperties props) {
        if (props.liveKey()) {
            throw new IllegalStateException("STRIPE_SECRET_KEY is a LIVE key (sk_live_). This project only uses "
                    + "Stripe test mode: use the sk_test_ key from the Stripe dashboard.");
        }
        if (!props.stripeOn()) {
            log.info("Payments: Stripe off (no STRIPE_SECRET_KEY). Orders use the test Pay button.");
            return new DisabledGateway();
        }
        log.info("Payments: Stripe on (test mode). Webhooks: {}",
                props.webhookSecret().isBlank() ? "off (the background check finds payments)" : "on");
        return new StripeGateway(props.stripeSecretKey());
    }

    /** No Stripe key: nothing is ever sent anywhere. */
    static final class DisabledGateway implements PaymentGateway {
        @Override public boolean enabled() { return false; }
        @Override public Checkout createCheckout(CheckoutRequest request) { throw new IllegalStateException("Stripe is off"); }
        @Override public Status retrieve(String sessionId) { throw new IllegalStateException("Stripe is off"); }
        @Override public void expire(String sessionId) { }
        @Override public void refund(String paymentIntentId) { throw new IllegalStateException("Stripe is off"); }
    }
}
