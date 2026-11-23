-- Week 5: orders created through RabbitMQ get a 10-minute payment hold.
--
--   CONFIRMED        bought with v1-v3 / v4 part 1 (no payment step)
--   PENDING_PAYMENT  ticket held for you, pay before expires_at
--   PAID             paid in time
--   EXPIRED          not paid in time; the ticket went back to the pool

ALTER TABLE orders
    -- Created by the API BEFORE the order reaches the DB (the buyer gets it in the 202 reply).
    -- UNIQUE makes the consumer idempotent: if RabbitMQ delivers the same message twice,
    -- the second INSERT hits the constraint and is skipped (ON CONFLICT DO NOTHING).
    ADD COLUMN reservation_id UUID UNIQUE,
    ADD COLUMN expires_at     TIMESTAMPTZ,
    ADD COLUMN paid_at        TIMESTAMPTZ;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status
        CHECK (status IN ('CONFIRMED', 'PENDING_PAYMENT', 'PAID', 'EXPIRED'));

-- "My orders" page.
CREATE INDEX idx_orders_user_id ON orders (user_id);
