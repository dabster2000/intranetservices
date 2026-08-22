-- ---------------------------------------------------------------------------
-- Candidate-email remediation (2026-08-22 spec, decisions F4/F6/F7): three
-- new templates the pipeline was silent without.
--
--   STAGE_OFFER                    — fires when an application enters Offer.
--                                    Review-first (auto_send = 0): an offer
--                                    email is the last one that should go
--                                    out unread.
--   UNSOLICITED_ACKNOWLEDGEMENT    — receipt for the unsolicited public
--                                    form, answered from the new
--                                    UNSOLICITED_APPLICATION_RECEIVED event
--                                    (no application exists on that path).
--   DUPLICATE_APPLICATION_NOTICE   — receipt when a returning candidate
--                                    applies while already in an open
--                                    process, answered from the new
--                                    DUPLICATE_APPLICATION_RECEIVED event.
--
-- All three are seeded INACTIVE: the bodies are drafts against the tone of
-- voice guide and TA owns the final wording — activation on
-- /recruitment/settings is the sign-off (the F9 posture: a template someone
-- is still writing never fires).
--
-- No table changes; recruitment_email_templates is excluded from the
-- prod→staging sync (V450). INSERT IGNORE keeps re-runs and existing rows
-- safe — the direct-SQL remediation may have created these keys in prod
-- before this migration deploys.
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO recruitment_email_templates
    (uuid, template_key, name, subject, body, body_format, auto_send, active,
     created_at, updated_at, created_by, copy_roles, copy_mode)
VALUES
    (UUID(), 'STAGE_OFFER', 'Tilbud – vi vil gerne have dig med',
     'Vi vil gerne give dig et tilbud',
     '<p>Kære {{candidate_first_name}}</p><p>Vi vil gerne give dig et tilbud om at blive en del af Trustworks.</p><p>Du hører fra os i dag – vi ringer og gennemgår detaljerne sammen, og kontrakten følger digitalt til underskrift.</p><p>Vi glæder os.</p><p>Med venlig hilsen<br><b>Trustworks</b></p>',
     'HTML', 0, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v521',
     'HIRING_OWNER', 'BCC'),
    (UUID(), 'UNSOLICITED_ACKNOWLEDGEMENT', 'Kvittering – uopfordret ansøgning',
     'Vi har modtaget din uopfordrede ansøgning',
     '<p>Kære {{candidate_first_name}}</p><p>Tak for din uopfordrede ansøgning – vi har modtaget dit CV.</p><p>Der er ikke en konkret stilling endnu. Vi læser din profil og holder den op mod vores behov, og du hører fra os inden for 4 arbejdsdage – uanset hvad.</p><p>Med venlig hilsen<br><b>Trustworks</b></p>',
     'HTML', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v521',
     '', 'BCC'),
    (UUID(), 'DUPLICATE_APPLICATION_NOTICE', 'Kvittering – allerede i proces',
     'Vi har modtaget dine dokumenter',
     '<p>Kære {{candidate_first_name}}</p><p>Vi har modtaget din nye ansøgning og gemt dine dokumenter.</p><p>Du er allerede i en aktiv proces hos os, så vi samler det hele ét sted. Din kontakt hos Trustworks vender tilbage, hvis den nye ansøgning ændrer noget.</p><p>Du behøver ikke foretage dig mere.</p><p>Med venlig hilsen<br><b>Trustworks</b></p>',
     'HTML', 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'migration-v521',
     '', 'BCC');
