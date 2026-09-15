-- Recipient-facing copy is captured with the capability without changing its list/address identity.
-- Old links keep their existing metadata fallback. No tokens or preferences are reissued or rewritten.
-- V557 (prod 2026-09-02): a DDL migration that waits on a MariaDB metadata lock hangs the Quarkus
-- boot silently and gets the task killed as unhealthy. This table takes an insert per issued token
-- and is live as of the 2026-09-15 conference mail release, so fail fast rather than queue.
SET SESSION lock_wait_timeout = 20;

ALTER TABLE conference_unsubscribe_token
    ADD COLUMN public_copy JSON NULL;
