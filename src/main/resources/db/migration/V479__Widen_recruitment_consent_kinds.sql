-- V479: Widen recruitment_consents.kind for the Airtable-parity consents.
--
-- The public /apply forms now require two consents at submission time
-- (recorded as GRANTED rows by PublicApplyService.recordSubmissionConsents):
--   APPLICATION_PROCESSING       - mandatory storage/processing consent
--                                  (expires_at NULL; retention sweep governs)
--   CRIMINAL_RECORD_ACKNOWLEDGED - mandatory ISAE 3000 acknowledgment that a
--                                  clean straffeattest may be requested
--                                  (expires_at NULL)
-- The CHECK constraint chk_rcon_kind_enum (V436) allowed only
-- TALENT_POOL_RETENTION, so it must be recreated with the new kinds.

ALTER TABLE recruitment_consents
    DROP CONSTRAINT chk_rcon_kind_enum;

ALTER TABLE recruitment_consents
    ADD CONSTRAINT chk_rcon_kind_enum
        CHECK (kind IN (
            'TALENT_POOL_RETENTION',
            'APPLICATION_PROCESSING',
            'CRIMINAL_RECORD_ACKNOWLEDGED'
        ));
