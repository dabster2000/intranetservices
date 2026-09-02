-- ===========================================================================
-- Rejection routing — template data repair and re-keying
-- Author: rejection-reason routing work, 2026-09-02
-- Target: twservices4 (PRODUCTION). The five rows below exist ONLY in prod;
--         staging's template library never received them.
--
-- Run as a DB admin. `debugging-user` is SELECT-only on prod by design, so
-- this script was written and reviewed but NOT executed.
--
-- Rows are addressed by uuid, not by template_key: section B renames keys,
-- and the keys were being edited by hand in the ATS while this was written.
--
-- Section A is safe on its own and needs no deploy.
-- Section B only becomes meaningful once the reason-routing backend is live
-- (CandidateMailerReactor key fall-through + the CANDIDATE_POOLED trigger).
-- ===========================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------------
-- PRE-FLIGHT. These five rows were being edited in the ATS while this script
-- was written (NO_THANKS_OUTSIDE_DK was activated and sent mid-way through).
-- Run this first and read it: if a key has moved, or if DATABASE or
-- NEJ_TAK_NYUDDANNEDE has since produced an EMAIL_SENT event, stop and
-- re-decide section B rather than running it.
--
--   SELECT uuid, template_key, active, auto_send, updated_at
--   FROM recruitment_email_templates
--   WHERE uuid IN ('82aab230-7f2d-451f-b30f-aa964fef1da2',
--                  '83c2df7c-c871-40d7-91aa-f378f942211e',
--                  '0783f87e-1e52-48e7-8383-289c43eb3b5f',
--                  '4c7ea434-86c0-11f1-9503-027533d3d1d3',
--                  '4c7ea552-86c0-11f1-9503-027533d3d1d3');
--
--   SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.template_key')) AS k, COUNT(*)
--   FROM recruitment_events WHERE event_type = 'EMAIL_SENT'
--     AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.template_key'))
--         IN ('DATABASE','NEJ_TAK_NYUDDANNEDE')
--   GROUP BY k;   -- expect no rows
-- ---------------------------------------------------------------------------

START TRANSACTION;

-- ---------------------------------------------------------------------------
-- SECTION A — repair the letters (Phase 1: data only, no deploy)
--
-- All three custom letters were pasted in from Outlook, where the greeting
-- was typed by hand. The merge fields did not come with them, so each one
-- would have opened with a dangling comma if it had ever been sent.
-- ---------------------------------------------------------------------------

-- A1. "Tak for din ansøgning - men lige nu database" (key DATABASE)
--     Body opened "Hej ," — no merge field at all — and hard-coded the
--     position "Forretningsudvikler", which is wrong for every other role
--     and meaningless for an unsolicited applicant (who has no position).
--
--     It also asked "Er det ok for dig?" with nothing to click, so the
--     answer could only ever arrive as an email someone had to notice and
--     act on. {{consent_link}} replaces the rhetorical question with the
--     real one: the candidate mailer mints a token for this letter, the
--     candidate's yes lands in recruitment_consents as a dated, withdrawable
--     record, and granting it starts their retention clock.
--
--     Deliberately NOT promising deletion on silence. The retention sweep
--     only reaches candidates who HAVE a retention deadline, and
--     CandidateService.pool sets none — so "we delete you if you do not
--     answer" would be untrue for exactly the unsolicited applicants this
--     letter is mostly for. See the PR notes for that gap.
UPDATE recruitment_email_templates
SET body = '<p>Hej {{candidate_first_name}}</p><p>Tak for din ansøgning til Trustworks.</p><p>Vi er lige nu et sted, hvor vi i højere grad har brug for senior-profiler til at løse de opgaver, vi har hos vores kunder.</p><p>Du har en interessant baggrund, og vi vil derfor meget gerne have lov til at gemme dine oplysninger i vores kandidatbank, så vi kan tage fat i dig, hvis den rette mulighed dukker op. Sig ja via linket herunder — det tager under et minut:</p><p>{{consent_link}}</p><p>Du kan altid bruge det samme link til at trække dit samtykke tilbage igen.</p><p>Vi takker dig for din interesse og ønsker dig held og lykke med jobsøgningen.</p><p>Mange hilsner</p><p>Trustworks</p>',
    updated_at = NOW()
