-- Numbered seats: seated zones are a grid of rows (A, B, C...) x seats (1..N).
-- Buyers pick exact seats on a seat map, or "Best available" picks the best ones side by side.
-- Standing zones and simple ticket types have no seats (both columns stay null).

ALTER TABLE venue_zones  ADD COLUMN seat_rows INT, ADD COLUMN seats_per_row INT;
ALTER TABLE ticket_types ADD COLUMN seat_rows INT, ADD COLUMN seats_per_row INT;

-- Every seat of an event's numbered ticket type. reservation_id = the order holding it (null = free).
-- An unpaid order that expires sets it back to null; deleting an order frees its seats too.
CREATE TABLE seats (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_type_id BIGINT     NOT NULL REFERENCES ticket_types (id) ON DELETE CASCADE,
    row_index      INT        NOT NULL,   -- 0 = front row
    row_label      VARCHAR(3) NOT NULL,   -- "A", "B", ... "Z", "AA", ...
    seat_number    INT        NOT NULL,   -- 1 .. seats_per_row, left to right
    reservation_id UUID REFERENCES orders (reservation_id) ON DELETE SET NULL,
    UNIQUE (ticket_type_id, row_index, seat_number)
);
CREATE INDEX idx_seats_reservation ON seats (reservation_id);
