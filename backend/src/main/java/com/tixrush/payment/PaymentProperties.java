package com.tixrush.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * tixrush.payments.*
 *
 * @param stripeSecretKey  sk_test_... from the Stripe dashboard (env STRIPE_SECRET_KEY). Empty = Stripe off:
 *                         every order keeps the instant test "Pay now" button, like before.
 * @param webhookSecret    whsec_... for checking that a webhook really comes from Stripe (env STRIPE_WEBHOOK_SECRET).
 *                         Only needed once the site is on the internet; empty = the webhook endpoint answers 503.
 * @param frontendUrl      where Stripe sends the buyer back to after paying
 * @param currency         "myr" = Malaysian ringgit
 */
@ConfigurationProperties("tixrush.payments")
public record PaymentProperties(@DefaultValue("") String stripeSecretKey,
                                @DefaultValue("") String webhookSecret,
                                @DefaultValue("http://localhost:5173") String frontendUrl,
                                @DefaultValue("myr") String currency) {

    public boolean stripeOn() {
        return stripeSecretKey != null && !stripeSecretKey.isBlank();
    }

    /** A live key (sk_live_) would take real money. Not allowed in this project. */
    public boolean liveKey() {
        return stripeOn() && stripeSecretKey.trim().startsWith("sk_live_");
    }
}
