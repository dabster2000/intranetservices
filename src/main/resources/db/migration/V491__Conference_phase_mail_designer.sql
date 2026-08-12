-- Mailcraft email designer for conference phase emails.
-- mail_json holds the editable EmailDocument design; mail keeps holding the
-- rendered HTML that is actually sent, so the send path is unchanged.
ALTER TABLE conference_phases
    ADD COLUMN mail_json LONGTEXT NULL;

-- Designer output targets Gmail's ~102 KB clipping limit; TEXT caps at 64 KB
-- and would silently truncate real designs queued for bulk send.
ALTER TABLE bulk_email_job
    MODIFY COLUMN body MEDIUMTEXT NOT NULL;
