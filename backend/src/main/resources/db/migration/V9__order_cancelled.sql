-- The buyer can cancel an unpaid order themselves ("Cancel order" on the payment step).
--
--   CANCELLED  the buyer gave the tickets back before paying; they went back on sale at once
--
-- Like EXPIRED, a CANCELLED order no longer holds any ticket.

ALTER TABLE orders DROP CONSTRAINT chk_orders_status;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status
        CHECK (status IN ('CONFIRMED', 'PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED'));
