-- P15 rich text: candidate-email bodies may now be sanitized HTML fragments.
--
-- The format is stored explicitly rather than sniffed from the body. Sniffing would
-- misread a plain Danish sentence containing "R&D" or "5 < 6" as markup and serve a
-- candidate mangled entities, from an unlogged branch sitting between a GDPR-regulated
-- template and someone's inbox.
--
-- Existing rows stay PLAIN and keep the exact pre-rich-text render path (escape the whole
-- body, newlines to <br>). No body content is rewritten here: a template becomes HTML only
-- when a recruiter deliberately saves it from the rich editor, which confines any rollback
-- hazard to templates a human converted on purpose. ACKNOWLEDGEMENT and REJECTION_SCREENING
-- in particular were hand-edited through the UI and must never be re-seeded.

ALTER TABLE recruitment_email_templates
    ADD COLUMN body_format VARCHAR(5) NOT NULL DEFAULT 'PLAIN'
        COMMENT 'PLAIN = legacy plain text (escaped + newline->br at send); HTML = sanitized fragment (a,b,blockquote,br,div,em,i,li,ol,p,span,strong,u,ul; a[href,title] http/https/mailto only)'
        AFTER body;

ALTER TABLE recruitment_email_templates
    ADD CONSTRAINT chk_ret_body_format CHECK (body_format IN ('PLAIN', 'HTML'));

ALTER TABLE recruitment_pending_emails
    ADD COLUMN body_format VARCHAR(5) NOT NULL DEFAULT 'PLAIN'
        COMMENT 'Snapshot of the template body_format at queue time - the approver reviews, edits and sends in this format'
        AFTER body;

ALTER TABLE recruitment_pending_emails
    ADD CONSTRAINT chk_rpe_body_format CHECK (body_format IN ('PLAIN', 'HTML'));

-- The V446 column comments still described the bodies as plain text.
ALTER TABLE recruitment_email_templates
    MODIFY COLUMN body TEXT NOT NULL
        COMMENT 'Danish body with merge fields; read according to body_format';

ALTER TABLE recruitment_pending_emails
    MODIFY COLUMN body TEXT NOT NULL
        COMMENT 'Rendered body snapshot in body_format (personal data - P19 scrubs PENDING rows)';
