-- Widen conference_participants.andet (the free-text "message" field of the public
-- contact / signup forms) from VARCHAR(255) to TEXT.
--
-- Why: the column is fed by the public website contact form
-- (POST /knowledge/conferences/{uuid}/contact, conference "WEBSITE"). Real messages
-- routinely exceed 255 characters. Under STRICT_TRANS_TABLES MariaDB rejects the insert
-- with 1406/22001 "Data too long for column 'andet'" — and because the insert happens on
-- an async event-bus consumer, the caller had already been given HTTP 204. The
-- registration was lost with nothing shown to the user (prod 2026-08-03, a 417-char
-- message; also 2026-07-05 and 2025-04-25). 43 older rows sit at exactly 255 characters,
-- consistent with the earlier forms capping the field client-side.
--
-- TEXT (65,535 bytes) is the right size for a contact-form message. An explicit 8,000
-- character bound is enforced synchronously at the REST boundary by
-- ParticipantFieldLimits, so an over-length submission now returns 400 instead of
-- disappearing, and the column can never be used as unbounded anonymous storage.
--
-- Note: VARCHAR -> TEXT is a BLOB-family type change, so InnoDB cannot do this
-- ALGORITHM=INPLACE; MariaDB falls back to a COPY rebuild holding LOCK=SHARED. The table
-- is ~5,200 rows with only the primary key and two FK indexes (none on `andet`), so the
-- rebuild is sub-second. It runs at boot because flyway.migrate-at-start=true.
--
-- Guarded so it is a no-op when already applied: flyway.repair-at-start=true has been
-- observed re-running migrations in this repo.

SET @is_already_text := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'conference_participants'
      AND COLUMN_NAME  = 'andet'
      AND DATA_TYPE    = 'text'
);

SET @ddl := IF(@is_already_text = 0,
    'ALTER TABLE `conference_participants` MODIFY COLUMN `andet` TEXT NULL COMMENT ''Free-text message from the public contact/signup form; bounded at 8000 chars by ParticipantFieldLimits''',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
