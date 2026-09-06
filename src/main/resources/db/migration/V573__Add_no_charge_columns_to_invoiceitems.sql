-- JK Team 2.0 / WP4b — no-charge invoice lines (spec §4.4.2, D2).
--
-- Hours on a declared zero-rate contract line reach the invoice as an explicit no-charge
-- line: quantity, the list value at list_rate, the reason and the review date, and a line
-- total of 0. The line is marked by no_charge_reason so the intercompany-invoice generator
-- keeps it (it skips other rate-0 items) and the credit-note copy carries it unchanged.
-- list_rate and rate_review_date are printed on the line; they never enter a total.

-- The ALTER needs an exclusive metadata lock, and a queued exclusive request
-- blocks new shared readers on the same table. Fail fast rather than hang the
-- boot (the V557 lesson).
SET SESSION lock_wait_timeout = 20;

ALTER TABLE invoiceitems
    ADD COLUMN no_charge_reason VARCHAR(32)   NULL COMMENT 'zero_rate_reason of the consultant line this no-charge item came from',
    ADD COLUMN list_rate        DECIMAL(10,2) NULL COMMENT 'List value per hour shown on a no-charge line — never part of a total',
    ADD COLUMN rate_review_date DATE          NULL COMMENT 'Step-up deadline printed on a no-charge line';
