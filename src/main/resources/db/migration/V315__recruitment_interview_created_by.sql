-- Slice 3b: track which user created (scheduled) the interview.
-- Required by GraphReconciliationWorker and the public Graph notification webhook
-- to resolve the organizer mailbox (TAM-as-organizer model — the calendar event
-- lives in the actor's mailbox, so RSVP queries must use that mailbox).

ALTER TABLE recruitment_interview
    ADD COLUMN created_by VARCHAR(36) NULL AFTER status;
