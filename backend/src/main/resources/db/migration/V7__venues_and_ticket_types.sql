-- Ticket types: an event can sell several kinds of ticket, each with its own price and stock.
--
--   Concert:     the types are ZONES of a saved venue (VIP, CAT 1, Rock Zone...), shown on its map picture.
--   Exhibition:  the admin types them by hand (Adult, Student, Child...). No venue, no map.
--
-- Events without any ticket type keep the old single pool ("Standard ticket").

-- A venue is set up ONCE and reused by many events.
CREATE TABLE venues (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(200) NOT NULL UNIQUE,
    map_key    VARCHAR(36),                       -- key of the seating-plan picture; null = none
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The seating-plan picture (same idea as event_images).
CREATE TABLE venue_maps (
    venue_id     BIGINT PRIMARY KEY REFERENCES venues (id) ON DELETE CASCADE,
    content_type VARCHAR(20) NOT NULL,
    data         BYTEA       NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The areas of a venue. Capacity is only a default: each event chooses how many to sell.
CREATE TABLE venue_zones (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id   BIGINT      NOT NULL REFERENCES venues (id) ON DELETE CASCADE,
    name       VARCHAR(60) NOT NULL,
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('SEATED', 'STANDING')),
    capacity   INT         NOT NULL CHECK (capacity > 0),
    color      VARCHAR(7)  NOT NULL,              -- "#e11d48": matches the colour on the map picture
    sort_order INT         NOT NULL,
    UNIQUE (venue_id, name)
);

-- RESTRICT: a venue that events still use can't be deleted (the API answers 409 first).
ALTER TABLE events ADD COLUMN venue_id BIGINT REFERENCES venues (id) ON DELETE RESTRICT;

-- What an event actually sells. Zones are COPIED in here, so editing the venue later never
-- changes an event that is already on sale. kind/color are only set for rows copied from a zone.
CREATE TABLE ticket_types (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id          BIGINT        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name              VARCHAR(60)   NOT NULL,
    kind              VARCHAR(10),
    color             VARCHAR(7),
    price             NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    total_tickets     INT           NOT NULL CHECK (total_tickets > 0),
    available_tickets INT           NOT NULL CHECK (available_tickets BETWEEN 0 AND total_tickets),
    sort_order        INT           NOT NULL,
    UNIQUE (event_id, name)
);
CREATE INDEX idx_ticket_types_event_id ON ticket_types (event_id);

-- One order = one purchase of 1-4 tickets of one type. Old orders: no type, quantity 1.
ALTER TABLE orders
    ADD COLUMN ticket_type_id BIGINT REFERENCES ticket_types (id) ON DELETE CASCADE,
    ADD COLUMN quantity       INT NOT NULL DEFAULT 1 CHECK (quantity BETWEEN 1 AND 4),
    ADD COLUMN unit_price     NUMERIC(10, 2);
