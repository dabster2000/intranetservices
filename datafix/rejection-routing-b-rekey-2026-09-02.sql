-- ===========================================================================
-- Rejection routing, part B — wire the letters onto the routing triggers
-- Author: rejection-reason routing work, 2026-09-02
-- Target: twservices4 (PRODUCTION). Run as a DB admin; `debugging-user` is
--         SELECT-only on prod by design.
--
-- RUN THIS ONLY AFTER the reason-routing backend is live in prod, i.e. after
-- CandidateMailerReactor resolves a rejection through rejectionKeyChain and
-- fires the CANDIDATE_POOLED trigger. Before that these keys name triggers
-- nothing can fire, and the letters below simply go quiet.
--
-- Run rejection-routing-a-repair-2026-09-02.sql FIRST. Part A repairs the
-- greetings; this file assumes those repairs are already in place.
--
-- Rows are addressed by uuid, not by template_key: this file renames keys,
-- and the keys were being edited by hand in the ATS while it was written.
-- ===========================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- PRE-FLIGHT. Read this before running.
--
--   -- 1. Part A has run: the greetings are repaired.
--   SELECT template_key, LEFT(body, 34) AS body_head, active
--   FROM recruitment_email_templates
--   WHERE uuid IN ('82aab230-7f2d-451f-b30f-aa964fef1da2',
--                  '83c2df7c-c871-40d7-91aa-f378f942211e');
--   -- expect both to open "<p>Hej {{candidate_first_name}}" / "<p>Kære {{…"
--
--   -- 2. Neither row being RENAMED has sent an email since. The column
--   --    comment's rule: never rename a key that has EMAIL_SENT events —
--   --    the candidate's timeline prints the key out of the EVENT, never out
--   --    of the template row, so a rename leaves history naming a key no row
--   --    has. (This is exactly why B3 copies instead of renaming.)
--   SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.template_key')) AS k, COUNT(*)
--   FROM recruitment_events WHERE event_type = 'EMAIL_SENT'
--     AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.template_key'))
--         IN ('DATABASE','NEJ_TAK_NYUDDANNEDE')
--   GROUP BY k;   -- expect NO ROWS; if either appears, stop and copy it
--                 -- into the new key instead of renaming, as B3 does.
-- ---------------------------------------------------------------------------

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- B1. DATABASE → POOLED, and the question becomes answerable.
--
-- This letter was never a rejection: it is "not now, may we keep you on
-- file", which is the talent pool. That mismatch is why it never fitted the
-- rejection trigger. As POOLED it also becomes the only letter an
-- unsolicited applicant can receive after the receipt — their submission
-- creates no application, so no rejection can ever reach them.
--
-- The body gains {{consent_link}}. The candidate mailer mints a real token
-- for this letter, so the yes lands in recruitment_consents as a dated,
-- withdrawable record instead of arriving as an email somebody has to act
-- on. The deletion sentence is now TRUE: entering the pool starts a
-- six-month retention clock (CandidateService.pool, 2026-09-02) and the GDPR
-- sweep acts on it. Do not re-add that sentence to any letter whose
-- candidates have no deadline.
-- ---------------------------------------------------------------------------
UPDATE recruitment_email_templates
SET template_key = 'POOLED',
    subject = 'Må vi gemme din profil hos Trustworks?',
    body = '<p>Hej {{candidate_first_name}}</p><p>Tak for din ansøgning til Trustworks.</p><p>Vi er lige nu et sted, hvor vi i højere grad har brug for senior-profiler til at løse de opgaver, vi har hos vores kunder.</p><p>Du har en interessant baggrund, og vi vil derfor meget gerne have lov til at gemme dine oplysninger i vores kandidatbank, så vi kan tage fat i dig, hvis den rette mulighed dukker op. Sig ja via linket herunder — det tager under et minut:</p><p>{{consent_link}}</p><p>Hvis du ikke giver os besked, sletter vi automatisk dine oplysninger efter seks måneder. Du kan altid bruge det samme link til at trække dit samtykke tilbage igen.</p><p>Vi takker dig for din interesse og ønsker dig held og lykke med jobsøgningen.</p><p>Mange hilsner</p><p>Trustworks</p>',
    updated_at = NOW()
