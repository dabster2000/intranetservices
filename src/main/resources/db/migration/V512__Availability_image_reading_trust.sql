-- Method B calendar-image reading, v2 (transcribe-then-compute).
--
-- The confirmation card used to list only busy intervals, so a day the model
-- FABRICATED as busy and a day it silently OMITTED looked identical to the
-- interviewer. Production 2026-08-18: two genuinely empty weekdays came back
-- fully booked and would have been confirmed.
--
-- The card now renders every covered day, which needs one distinction the
-- constraint rows cannot express: a day with no busy interval is either
-- positively read as FREE or was UNREADABLE and deliberately discarded. Only
-- the second must be shown as "could not read".
--
-- Both columns are additive and nullable: existing rows read as "no days
-- discarded, no trust concerns recorded", which is the correct history.

ALTER TABLE recruitment_availability_evidence
    -- Comma-separated ISO dates the vision pass could not read and that were
    -- discarded rather than guessed. Sized for three week-screenshots.
    ADD COLUMN unreadable_days VARCHAR(255) NULL AFTER covered_to,
    -- Comma-separated deterministic trust codes from AvailabilityImageReading
    -- (ALL_ROUND_BOUNDARIES, IMPLAUSIBLE_DENSITY, NO_AXIS, UNREADABLE_DAYS,
    -- NOT_CORROBORATED). Audit + card disclosure only: it never blocks, because
    -- this path is deliberately frictionless (owner decision 2026-08-18).
    ADD COLUMN read_trust VARCHAR(160) NULL AFTER unreadable_days;