WHERE uuid = '82aab230-7f2d-451f-b30f-aa964fef1da2';

-- A2. "Nej tak til nyuddannede" (key NEJ_TAK_NYUDDANNEDE)
--     Body opened "Kære&nbsp;" — no merge field — and signed with a single
--     named recruiter, which cannot be right on a letter the system sends
--     or queues on anyone's behalf. Subject was literally "Trustworks".
--     Also corrects the word order in "men at det er i højere grad baseret".
UPDATE recruitment_email_templates
SET subject = 'Afslag på din ansøgning',
    body = '<p>Kære {{candidate_first_name}}</p><p>Endnu engang tak for din ansøgning og interesse for Trustworks.</p><p>Til trods for din interessante uddannelse, vælger vi ikke at gå videre med dig i denne omgang. Vi er et sted lige nu, hvor vi har behov for mere erfarne konsulenter.</p><p>Det betyder ikke, at du er ukvalificeret — det er i højere grad baseret på et nuværende behov hos vores kunder.</p><p>Du er velkommen til at søge igen på et senere tidspunkt.</p><p>Mange hilsner</p><p>Trustworks</p>',
    updated_at = NOW()
WHERE uuid = '83c2df7c-c871-40d7-91aa-f378f942211e';

-- A3. "Afslag til udenlandske ansøgere" (key NO_THANKS_OUTSIDE_DK)
--     Rendered "Dear Anna ," — a stray space before the comma, from the
--     span the merge field was pasted into. Subject was "Trustworks".
--     The empty <div> scaffolding around the text is dropped with it.
--     This row is LIVE: it was activated and sent by hand at 10:50 on
--     2026-09-02, so the repair below is all it gets — see B3 for why it is
--     not re-keyed.
UPDATE recruitment_email_templates
SET subject = 'Your application to Trustworks',
    body = '<p>Dear {{candidate_first_name}},</p><p>Thank you for your application to Trustworks.</p><p>Unfortunately, we do not hire candidates who are based outside Denmark or who are not fluent in Danish, both spoken and written.</p><p>Thank you for your interest, and good luck with your continued job search.</p><p>Best regards,</p><p>Trustworks</p>',
    updated_at = NOW()
WHERE uuid = '0783f87e-1e52-48e7-8383-289c43eb3b5f';

-- A4. Re-arm the two catch-all rejection letters.
--     Both were deactivated on 2026-08-22 19:42, and since then NO rejection
--     email has fired at all — while the acknowledgement letter keeps
--     promising every applicant an answer "inden for 4 arbejdsdage —
--     uanset hvad".
--     Both are auto_send = 0, so re-arming them queues each rejection in the
--     review queue for a human to approve. Nothing is sent unattended.
UPDATE recruitment_email_templates
SET active = 1, updated_at = NOW()
WHERE uuid IN ('4c7ea434-86c0-11f1-9503-027533d3d1d3',   -- REJECTION_SCREENING
               '4c7ea552-86c0-11f1-9503-027533d3d1d3');  -- REJECTION_POST_INTERVIEW

