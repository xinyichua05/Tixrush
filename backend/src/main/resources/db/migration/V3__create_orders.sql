-- One row = one ticket bought. ("order" is a reserved SQL word, so the table is "orders".)
CREATE TABLE orders (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    -- Which "Buy" version created it (naive / pessimistic / optimistic), so we can compare them.
    strategy   VARCHAR(20) NOT NULL,
    -- Only CONFIRMED for now. Week 5 adds PENDING_PAYMENT / EXPIRED for the 10-minute hold.
    status     VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Speeds up "count/delete orders of event X" (stats + reset).
CREATE INDEX idx_orders_event_id ON orders (event_id);