WHERE uuid = '82aab230-7f2d-451f-b30f-aa964fef1da2';

-- ---------------------------------------------------------------------------
-- B2. NEJ_TAK_NYUDDANNEDE → REJECTION_EXPERIENCE_LEVEL.
-- Rejections with the coded reason "Experience level" now select it, at any
-- stage, in preference to the catch-all letter.
-- ---------------------------------------------------------------------------
UPDATE recruitment_email_templates
SET template_key = 'REJECTION_EXPERIENCE_LEVEL', updated_at = NOW()
WHERE uuid = '83c2df7c-c871-40d7-91aa-f378f942211e';

-- ---------------------------------------------------------------------------
-- B3. NO_THANKS_OUTSIDE_DK stays where it is; the routing gets a COPY.
--
-- Two reasons, either sufficient: it has an EMAIL_SENT event (sent by hand
-- at 10:50 on 2026-09-02), and it is a letter someone activated and is using
-- — renaming it would pull it out from under them mid-use.
--
-- The copy needs the LOCATION_LANGUAGE reason code, which ships with the
-- backend. created_by mirrors the source row's author: this is their text.
-- ---------------------------------------------------------------------------
INSERT INTO recruitment_email_templates
    (uuid, template_key, name, subject, body, body_format,
     auto_send, active, created_at, updated_at, created_by, modified_by,
     copy_roles, copy_mode)
VALUES (
    '2a556f8b-92b1-4f29-a5b2-d4fd02ea7084',
    'REJECTION_LOCATION_LANGUAGE',
    'Afslag – uden for Danmark eller uden dansk',
    'Your application to Trustworks',
    '<p>Dear {{candidate_first_name}},</p><p>Thank you for your application to Trustworks.</p><p>Unfortunately, we do not hire candidates who are based outside Denmark or who are not fluent in Danish, both spoken and written.</p><p>Thank you for your interest, and good luck with your continued job search.</p><p>Best regards,</p><p>Trustworks</p>',
    'HTML',
    0,   -- review-first: the agreed posture for every rejection letter
    0,   -- HR activates it once they have read it
    NOW(), NOW(),
    '4c77d3a8-4631-408b-bc5f-a8cf570ce06d', NULL,
    'SENDER', 'BCC');

-- ---------------------------------------------------------------------------
-- Verify before committing. Expect 4 rows:
--   NO_THANKS_OUTSIDE_DK         active=1 auto=0  asks=0  key untouched
--   POOLED                       active=0 auto=0  asks=1  ← the consent link
--   REJECTION_EXPERIENCE_LEVEL   active=0 auto=0  asks=0
--   REJECTION_LOCATION_LANGUAGE  active=0 auto=0  asks=0  ← new row
--
-- All three re-keyed/new letters stay active = 0. Activating one is HR's
-- explicit act after reading it — the posture the create dialog takes for
-- every new template. Nothing here mails anyone until they do.
-- ---------------------------------------------------------------------------
SELECT template_key, active, auto_send, subject,
       body LIKE '%{{consent_link}}%' AS asks_for_consent
FROM recruitment_email_templates
WHERE uuid IN ('82aab230-7f2d-451f-b30f-aa964fef1da2',
               '83c2df7c-c871-40d7-91aa-f378f942211e',
               '0783f87e-1e52-48e7-8383-289c43eb3b5f',
               '2a556f8b-92b1-4f29-a5b2-d4fd02ea7084')
ORDER BY template_key;

-- COMMIT;    -- uncomment once the SELECT above reads correctly
-- ROLLBACK;  -- otherwise
