-- Real payments through Stripe Checkout (test mode while developing).
--
-- One row per attempt to pay an order: a Stripe "Checkout Session" (the payment page you're sent to).
-- An order can have several attempts (closed the tab, came back, clicked Pay again); an attempt
-- that is still OPEN is reused instead of making a new one.
--
--   OPEN      the Stripe page exists, nobody has paid yet
--   PAID      Stripe took the money and the order is PAID
--   EXPIRED   the page was closed (time ran out / order cancelled) without payment
--   REFUNDED  the money arrived too late (the order had already expired or was cancelled),
--             so we gave it straight back

CREATE TABLE payments (
    id                BIGSERIAL PRIMARY KEY,
    reservation_id    UUID        NOT NULL REFERENCES orders (reservation_id) ON DELETE CASCADE,
    stripe_session_id VARCHAR(255) NOT NULL UNIQUE,
    checkout_url      TEXT        NOT NULL,
    payment_intent_id VARCHAR(255),
    amount_sen        BIGINT      NOT NULL CHECK (amount_sen > 0),  -- RM 12.50 = 1250 sen (Stripe counts in cents)
    currency          VARCHAR(3)  NOT NULL,
    status            VARCHAR(10) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'PAID', 'EXPIRED', 'REFUNDED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at           TIMESTAMPTZ
);

CREATE INDEX idx_payments_reservation ON payments (reservation_id);
-- The background check looks for OPEN attempts only; a partial index keeps that tiny.
CREATE INDEX idx_payments_open ON payments (created_at) WHERE status = 'OPEN';
