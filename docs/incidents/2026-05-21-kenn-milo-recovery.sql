-- ===================================================================
-- Incident recovery — 2026-05-21 07:07 UTC
-- ===================================================================
-- Recruitment "Send dossier for signature" for candidate Kenn Milo
-- succeeded on NextSign but the post-NextSign DB writes failed with
-- Agroal "Sorry, acquisition timeout!" (JDBC pool exhaustion). NextSign
-- case 6a0eaf1b03a779f43c003871 was created and signing emails were
-- dispatched to Thomas Buchholdt, Hans Ernst Lassen, Kenn Milo and
-- Marie Myssing — but signing_cases and candidate_dossier_revisions
-- were never written.
--
-- This script backfills the two missing rows so:
--   * NextSignStatusSyncBatchlet picks the case up (processing_status
--     = 'PENDING_FETCH') and reflects the true NextSign status.
--   * Convert-on-hire (CandidateConversionUseCase) finds the case key
--     via candidate_dossier_revisions and transfers ownership.
--   * The recruitment page shows the candidate as "sent for signature"
--     instead of inviting another (duplicate) send.
--
-- IMPORTANT — before running:
--   1. The debugging-user credentials are READ-ONLY. Run this script
--      as a write-enabled user (e.g. the admin user that owns Flyway).
--   2. Tell Hans NOT to re-click "Send for signature" on Kenn's page
--      until the rows below are in place. A retry would create a
--      duplicate NextSign case and send a second batch of emails.
-- ===================================================================

-- ----- Pre-flight (safe to run anywhere; verifies the assumed state) -----

-- Dossier + candidate identity. Expect exactly one row.
SELECT cd.uuid          AS dossier_uuid,
       cd.candidate_uuid,
       cd.template_uuid,
       cd.status        AS dossier_status,
       rc.firstname,
       rc.lastname,
       rc.email
FROM candidate_dossiers cd
JOIN recruitment_candidates rc ON rc.uuid = cd.candidate_uuid
WHERE cd.candidate_uuid = 'bb790c7d-3e3a-4bc5-9b39-d22c7c36f8e6';

-- Confirm the signing_cases row is genuinely missing. Expect zero rows.
SELECT * FROM signing_cases WHERE case_key = '6a0eaf1b03a779f43c003871';

-- Highest existing revision version for this dossier. Used to allocate
-- the next gap-less version_number. Expect 0..N.
SELECT COALESCE(MAX(version_number), 0) AS max_existing_version
FROM candidate_dossier_revisions
WHERE dossier_uuid = (
    SELECT uuid FROM candidate_dossiers
    WHERE candidate_uuid = 'bb790c7d-3e3a-4bc5-9b39-d22c7c36f8e6'
    LIMIT 1
);

-- ----- Recovery (wrap in a transaction so partial state is impossible) -----

START TRANSACTION;

-- (1) signing_cases row equivalent to
--     signingService.saveMinimalCase(caseKey, candidate.getUuid(),
--                                    "Recruitment: Kenn Milo", 4, null)
INSERT INTO signing_cases (
    case_key,             nextsign_key,
    user_uuid,            reference_id,
    document_name,        status,             processing_status,  folder,
    total_signers,        completed_signers,
    created_at
) VALUES (
    '6a0eaf1b03a779f43c003871',
    'WAHC98brdw9dOtssLmn3JMLy9',
    'bb790c7d-3e3a-4bc5-9b39-d22c7c36f8e6',                          -- saveMinimalCase passes candidate.getUuid()
    'recruitment-candidate:bb790c7d-3e3a-4bc5-9b39-d22c7c36f8e6',
    'Recruitment: Kenn Milo',
    'PENDING', 'PENDING_FETCH', 'Default',
    4, 0,
    '2026-05-21 07:07:07'
);

-- (2) candidate_dossier_revisions snapshot so collectSigningCaseKeys
-- finds the link at Convert time. The placeholder_values_snapshot is
-- intentionally a thin recovery marker — the original values (CPR,
-- salary, etc.) were lost when the request failed. The signers JSON
-- mirrors the actual NextSign payload from the trace.

SET @dossier_uuid  := (SELECT uuid FROM candidate_dossiers
                       WHERE candidate_uuid = 'bb790c7d-3e3a-4bc5-9b39-d22c7c36f8e6'
                       LIMIT 1);
SET @next_version  := (SELECT COALESCE(MAX(version_number), 0) + 1
                       FROM candidate_dossier_revisions
                       WHERE dossier_uuid = @dossier_uuid);

INSERT INTO candidate_dossier_revisions (
    uuid,
    dossier_uuid,
    version_number,
    kind,
    placeholder_values_snapshot,
    signers_config_snapshot,
    appendices_snapshot,
    signing_case_key,
    recipient_email,
    sent_by_useruuid,
    note,
    created_at
) VALUES (
    UUID(),
    @dossier_uuid,
    @next_version,
    'SIGNATURE',
    JSON_OBJECT(
        '_recovery_note',
        'Backfilled 2026-05-21. Original placeholder values were lost when sendSignature failed after NextSign success (Agroal acquisition timeout).'
    ),
    JSON_ARRAY(
        JSON_OBJECT('name', 'Thomas Buchholdt',  'email', 'thomas.buchholdt@trustworks.dk', 'signing', true,  'order', 0),
        JSON_OBJECT('name', 'Hans Ernst Lassen', 'email', 'hans.lassen@trustworks.dk',      'signing', true,  'order', 1),
        JSON_OBJECT('name', 'Kenn Milo',         'email', 'kennmilo@gmail.com',             'signing', true,  'order', 2),
        JSON_OBJECT('name', 'Marie Myssing',     'email', 'marie.myssing@trustworks.dk',    'signing', false, 'order', 3)
    ),
    JSON_ARRAY(),                                                     -- appendices were sent, but their snapshot is not recoverable from logs
    '6a0eaf1b03a779f43c003871',
    'kennmilo@gmail.com',
    '7948c5e8-162c-4053-b905-0f59a21d7746',                           -- Hans Lassen (X-Requested-By from the trace)
    'Hej Kenn,\n\nHermed dokumenter til underskrift som aftalt.\n\nMvh \nHans og Thomas',
    '2026-05-21 07:07:07'
);

-- ----- Post-flight verification ----------------------------------------

-- Confirm both rows are present.
SELECT 'signing_cases'                         AS source,
       case_key, user_uuid, document_name,
       status, processing_status, total_signers
FROM signing_cases
WHERE case_key = '6a0eaf1b03a779f43c003871';

SELECT 'candidate_dossier_revisions'          AS source,
       uuid, version_number, kind,
       signing_case_key, recipient_email
FROM candidate_dossier_revisions
WHERE signing_case_key = '6a0eaf1b03a779f43c003871';

-- If both lookups return exactly one row each, commit:
COMMIT;
-- Otherwise: ROLLBACK; and re-investigate.

-- ----- Follow-up monitoring -------------------------------------------
-- Within ~5 minutes the NextSignStatusSyncBatchlet should flip
-- processing_status from PENDING_FETCH to COMPLETED and populate
-- status (OPEN / partially signed / etc.):
--   SELECT case_key, processing_status, status, completed_signers,
--          last_status_fetch
--   FROM signing_cases
--   WHERE case_key = '6a0eaf1b03a779f43c003871';
