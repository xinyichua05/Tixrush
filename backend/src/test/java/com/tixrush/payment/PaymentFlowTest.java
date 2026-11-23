package com.tixrush.payment;

import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.CreateEventRequest;
import com.tixrush.event.EventResponse;
import com.tixrush.event.EventService;
import com.tixrush.event.TicketTypeInput;
import com.tixrush.order.Order;
import com.tixrush.order.OrderRepository;
import com.tixrush.order.OrderService;
import com.tixrush.user.Role;
import com.tixrush.user.User;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stripe payments end to end, with a FAKE Stripe (no network, no key): opening the payment page,
 * finding out it was paid, refunding money that arrives too late, closing the page on cancel,
 * and the signed webhook. (Needs Redis for the cancel step, like the other order tests.)
 */
@SpringBootTest(properties = "tixrush.payments.webhook-secret=" + PaymentFlowTest.WEBHOOK_SECRET)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = "rediss?://.+")
class PaymentFlowTest {

    static final String WEBHOOK_SECRET = "whsec_test_secret";

    @TestConfiguration
    static class FakeStripe {
        @Bean
        @Primary
        FakePaymentGateway fakePaymentGateway() {
            return new FakePaymentGateway();
        }
    }

    @Autowired FakePaymentGateway stripe;
    @Autowired PaymentService payments;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired EventService eventService;
    @Autowired UserRepository userRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mockMvc;

    private Long eventId;
    private Long adultType;
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stripe.reset();
        Instant now = Instant.now();
        EventResponse event = eventService.createEvent(new CreateEventRequest("Payment Test Show", "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), null, null,
                List.of(new TicketTypeInput(null, "Adult", new BigDecimal("25.50"), 100))));
        eventId = event.id();
        adultType = event.ticketTypes().getFirst().id();
    }

    @AfterEach
    void cleanUp() {
        eventService.deleteEvent(eventId); // orders and their payments go too (ON DELETE CASCADE)
        userRepository.deleteAllById(users);
    }

    private Long newUser() {
        Long id = userRepository.save(new User("pay-" + UUID.randomUUID() + "@test.local", "x", Role.USER)).getId();
        users.add(id);
        return id;
    }

    /** A PENDING_PAYMENT order of 2 Adult tickets, written straight to the DB (no Redis/RabbitMQ needed). */
    private UUID pendingOrder(Long userId) {
        UUID rid = UUID.randomUUID();
        tx.executeWithoutResult(t -> orderRepository.insertPendingTicketsIfAbsent(rid, eventId, userId, "queue",
                Instant.now().plus(10, ChronoUnit.MINUTES), Instant.now(), adultType, 2));
        return rid;
    }

    private String sessionOf(UUID rid) {
        return paymentRepository.findByReservation(rid).getFirst().sessionId();
    }

    @Test
    void payNow_opensOneStripePage_forTheRightAmount_andAgainGivesTheSamePage() {
        Long fan = newUser();
        UUID rid = pendingOrder(fan);
        assertThat(orderService.myOrder(rid, fan).payWith()).isEqualTo("STRIPE");

        String url = payments.startCheckout(rid, fan);
        assertThat(payments.startCheckout(rid, fan)).as("a second click: same page").isEqualTo(url);

        assertThat(stripe.created).hasSize(1);
        PaymentGateway.CheckoutRequest r = stripe.created.getFirst();
        assertThat(r.unitAmountSen()).isEqualTo(2550);    // RM 25.50 in sen
        assertThat(r.quantity()).isEqualTo(2);
        assertThat(r.currency()).isEqualTo("myr");
        assertThat(r.name()).isEqualTo("Adult · Payment Test Show");
        assertThat(r.successUrl()).endsWith("/orders/" + rid + "?paid=1");
        assertThat(paymentRepository.findByReservation(rid).getFirst().amountSen()).isEqualTo(5100);
    }

    @Test
    void paidOnStripe_theOrderBecomesPaid_onceOnly() {
        Long fan = newUser();
        UUID rid = pendingOrder(fan);
        payments.startCheckout(rid, fan);
        payments.checkMine(rid, fan);
        assertThat(orderService.myOrder(rid, fan).status()).as("not paid yet").isEqualTo(Order.PENDING_PAYMENT);

        stripe.pay(sessionOf(rid));
        payments.checkMine(rid, fan); // back from Stripe
        payments.sync(sessionOf(rid)); // ...and the background check, a moment later: harmless

        assertThat(orderService.myOrder(rid, fan).status()).isEqualTo(Order.PAID);
        assertThat(paymentRepository.findByReservation(rid).getFirst().status()).isEqualTo(PaymentRepository.PAID);
        assertThat(stripe.refunded).isEmpty();
    }

    @Test
    void moneyThatArrivesTooLate_isRefunded() {
        Long fan = newUser();
        UUID rid = pendingOrder(fan);
        payments.startCheckout(rid, fan);
        // The 10 minutes run out while the fan is still on Stripe's page...
        jdbc.sql("update orders set expires_at = now() - interval '1 second' where reservation_id = :rid")
                .param("rid", rid).update();
        stripe.pay(sessionOf(rid)); // ...and then they pay.

        payments.sync(sessionOf(rid));

        assertThat(stripe.refunded).containsExactly("pi_" + sessionOf(rid));
        assertThat(paymentRepository.findByReservation(rid).getFirst().status()).isEqualTo(PaymentRepository.REFUNDED);
        assertThat(orderService.myOrder(rid, fan).status()).isNotEqualTo(Order.PAID);
        assertThat(orderService.myOrder(rid, fan).refunded()).isTrue();
    }

    @Test
    void cancellingTheOrder_closesItsStripePage() {
        Long fan = newUser();
        UUID rid = pendingOrder(fan);
        payments.startCheckout(rid, fan);

        orderService.cancel(rid, fan);

        assertThat(stripe.expired).containsExactly(sessionOf(rid));
        assertThat(paymentRepository.findByReservation(rid).getFirst().status()).isEqualTo(PaymentRepository.EXPIRED);
    }

    @Test
    void theTestPayButton_isNoShortcut_andOthersCantTouchYourOrder() {
        Long fan = newUser();
        UUID rid = pendingOrder(fan);
        assertThatThrownBy(() -> orderService.pay(rid, fan))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Stripe");
        assertThatThrownBy(() -> payments.startCheckout(rid, newUser())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void webhook_signedByStripe_marksThePaymentPaid_butAForgedOneIsRefused() throws Exception {
        Long fan = newUser();
        UUID rid = pendingOrder(fan);
        payments.startCheckout(rid, fan);
        String session = sessionOf(rid);
        stripe.pay(session);
        String body = """
                {"id":"evt_test_1","object":"event","type":"checkout.session.completed",
                 "data":{"object":{"id":"%s","object":"checkout.session"}}}""".formatted(session);

        mockMvc.perform(post("/api/payments/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Stripe-Signature", sign(body, "whsec_wrong_secret")))
                .andExpect(status().isBadRequest());
        assertThat(orderService.myOrder(rid, fan).status()).isEqualTo(Order.PENDING_PAYMENT);

        mockMvc.perform(post("/api/payments/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Stripe-Signature", sign(body, WEBHOOK_SECRET)))
                .andExpect(status().isOk());
        assertThat(orderService.myOrder(rid, fan).status()).isEqualTo(Order.PAID);
    }

    /** Stripe's signature: "t=<time>,v1=<HMAC-SHA256 of '<time>.<body>' with the webhook secret>". */
    private static String sign(String body, String secret) throws Exception {
        long t = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
    }
}
