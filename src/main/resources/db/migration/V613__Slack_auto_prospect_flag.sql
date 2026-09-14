-- V613 — the switch for creating a PROSPECT from a Slack company hint with nobody in the loop.
--
-- Seeded 'false'. Every Slack feature in this repo is opt-in, and this one more than the
-- others: the two lane switches arm READING channels and spending model tokens, while this
-- one arms WRITING a client row. The failure modes are not comparable. A lane left on costs
-- tokens; this left on while the matcher is wrong costs duplicate companies that somebody
-- has to merge by hand, and a duplicate `client` row is exactly what broke this module's own
-- alias resolution for Devoteam.
--
-- What it arms, when true: a hint is created as a PROSPECT only when the model graded one of
-- its sightings HIGH *and* the prefix-and-initials matcher found no company we already have.
-- Everything else still waits for a person. The hint is then LINKED to what was created, so
-- it leaves the panel and its name becomes an alias — the same end state as pressing Add.
--
-- The row is created with cvr NULL on purpose. A CVR ends up on invoices and in e-conomic,
-- and a model asked for one will produce eight plausible digits for a company it has never
-- seen. The nightly CVR job finds these with `type = 'PROSPECT' and cvr is null`.

-- Same shape as V594's seeding of the account-spaces switch: category 'crm', and an
-- ON DUPLICATE that touches nothing, so re-running never resets a switch somebody has since
-- turned on.
INSERT INTO app_settings (setting_key, setting_value, category)
VALUES ('crm.slack.auto-prospect.enabled', 'false', 'crm')
ON DUPLICATE KEY UPDATE setting_key = VALUES(setting_key);