-- ---------------------------------------------------------------------------
-- SECTION B — re-key onto the routing triggers (needs the backend deployed)
--
-- template_key is immutable through the API by design, so raw SQL is the
-- only route. The column comment sets the limit: "Never rename a key that
-- has EMAIL_SENT events" — the key is what the candidate's timeline prints,
-- and it reads the key out of the event, never out of the template row, so
-- a rename leaves history naming a key no row has.
--
-- Checked 2026-09-02, and it split the three:
--   SELECT COUNT(*) FROM recruitment_events WHERE event_type = 'EMAIL_SENT'
--     AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.template_key'))
--         IN ('DATABASE','NEJ_TAK_NYUDDANNEDE','NO_THANKS_OUTSIDE_DK');
--   → 1, and it is NO_THANKS_OUTSIDE_DK (sent by hand at 10:50 that morning,
--     while this script was being written). So B1 and B2 rename; B3 does not.
--
-- Every letter here stays active = 0. Activating one is HR's explicit act
-- after reading the repaired text — the posture the create dialog takes for
-- every new template.
-- ---------------------------------------------------------------------------

-- B1. DATABASE → POOLED.
--     This one was never a rejection: it is "not now, may we keep you on
--     file", which is the talent pool. That mismatch is exactly why it never
--     fitted the rejection trigger. As POOLED it also becomes the only
--     letter an unsolicited applicant can receive after the receipt — their
--     submission creates no application, so no rejection can ever reach them.
UPDATE recruitment_email_templates
SET template_key = 'POOLED', updated_at = NOW()
WHERE uuid = '82aab230-7f2d-451f-b30f-aa964fef1da2';

-- B2. NEJ_TAK_NYUDDANNEDE → REJECTION_EXPERIENCE_LEVEL.
UPDATE recruitment_email_templates
SET template_key = 'REJECTION_EXPERIENCE_LEVEL', updated_at = NOW()
WHERE uuid = '83c2df7c-c871-40d7-91aa-f378f942211e';

-- B3. NO_THANKS_OUTSIDE_DK stays exactly where it is; the routing gets a
--     COPY under the trigger key instead.
--     Two reasons, either sufficient: it has an EMAIL_SENT event (see
--     above), and it is a letter someone activated and used this morning —
--     renaming it would pull it out from under them mid-use. The copy needs
--     the new LOCATION_LANGUAGE reason code, which ships with the backend;
--     until then these applicants are filed under PROFILE_MISMATCH or OTHER
--     and nothing can select this letter automatically.
--     created_by mirrors the source row's author: this is their text.
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
    0,   -- review-first, like every other rejection letter
    0,   -- HR activates it once they have read it
    NOW(), NOW(),
    '4c77d3a8-4631-408b-bc5f-a8cf570ce06d', NULL,
    'SENDER', 'BCC');

-- ---------------------------------------------------------------------------
-- Verify before committing. Expect exactly 6 rows:
--   NO_THANKS_OUTSIDE_DK         active=1 auto=0   repaired, key untouched
--   POOLED                       active=0 auto=0   body starts "<p>Hej {{candidate_first_name}}"
--                                                  and CONTAINS {{consent_link}}
--   REJECTION_EXPERIENCE_LEVEL   active=0 auto=0   subject "Afslag på din ansøgning"
--   REJECTION_LOCATION_LANGUAGE  active=0 auto=0   subject "Your application to Trustworks"
--   REJECTION_POST_INTERVIEW     active=1 auto=0
--   REJECTION_SCREENING          active=1 auto=0
-- Every body must open with a merge field, never a dangling greeting.
-- ---------------------------------------------------------------------------
SELECT template_key, active, auto_send, subject, LEFT(body, 40) AS body_head,
       body LIKE '%{{consent_link}}%' AS asks_for_consent
FROM recruitment_email_templates
WHERE uuid IN ('82aab230-7f2d-451f-b30f-aa964fef1da2',
               '83c2df7c-c871-40d7-91aa-f378f942211e',
               '0783f87e-1e52-48e7-8383-289c43eb3b5f',
               '4c7ea434-86c0-11f1-9503-027533d3d1d3',
               '4c7ea552-86c0-11f1-9503-027533d3d1d3',
               '2a556f8b-92b1-4f29-a5b2-d4fd02ea7084')
ORDER BY template_key;

-- COMMIT;    -- uncomment once the SELECT above reads correctly
-- ROLLBACK;  -- otherwise
