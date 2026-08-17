-- =============================================================================
-- Migration V502: Persist the IntercompanyClassificationCheck drift baseline
--
-- Problem (verified against production twservices4, 2026-08-17):
--   IntercompanyClassificationCheck holds its comparison baseline in an
--   in-memory AtomicReference<Snapshot>. onStart(@Observes StartupEvent) records
--   a baseline and returns WITHOUT alerting, because a null snapshot is treated
--   as "first ever run". Every process restart therefore re-arms that first-run
--   path and silently swallows whatever drift happened across the restart.
--
--   Observed 2026-08-14: two boots recorded baselines 81,056,905 DKK apart and
--   no alert was emitted. A deploy — the single most likely moment for a
--   configuration change in e-conomic to land — reliably disarms the detector.
--
-- Fix:
--   One row per (companyuuid, year, month) cell holding the last observed
--   misposted DKK. The check reads it on boot instead of starting from null, so
--   the comparison survives restarts; the silent-baseline path now applies only
--   to a cell that has genuinely never been seen before.
--
--   misposted_dkk is DECIMAL(18,2), not the DOUBLE that finance_details.amount
--   uses: this is a comparison key, and the growth test (> 1.00 DKK) must not be
--   at the mercy of binary-float drift between two runs. The check's Snapshot
--   already truncated to whole kroner in memory for the same reason; storing the
--   øre keeps that decision in one place.
--
-- Grain matches the check's cell key exactly: companyuuid + calendar year +
--   calendar month, i.e. the same tuple as Misclassification. Composite PK, so a
--   re-run of the same cell is an UPSERT and the table stays bounded at
--   (2 subsidiaries × months observed).
--
-- Rollback:
--   DROP TABLE intercompany_classification_baseline;
--   (the check falls back to the pre-V502 in-memory behaviour)
-- =============================================================================

CREATE TABLE IF NOT EXISTS intercompany_classification_baseline (
    companyuuid    VARCHAR(36)    NOT NULL,
    year           INT            NOT NULL,
    month          INT            NOT NULL,
    misposted_dkk  DECIMAL(18, 2) NOT NULL,
    rows_count     BIGINT         NOT NULL DEFAULT 0,
    observed_at    DATETIME       NOT NULL,
    PRIMARY KEY (companyuuid, year, month)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'Last observed misposted-on-1010 DKK per (tenant x month) cell. Read by IntercompanyClassificationCheck on boot so a restart cannot suppress drift detection (V502).';

-- Verification:
--   SELECT COUNT(*) FROM intercompany_classification_baseline;
--   -- Expect: 0 immediately after migration; populated by the first check run
--   --         (04:15 UTC cron or the startup probe), then one row per cell.
