-- V612 — what the model thought of a sighting, kept instead of thrown away.
--
-- WHY. When the source-channel lane reads a company Intra does NOT have, the model's whole
-- reading is discarded at the door: `slack_unmatched_company_sighting` kept only the name,
-- the channel, the day, a mention count, an author count and a permalink. So by the time a
-- name reaches the hints panel, a hot inbound opportunity and a passing name-drop are
-- indistinguishable — the only thing left to rank them by is how often they were said.
--
-- That is what made the corroboration bar wrong rather than merely strict. On 2026-09-14 a
-- colleague posted a prospect's LinkedIn note into #kommercielt-team with "vi skal med i
-- denne dialog ... er det en du vil tage?", naming an SVP at a company we do not work with.
-- One day, one author, one channel: not corroborated, no client match, held back. The model
-- had judged it; nothing kept the judgement.
--
-- So the sighting now carries the signal type and the headline the model wrote. A hint whose
-- sighting was graded HIGH is offered on its FIRST mention, whatever its counts, and the
-- panel can say what it is rather than only how often it was said.
--
-- Nullable, and not backfilled: every existing sighting was written before this column
-- existed and there is nothing to recover the judgement from. A null reads as "not graded",
-- which falls back to the counts exactly as before.

ALTER TABLE slack_unmatched_company_sighting
    ADD COLUMN signal_type VARCHAR(20) NULL AFTER mention_count,
    ADD COLUMN headline VARCHAR(200) NULL AFTER signal_type;

-- The hints panel asks "which names have ever been graded HIGH", across all sightings of
-- each name. name_key leads because that is what the aggregate is grouped by.
CREATE INDEX idx_sighting_name_signal ON slack_unmatched_company_sighting (name_key, signal_type);
