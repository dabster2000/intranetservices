-- Method B: recruiter-sends-the-link mode (owner request 2026-08-15).
-- manual_candidate_delivery = the send step mints the token/batch as
-- always but mails the candidate NOTHING; the recruiter gets the link
-- plus a ready-to-send text draft as a Slack DM instead.
ALTER TABLE recruitment_scheduling_request
    ADD COLUMN manual_candidate_delivery BIT(1) NOT NULL DEFAULT b'0';
