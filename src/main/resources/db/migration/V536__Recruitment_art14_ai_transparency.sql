-- ===================================================================
-- Recruitment: Art. 14 notice — AI transparency paragraph
-- (Interview Room design spec 2026-08-26 §9)
--
-- Purpose:
--   The Art. 14 notice already sent to candidates must NAME the AI
--   assistance used in the recruitment process, including the Interview
--   Room's assistance features (spec §9: "this is a copy change to an
--   existing template, and it is not optional"). The paragraph states
--   what the AI does (organises the interviewer's own notes, proposes
--   structured facts and summaries) and what it never does (assess,
--   score or decide — every AI output is reviewed and accepted or
--   rejected by a named person).
--
-- Idempotency: append-only UPDATE guarded by a NOT LIKE marker, so a
--   re-run (repair-at-start re-applies migrations across checkouts) and
--   a DPO's hand edits both survive. The template row itself is seeded
--   by V448 (INSERT IGNORE); if the DPO deleted the AI paragraph on
--   purpose after this ran once, a re-run would re-append it — the
--   marker phrase below is what the guard matches on, so an edit that
--   keeps any part of the phrase keeps the guard.
--
-- Author: Claude Code
-- Date:   2026-08-26
-- Rollback:
--   UPDATE recruitment_email_templates
--      SET body = REPLACE(body, CONCAT('\n\nAI-assisteret notatstøtte: ',
--          'Trustworks bruger AI-værktøjer til at organisere vores egne interviewnoter, ',
--          'foreslå strukturerede fakta (fx opsigelsesvarsel og startdato) og udarbejde ',
--          'opsummeringer. AI-værktøjerne vurderer dig ikke og træffer ingen beslutninger — ',
--          'alle forslag gennemgås og godkendes eller afvises af en medarbejder, og enhver ',
--          'vurdering af din ansøgning foretages af mennesker.'), '')
--    WHERE template_key = 'ART14_NOTICE';
-- ===================================================================

UPDATE recruitment_email_templates
   SET body = CONCAT(body,
        '\n\nAI-assisteret notatstøtte: Trustworks bruger AI-værktøjer til at organisere ',
        'vores egne interviewnoter, foreslå strukturerede fakta (fx opsigelsesvarsel og ',
        'startdato) og udarbejde opsummeringer. AI-værktøjerne vurderer dig ikke og træffer ',
        'ingen beslutninger — alle forslag gennemgås og godkendes eller afvises af en ',
        'medarbejder, og enhver vurdering af din ansøgning foretages af mennesker.'),
       updated_at = UTC_TIMESTAMP()
 WHERE template_key = 'ART14_NOTICE'
   AND body NOT LIKE '%AI-assisteret notatstøtte%';
