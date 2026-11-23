package com.tixrush.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background check: every 30 s, ask Stripe about payment pages that are still open.
 *
 * This is how a payment is never lost, even if you pay and then close the tab before coming back
 * (and even without webhooks, like on this laptop). Real payment systems always have a job like
 * this next to their webhooks, because webhooks can be late or go missing too.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentService paymentService;

    public PaymentReconciler(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 30_000)
    public void checkOpenPayments() {
        for (String sessionId : paymentService.openSessionsToCheck()) {
            try {
                paymentService.sync(sessionId);
            } catch (RuntimeException e) {
                log.warn("Background payment check failed for one session: {}", e.getMessage()); // next round retries
            }
        }
    }
}
