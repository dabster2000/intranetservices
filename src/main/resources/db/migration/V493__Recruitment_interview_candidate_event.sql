-- ===================================================================
-- V493: Recruitment ATS — candidate/internal interview event split
-- ===================================================================
-- Feature: Interview scheduling modernization Phase 6 — the candidate
--          gets their OWN Outlook event with a candidate-facing HTML
--          body; the interviewers keep an internal event.
-- Domain:  recruitmentservice (interview loop)
--
-- Purpose:
--   * graph_candidate_event_id: the candidate-facing event's Graph id.
--     NULL = single-event row (pre-split, or candidate without email) —
--     update/cancel keep treating those exactly as before, so nothing
--     migrates and nobody gets double-invited.
--   * Seed the INTERVIEW_CANDIDATE_INVITATION email template: the
--     candidate event's subject + HTML body, editable by HR in the
--     existing templates admin (rich text, merge fields). The key is
--     not a reactor trigger — the calendar service reads it directly.
--
-- Design notes:
--   * The candidate event is created under the shared organizer mailbox
--     and carries ONLY the candidate — no interviewer list, no internal
--     links, no kit. What the candidate must not see simply never
--     rides on their event.
--   * Extra merge fields resolved at event-build time:
--     {{interview_date}}, {{interview_time}}, {{interview_location}}.
-- ===================================================================

ALTER TABLE recruitment_interviews
    ADD COLUMN graph_candidate_event_id VARCHAR(255) NULL;

INSERT INTO recruitment_email_templates
    (uuid, template_key, name, subject, body, body_format, auto_send, active,
     created_at, updated_at, created_by)
VALUES
    (UUID(), 'INTERVIEW_CANDIDATE_INVITATION', 'Kalenderinvitation: samtale',
     'Samtale hos Trustworks',
     '<p>Kære {{candidate_first_name}}</p><p>Vi glæder os til at møde dig hos Trustworks.</p><p><strong>Tidspunkt:</strong> {{interview_date}} kl. {{interview_time}}<br><strong>Sted:</strong> {{interview_location}}</p><p>Er du forhindret, eller har du spørgsmål inden vi ses, er du velkommen til at svare på denne invitation.</p><p>Med venlig hilsen<br>Trustworks</p>',
     'HTML', 0, 1,
     UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'system');
