-- Flyway runs files named V<number>__<description>.sql in order, exactly once each,
-- and records them in the flyway_schema_history table.
-- RULE: never edit a migration that has already run. Add a new V2__..., V3__... instead.

CREATE TABLE events (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    venue             VARCHAR(200) NOT NULL,
    starts_at         TIMESTAMPTZ  NOT NULL,
    sale_starts_at    TIMESTAMPTZ  NOT NULL,
    total_tickets     INTEGER      NOT NULL CHECK (total_tickets > 0),
    available_tickets INTEGER      NOT NULL,
    -- Not used yet. In week 3 this becomes the optimistic-lock counter (@Version).
    version           BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT available_within_total CHECK (available_tickets BETWEEN 0 AND total_tickets),
    CONSTRAINT sale_before_show       CHECK (sale_starts_at < starts_at)
);
