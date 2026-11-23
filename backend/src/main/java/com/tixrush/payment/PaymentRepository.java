package com.tixrush.payment;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The payments table (V10): one row per Stripe payment page opened for an order. */
@Repository
public class PaymentRepository {

    public static final String OPEN = "OPEN", PAID = "PAID", EXPIRED = "EXPIRED", REFUNDED = "REFUNDED";

    public record Payment(long id, UUID reservationId, String sessionId, String checkoutUrl, String paymentIntentId,
                          long amountSen, String currency, String status, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves a new attempt. 0 = this session is already saved (a double click got the same session from Stripe). */
    public int insert(UUID reservationId, String sessionId, String url, long amountSen, String currency) {
        return jdbc.sql("""
                        insert into payments (reservation_id, stripe_session_id, checkout_url, amount_sen, currency)
                        values (:rid, :sid, :url, :amount, :currency)
                        on conflict (stripe_session_id) do nothing
                        """)
                .param("rid", reservationId).param("sid", sessionId).param("url", url)
                .param("amount", amountSen).param("currency", currency)
                .update();
    }

    public List<Payment> findByReservation(UUID reservationId) {
        return jdbc.sql("select * from payments where reservation_id = :rid order by id")
                .param("rid", reservationId).query(PaymentRepository::map).list();
    }

    public Optional<Payment> findOpen(UUID reservationId) {
        return jdbc.sql("select * from payments where reservation_id = :rid and status = 'OPEN' order by id desc limit 1")
                .param("rid", reservationId).query(PaymentRepository::map).optional();
    }

    public int countAttempts(UUID reservationId) {
        return jdbc.sql("select count(*) from payments where reservation_id = :rid")
                .param("rid", reservationId).query(Integer.class).single();
    }

    /** Locks the row until the transaction ends: two checks of the same payment can't both act on it. */
    public Optional<Payment> lockBySession(String sessionId) {
        return jdbc.sql("select * from payments where stripe_session_id = :sid for update")
                .param("sid", sessionId).query(PaymentRepository::map).optional();
    }

    public boolean exists(String sessionId) {
        return jdbc.sql("select count(*) from payments where stripe_session_id = :sid")
                .param("sid", sessionId).query(Integer.class).single() > 0;
    }

    /** OPEN attempts older than `minAge`: the background check asks Stripe about these. */
    public List<String> openSessionsOlderThan(Duration minAge, int limit) {
        return jdbc.sql("select stripe_session_id from payments where status = 'OPEN' and created_at < :before order by created_at limit :limit")
                .param("before", OffsetDateTime.now().minus(minAge)).param("limit", limit)
                .query(String.class).list();
    }

    public boolean wasRefunded(UUID reservationId) {
        return jdbc.sql("select count(*) from payments where reservation_id = :rid and status = 'REFUNDED'")
                .param("rid", reservationId).query(Integer.class).single() > 0;
    }

    /** OPEN -> PAID / EXPIRED / REFUNDED. Only from OPEN: a finished payment never changes again. */
    public int finish(String sessionId, String status, String paymentIntentId) {
        return jdbc.sql("""
                        update payments set status = :status,
                               payment_intent_id = coalesce(:pi, payment_intent_id),
                               paid_at = case when :status in ('PAID', 'REFUNDED') then now() else paid_at end
                        where stripe_session_id = :sid and status = 'OPEN'
                        """)
                .param("status", status).param("pi", paymentIntentId).param("sid", sessionId)
                .update();
    }

    private static Payment map(ResultSet rs, int row) throws SQLException {
        return new Payment(rs.getLong("id"), rs.getObject("reservation_id", UUID.class), rs.getString("stripe_session_id"),
                rs.getString("checkout_url"), rs.getString("payment_intent_id"), rs.getLong("amount_sen"),
                rs.getString("currency"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
