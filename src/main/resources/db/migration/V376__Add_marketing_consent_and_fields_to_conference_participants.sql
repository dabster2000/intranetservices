-- Flexible participant intake: GDPR marketing opt-in (typed) + arbitrary extra fields (JSON bag).
ALTER TABLE conference_participants
    ADD COLUMN marketing_consent TINYINT(1) NOT NULL DEFAULT 0 AFTER samtykke,
    ADD COLUMN fields            JSON       NULL          AFTER andet;
