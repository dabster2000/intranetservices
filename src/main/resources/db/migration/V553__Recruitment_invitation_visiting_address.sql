-- ===================================================================
-- Recruitment: the interview invitation names the panel and the door
-- (change requests (a) + (h), 2026-09-01)
--
-- Purpose:
--   A candidate booked for an interview receives an Outlook calendar
--   event, not an email -- RecruitmentCalendarService builds it from the
--   INTERVIEW_CANDIDATE_INVITATION template row seeded by V493. That
--   event carries ONLY the candidate as attendee (V493's split, so the
--   internal panel never leaks onto the candidate's copy), which means
--   the candidate has, until now, had no way at all to learn WHO they
--   are meeting -- and the reception iPad at /guest asks exactly that:
--   your name, your company, and the employee you are visiting.
--
--   This migration seeds the two things the code needs to close that:
--
--   1. recruitment.interview.visiting-address -- the HR-editable
--      VISITING address, read by RecruitmentVisitingAddress and shown on
--      /recruitment/settings. Deliberately a NEW concept, separate from
--      the registered/postal address 'Pustervig 3, 1126 Koebenhavn K'
--      that appears in ExpenseAIValidationService (expense geofence),
--      MailResource (bulk-mail footer) and V348. Those answer "where is
--      the company registered"; this one answers "where do I ring the
--      bell". Do not merge them.
--
--      A missing row is NOT a broken feature: the code falls back to the
--      same built-in default (RecruitmentVisitingAddress.DEFAULT_ADDRESS),
--      which matters because staging's nightly prod->staging app_settings
--      copy can take this seed away again. An EMPTY row is a deliberate
--      opt-out: invitations then carry no address and no arrival
--      instructions, exactly as before this change.
--
--   2. The three new merge tokens in the invitation body:
--        {{interviewer_names}}      "Ida Iversen og Lars Bo Jensen"
--        {{arrival_instructions}}   the reception-iPad sentence, which
--                                   embeds the visiting address
--        {{visiting_address}}       the bare address, offered so HR can
--                                   compose their own wording (not used
--                                   by the seeded body itself)
--      All three resolve on EVERY path, empty included -- the renderer
--      leaves an unknown token VERBATIM in the body, so a token without a
--      value would mail the candidate a literal "{{interviewer_names}}".
--      {{visiting_address}} and {{arrival_instructions}} are empty for an
--      online interview (RecruitmentInterview.online_meeting, V492); the
--      service then drops the paragraph that held them.
--
-- Why the body UPDATE is guarded by an EXACT match on the V493 text:
--   recruitment_email_templates is excluded from the prod->staging sync
--   (V450) and V493 seeded this row with a bare INSERT, so prod's live
--   body can have been hand-edited by HR and staging cannot prove what it
--   says. An unguarded UPDATE would silently discard that edit. The guard
--   below therefore rewrites ONLY a row that is still byte-for-byte the
--   V493 seed. A hand-edited prod row is left completely alone: it keeps
--   working, it simply does not gain the new lines until HR adds the
--   tokens themselves from the settings screen (where they are documented
--   in the per-template merge-field list). Nothing can leak either way --
--   the tokens are supplied on every path whether the body uses them or
--   not.
--
-- GDPR impact: none. No new personal data is stored. The interviewer
--   names rendered into the invitation are already-published internal
--   staff names, resolved at send time from the users table and never
--   persisted here.
--
-- Idempotency: INSERT IGNORE plus an UPDATE whose WHERE clause no longer
--   matches once it has run. Raw re-run safe (repair-at-start re-runs
--   migrations across checkouts) and admin-tuned values survive.
--
-- Author: Claude Code
-- Date:   2026-09-01
-- Rollback:
--   -- back to the V493 body:
--   UPDATE recruitment_email_templates
--      SET body = '<p>Kære {{candidate_first_name}}</p><p>Vi glæder os til at møde dig hos Trustworks.</p><p><strong>Tidspunkt:</strong> {{interview_date}} kl. {{interview_time}}<br><strong>Sted:</strong> {{interview_location}}</p><p>Er du forhindret, eller har du spørgsmål inden vi ses, er du velkommen til at svare på denne invitation.</p><p>Med venlig hilsen<br>Trustworks</p>'
--    WHERE template_key = 'INTERVIEW_CANDIDATE_INVITATION';
--   DELETE FROM app_settings
--    WHERE setting_key = 'recruitment.interview.visiting-address';
--   Note the built-in fallback body in RecruitmentCalendarService also
--   names the panel, so a full revert of the candidate-facing text needs
--   the matching backend image, not this file alone. To stop printing the
--   address and the arrival instruction WITHOUT a redeploy, blank the
--   setting rather than deleting it:
--     UPDATE app_settings SET setting_value = ''
--      WHERE setting_key = 'recruitment.interview.visiting-address';
-- ===================================================================

INSERT IGNORE INTO app_settings (setting_key, setting_value, category)
VALUES
    ('recruitment.interview.visiting-address', 'Hausergade 3, 1128 København K', 'recruitment');

UPDATE recruitment_email_templates
   SET body = CONCAT(
        '<p>Kære {{candidate_first_name}}</p>',
        '<p>Vi glæder os til at møde dig hos Trustworks.</p>',
        '<p><strong>Tidspunkt:</strong> {{interview_date}} kl. {{interview_time}}',
        '<br><strong>Sted:</strong> {{interview_location}}',
        '<br><strong>Du skal møde:</strong> {{interviewer_names}}</p>',
        '<p>{{arrival_instructions}}</p>',
        '<p>Er du forhindret, eller har du spørgsmål inden vi ses, er du velkommen til at svare på denne invitation.</p>',
        '<p>Med venlig hilsen<br>Trustworks</p>'),
       updated_at = UTC_TIMESTAMP()
 WHERE template_key = 'INTERVIEW_CANDIDATE_INVITATION'
   AND body = CONCAT(
        '<p>Kære {{candidate_first_name}}</p>',
        '<p>Vi glæder os til at møde dig hos Trustworks.</p>',
        '<p><strong>Tidspunkt:</strong> {{interview_date}} kl. {{interview_time}}',
        '<br><strong>Sted:</strong> {{interview_location}}</p>',
        '<p>Er du forhindret, eller har du spørgsmål inden vi ses, er du velkommen til at svare på denne invitation.</p>',
        '<p>Med venlig hilsen<br>Trustworks</p>');
