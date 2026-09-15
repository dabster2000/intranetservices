-- Fail fast rather than hang the boot on a metadata lock, as V620 does and V557 taught.
SET SESSION lock_wait_timeout = 20;

-- Graph occurrence identity permits counting a meeting once across consenting mailboxes.
-- Legacy rows stay separate until their next calendar read fills the identity.
ALTER TABLE calendar_unmatched_meeting ADD COLUMN ical_uid VARCHAR(255) NULL,
    ADD COLUMN sync_generation BIGINT NOT NULL DEFAULT 0;
