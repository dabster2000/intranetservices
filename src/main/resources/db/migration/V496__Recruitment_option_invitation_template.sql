-- ---------------------------------------------------------------------------
-- Method B Phase 11 (plan 2026-08-12 §11.4): the OPTION_INVITATION email
-- template — the candidate's "choose your interview time" mail.
--
-- Sent by the Method B advance sweep when the secured options go out:
-- the sweep mints the option-batch token, resolves {{options_link}} /
-- {{options_deadline}} / {{options_list}} as extras AT SEND TIME (the
-- {{consent_link}} lesson — a manual send of this template leaves the
-- placeholders visibly unresolved by design), and queues the mail through
-- the hardened mail outbox (V494).
--
-- No table changes; recruitment_email_templates is already excluded from
-- the prod→staging sync (V450), so staging edits survive the nightly
-- refresh. INSERT IGNORE keeps re-runs and existing edits safe.
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO recruitment_email_templates
    (uuid, template_key, name, subject, body, auto_send, active,
     created_at, updated_at, created_by)
VALUES
    (UUID(), 'OPTION_INVITATION', 'Interview – vælg et tidspunkt',
     'Vælg tidspunkt for din samtale med Trustworks',
     'Kære {{candidate_first_name}}\n\nTak for din interesse i Trustworks. Vi vil gerne invitere dig til samtale, og du kan nu selv vælge det tidspunkt, der passer dig bedst.\n\nVi har reserveret følgende muligheder (alle tider er dansk tid):\n\n{{options_list}}\n\nVælg dit tidspunkt her:\n\n{{options_link}}\n\nLinket er personligt og virker til og med {{options_deadline}}. Svarer du ikke inden da, frigiver vi tiderne igen, og en af vores rekrutteringsfolk kontakter dig i stedet.\n\nPasser ingen af tidspunkterne? Så kan du melde det direkte på siden — så finder vi noget andet sammen.\n\nHar du spørgsmål, kan du altid skrive til hr@trustworks.dk.\n\nMed venlig hilsen\nTrustworks',
     1, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v496');
