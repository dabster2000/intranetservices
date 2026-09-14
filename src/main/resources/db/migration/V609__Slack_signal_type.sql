-- V609 — the KIND of account event behind a Slack reading.
--
-- WHY. The first production run of both Slack lanes (2026-09-14) graded 11 of 20
-- account-space days HIGH, and five of those eleven were our own engineering: a CVE, an
-- E2E fixture, a review comment on an architecture drawing, a Coupa password reset. The
-- same run filed "styregruppemøde skal drøfte flerårigt samarbejde og kontraktfornyelser"
-- and "forlængelser for tre ressourcer ind i 2027" as LOW. Both prompts asked for HIGH
-- when the reading "holds a decision, a risk or a client ask" — a test on the grammar of
-- what was said, which a build failure passes and a renewal conversation can fail.
--
-- `relevance` is now DERIVED from this column (SlackDigestContent.relevanceOf), so the
-- grade follows what KIND of event it was. The column also answers the question one enum
-- never could: "every EXTENSION signal across all accounts this quarter".
--
-- Values (SlackDigestContent.PRIORITY, most consequential first):
--   WON, LOST, EXTENSION, NEW_SCOPE, PROPOSAL, ESCALATION, PROCUREMENT, ALLOCATION,
--   COMPLIANCE  -> relevance HIGH
--   DELIVERY, RELATIONSHIP                                  -> relevance LOW
--   NONE                                                    -> relevance NONE
--
-- varchar, not an enum: an unknown value from a later prompt version degrades to NONE on
-- read instead of failing the write of a row a model call was already paid for. Adding a
-- value is then a code change only, with no migration and no table rebuild.

ALTER TABLE account_slack_digest
    ADD COLUMN signal_type VARCHAR(20) NULL AFTER relevance;

ALTER TABLE account_slack_mention
    ADD COLUMN signal_type VARCHAR(20) NULL AFTER relevance;

-- Backfill is deliberately NOT attempted. The existing rows were graded by the v1 rubric,
-- and the column's whole purpose is to record a judgement that rubric never made — there
-- is nothing in `relevance`, `headline` or `digest_json` to recover it from, and guessing
-- would put invented history behind a filter people are meant to trust. The rows are
-- re-read from Slack instead: the lanes' cursors are the only state that matters, and
-- clearing them re-reads the same days under the v2 prompts.

-- The feed reads "the HIGH signals of the last N days, newest first" across all accounts,
-- and the account page reads one client's. Both start from the date.
CREATE INDEX idx_digest_signal_date ON account_slack_digest (signal_type, digest_date);
CREATE INDEX idx_mention_signal_date ON account_slack_mention (signal_type, mention_date);
