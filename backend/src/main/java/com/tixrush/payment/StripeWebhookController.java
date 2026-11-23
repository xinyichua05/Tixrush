package com.tixrush.payment;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * POST /api/payments/stripe/webhook: Stripe calls this when something happens to a payment.
 *
 * Anyone on the internet could POST here, so there's no login: instead every message carries a
 * signature (Stripe-Signature header) made with a secret only Stripe and we know. A message whose
 * signature doesn't match is refused (400).
 *
 * We don't even trust the content of a correctly signed message: we take only the session id from it
 * and ask Stripe for the current state (PaymentService.sync), exactly like the background check does.
 * Same code path for all three ways of finding out, and a replayed old message changes nothing.
 *
 * Needs STRIPE_WEBHOOK_SECRET (whsec_...), which only matters once the site is on the internet.
 */
@RestController
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    private static final Set<String> HANDLED = Set.of("checkout.session.completed",
            "checkout.session.async_payment_succeeded", "checkout.session.async_payment_failed",
            "checkout.session.expired");

    private final PaymentService paymentService;
    private final PaymentProperties props;
    private final JsonMapper json;

    public StripeWebhookController(PaymentService paymentService, PaymentProperties props, JsonMapper json) {
        this.paymentService = paymentService;
        this.props = props;
        this.json = json;
    }

    @PostMapping("/api/payments/stripe/webhook")
    public ResponseEntity<Void> webhook(@RequestBody String payload,
                                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (props.webhookSecret().isBlank()) {
            return ResponseEntity.status(503).build(); // webhooks not set up here: the background check covers it
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, signature == null ? "" : signature, props.webhookSecret());
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook refused: bad signature");
            return ResponseEntity.badRequest().build();
        }
        if (HANDLED.contains(event.getType())) {
            String sessionId = json.readTree(payload).path("data").path("object").path("id").asString();
            paymentService.sync(sessionId);
        }
        return ResponseEntity.ok().build(); // 2xx = "got it": Stripe stops resending
    }
}
