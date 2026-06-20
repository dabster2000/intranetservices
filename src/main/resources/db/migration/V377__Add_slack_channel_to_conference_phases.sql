-- Optional per-phase Slack channel. When set, entering this phase posts a
-- Block Kit message about the participant to this channel. Blank/NULL = off.
ALTER TABLE conference_phases
    ADD COLUMN slack_channel VARCHAR(120) NULL AFTER mail;
