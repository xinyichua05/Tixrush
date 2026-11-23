package com.tixrush.payment;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.order.Order;
import com.tixrush.order.OrderRepository;
import com.tixrush.seat.SeatLabel;
import com.tixrush.seat.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Real payments, start to finish:
 *
 *   1. startCheckout  Pay now -> we ask Stripe for a payment page for THIS order and send you there
 *   2. sync           "Was it paid?" -> asks Stripe, then PENDING_PAYMENT -> PAID (or refunds, see below).
 *                     Three things call it, and it's safe to call any number of times:
 *                       - you, coming back from Stripe          (POST /api/orders/{id}/payment/check)
 *                       - the background check, every 30 s       (PaymentReconciler: you closed the tab)
 *                       - Stripe's webhook, once the site is online (StripeWebhookController)
 *   3. closeOpen      the order expired or was cancelled -> close its Stripe page, so nobody pays for
 *                     tickets that went back on sale
 *
 * Money rule: if a payment lands for an order that can no longer be paid (expired, cancelled, or
 * already paid on another page), we REFUND it at once. We never keep money for tickets you don't get.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    /** Stripe's shortest allowed lifetime for a payment page. Our own hold is shorter: we close it ourselves. */
    private static final Duration STRIPE_MIN_LIFETIME = Duration.ofMinutes(31);

    public static final String PAY_WITH_STRIPE = "STRIPE", PAY_WITH_TEST = "TEST";

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final PaymentProperties props;
    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final SeatRepository seatRepository;
    private final TransactionTemplate tx;

    public PaymentService(PaymentGateway gateway, PaymentRepository payments, PaymentProperties props,
                          OrderRepository orderRepository, EventRepository eventRepository,
                          TicketTypeRepository ticketTypeRepository, SeatRepository seatRepository,
                          TransactionTemplate tx) {
        this.gateway = gateway;
        this.payments = payments;
        this.props = props;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.seatRepository = seatRepository;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ which button

    /**
     * STRIPE: a real order with a price, and Stripe is on.
     * TEST:   the instant test button: Stripe off, Practice rush tickets (not real), or an old
     *         single-pool order (no price to charge).
     */
    public String payWith(Order order) {
        if (!gateway.enabled() || order.getUnitPrice() == null || order.getReservationId() == null) {
            return PAY_WITH_TEST;
        }
        return payWith(order, eventRepository.findById(order.getEventId()).map(Event::isPractice).orElse(false));
    }

    /** Same, when the caller already knows whether it's a practice order (saves a DB read per order in lists). */
    public String payWith(Order order, boolean practice) {
        boolean real = gateway.enabled() && order.getUnitPrice() != null && order.getReservationId() != null && !practice;
        return real ? PAY_WITH_STRIPE : PAY_WITH_TEST;
    }

    public boolean wasRefunded(UUID reservationId) {
        return gateway.enabled() && payments.wasRefunded(reservationId);
    }

    // ------------------------------------------------------------------ 1. start

    /** Pay now: returns the Stripe page to send you to (the same one again if you click twice). */
    public String startCheckout(UUID reservationId, Long userId) {
        Order order = orderRepository.findByReservationIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that order."));
        if (!PAY_WITH_STRIPE.equals(payWith(order))) {
            throw new BadRequestException("This order uses the test Pay button.");
        }
        if (!Order.PENDING_PAYMENT.equals(order.getStatus())) {
            throw new ConflictException(Order.PAID.equals(order.getStatus())
                    ? "This order is already paid." : "This order can't be paid any more.");
        }
        if (order.getExpiresAt() != null && !order.getExpiresAt().isAfter(Instant.now())) {
            throw new ConflictException("Too late: the time to pay has run out.");
        }
        var open = payments.findOpen(reservationId);
        if (open.isPresent()) {
            return open.get().checkoutUrl(); // you already have a Stripe page for this order: go back to it
        }

        long unitSen = order.getUnitPrice().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        String event = eventRepository.findById(order.getEventId()).map(Event::getName).orElse("TixRush");
        String type = order.getTicketTypeId() == null ? "Ticket"
                : ticketTypeRepository.findById(order.getTicketTypeId()).map(t -> t.getName()).orElse("Ticket");
        String back = props.frontendUrl().replaceAll("/+$", "") + "/orders/" + reservationId;
        int attempt = payments.countAttempts(reservationId) + 1;

        PaymentGateway.Checkout checkout = gateway.createCheckout(new PaymentGateway.CheckoutRequest(
                reservationId, type + " · " + event, seatsText(reservationId), unitSen, order.getQuantity(),
                props.currency(), back + "?paid=1", back, Instant.now().plus(STRIPE_MIN_LIFETIME),
                "checkout-" + reservationId + "-" + attempt));
        payments.insert(reservationId, checkout.sessionId(), checkout.url(), unitSen * order.getQuantity(), props.currency());
        log.info("Payment page opened for order {} ({} sen)", reservationId, unitSen * order.getQuantity());
        return checkout.url();
    }

    /** "Seats A24, A25" on Stripe's page, so you can see what you're paying for. */
    private String seatsText(UUID reservationId) {
        List<SeatLabel> seats = seatRepository.labelsFor(List.of(reservationId)).getOrDefault(reservationId, List.of());
        return seats.isEmpty() ? null
                : "Seats " + seats.stream().map(s -> s.row() + s.number()).collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ 2. was it paid?

    /** Back from Stripe: check every open payment page of your order now (no need to wait for the background check). */
    public void checkMine(UUID reservationId, Long userId) {
        orderRepository.findByReservationIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new NotFoundException("We couldn't find that order."));
        if (!gateway.enabled()) {
            return;
        }
        payments.findByReservation(reservationId).stream()
                .filter(p -> PaymentRepository.OPEN.equals(p.status()))
                .forEach(p -> sync(p.sessionId()));
    }

    /**
     * Asks Stripe about one payment page and acts on the answer. Idempotent: the payment row is
     * LOCKED while we decide, and a finished payment (PAID / EXPIRED / REFUNDED) is never touched again,
     * so the return check, the background check and the webhook can all call this at the same time.
     */
    public void sync(String sessionId) {
        if (!gateway.enabled() || !payments.exists(sessionId)) {
            return; // not one of ours (e.g. a webhook for another app on the same Stripe account)
        }
        PaymentGateway.Status status = gateway.retrieve(sessionId); // network: before we lock anything
        if (!status.paid() && !status.expired()) {
            return; // still open: nothing to do yet
        }
        tx.executeWithoutResult(t -> {
            var row = payments.lockBySession(sessionId);
            if (row.isEmpty() || !PaymentRepository.OPEN.equals(row.get().status())) {
                return; // someone else already finished it
            }
            PaymentRepository.Payment p = row.get();
            if (!status.paid()) {
                payments.finish(sessionId, PaymentRepository.EXPIRED, null);
                return;
            }
            Order order = orderRepository.findByReservationId(p.reservationId()).orElse(null);
            // The same conditional UPDATE as the old test button: PENDING_PAYMENT -> PAID, only before expires_at.
            boolean ours = order != null && orderRepository.payIfPending(p.reservationId(), order.getUserId(), Instant.now()) == 1;
            if (ours) {
                payments.finish(sessionId, PaymentRepository.PAID, status.paymentIntentId());
                log.info("Order {} paid with Stripe", p.reservationId());
            } else {
                // Too late (expired / cancelled) or already paid on another page: the money goes straight back.
                gateway.refund(status.paymentIntentId());
                payments.finish(sessionId, PaymentRepository.REFUNDED, status.paymentIntentId());
                log.warn("Order {}: payment arrived but the order is {} -> refunded", p.reservationId(),
                        order == null ? "gone" : order.getStatus());
            }
        });
    }

    // ------------------------------------------------------------------ 3. close

    /**
     * The order expired or was cancelled: close its open Stripe pages once the DB change is committed
     * (a rollback must not close a page that's still valid). Then check them once more: if someone
     * managed to pay in the last second, sync refunds it.
     */
    public void closeOpenAfterCommit(UUID reservationId) {
        if (!gateway.enabled() || reservationId == null) {
            return;
        }
        Runnable close = () -> {
            try {
                for (PaymentRepository.Payment p : payments.findByReservation(reservationId)) {
                    if (PaymentRepository.OPEN.equals(p.status())) {
                        gateway.expire(p.sessionId());
                        sync(p.sessionId());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Could not close the payment pages of order {}: {} (the background check will retry)",
                        reservationId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    close.run();
                }
            });
        } else {
            close.run();
        }
    }

    // ------------------------------------------------------------------ background

    /** For PaymentReconciler: the open pages worth asking Stripe about. */
    public List<String> openSessionsToCheck() {
        return gateway.enabled() ? payments.openSessionsOlderThan(Duration.ofSeconds(20), 50) : List.of();
    }
}
