-- ===========================================================================
-- Rejection routing, part A — repair the letters (no deploy needed)
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
-- THIS FILE IS SAFE TO RUN NOW, against the prod backend as it stands.
-- It repairs three letters that would open with a dangling greeting, and
-- re-arms the two catch-all rejection letters that have been switched off
-- since 2026-08-22 — so a rejection produces an email again.
--
-- Part B (rejection-routing-b-rekey-2026-09-02.sql) re-keys the custom
-- letters onto the routing triggers and gives the pool letter its consent
-- link. Run that one only AFTER the reason-routing backend reaches prod.
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
--     Its "Er det ok for dig?" stays rhetorical for now — there is nothing
--     to click until part B gives it a consent link.
UPDATE recruitment_email_templates
SET body = '<p>Hej {{candidate_first_name}}</p><p>Tak for din ansøgning til Trustworks.</p><p>Vi er lige nu et sted, hvor vi i højere grad har brug for senior-profiler til at løse de opgaver, vi har hos vores kunder.</p><p>Du har en interessant baggrund, og vi vil derfor meget gerne have lov til at gemme dine oplysninger i vores database, så vi på et senere tidspunkt eventuelt kan tage fat i dig. Er det ok for dig?</p><p>Vi takker dig for din interesse og ønsker dig held og lykke med jobsøgningen.</p><p>Mange hilsner</p><p>Trustworks</p>',
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
--     2026-09-02, so the repair below is all it gets — part B explains why
--     it is copied rather than re-keyed.
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
-- Verify before committing. Expect 5 rows:
--   DATABASE                     active=0 auto=0   body opens "<p>Hej {{candidate_first_name}}"
--   NEJ_TAK_NYUDDANNEDE          active=0 auto=0   subject "Afslag på din ansøgning"
--   NO_THANKS_OUTSIDE_DK         active=1 auto=0   subject "Your application to Trustworks"
--   REJECTION_POST_INTERVIEW     active=1 auto=0   ← re-armed
--   REJECTION_SCREENING          active=1 auto=0   ← re-armed
-- Every body must open with a merge field, never a dangling greeting.
-- ---------------------------------------------------------------------------
SELECT template_key, active, auto_send, subject, LEFT(body, 40) AS body_head
FROM recruitment_email_templates
WHERE uuid IN ('82aab230-7f2d-451f-b30f-aa964fef1da2',
               '83c2df7c-c871-40d7-91aa-f378f942211e',
               '0783f87e-1e52-48e7-8383-289c43eb3b5f',
               '4c7ea434-86c0-11f1-9503-027533d3d1d3',
               '4c7ea552-86c0-11f1-9503-027533d3d1d3')
ORDER BY template_key;

-- COMMIT;    -- uncomment once the SELECT above reads correctly
-- ROLLBACK;  -- otherwise
