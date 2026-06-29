-- =============================================================================
-- V385: Partner-bonus payout events + per-invoice "consumed" marker
--
-- GOAL
--   Guarantee that an invoice's APPROVED bonus rows can only ever fund ONE
--   partner-bonus payout. Today the sales bonus is recomputed live from every
--   APPROVED InvoiceBonus row in the fiscal-year window on each payout, with no
--   record of which invoices were already consumed — so a re-run, or an invoice
--   that re-buckets across the FY boundary, could fund the same invoice twice.
--
-- WHAT THIS ADDS
--   1. partner_bonus_payouts — one row per (partner group, fiscal year) payout
--      event. It freezes the group sales basis + per-partner amount the first
--      time any partner in the group is paid for that FY, so the other partners
--      draw the same frozen split without re-consuming invoices.
--   2. invoice_bonuses.payout_uuid — nullable marker stamped on the APPROVED
--      bonus rows that were consumed by a payout event. The payout recompute
--      sums only rows WHERE payout_uuid IS NULL, so consumed invoices can never
--      fund a second bonus.
--
-- IDEMPOTENT / RE-RUNNABLE
--   Every statement uses IF [NOT] EXISTS so a partially-applied or interrupted
--   run (MariaDB auto-commits each DDL; a killed task can leave the column
--   behind) re-runs cleanly. No explicit ALGORITHM/LOCK clause — the default
--   online DDL is used (LOCK=NONE is not accepted for the index add on this
--   table). repair-at-start clears any failed schema-history row before retry.
--   payout_uuid stays nullable with no default → existing rows are "unconsumed".
--   A one-time backfill (admin endpoint, dry-run first) stamps the invoices that
--   already funded paid fiscal years. No FK constraints (app maintains
--   integrity) to avoid charset/collation coupling and canary alter hazards.
-- =============================================================================

CREATE TABLE IF NOT EXISTS partner_bonus_payouts (
    uuid                    VARCHAR(36)  NOT NULL,
    partner_group_uuid      VARCHAR(36)  NOT NULL,
    fiscal_year             INT          NOT NULL,
    payout_month            DATE         NULL,
    computed_sales_basis    DOUBLE       NOT NULL DEFAULT 0,
    sales_bonus_per_partner DOUBLE       NOT NULL DEFAULT 0,
    partner_count           INT          NOT NULL DEFAULT 0,
    is_backfill             TINYINT(1)   NOT NULL DEFAULT 0,
    created_at              DATETIME     NOT NULL,
    created_by              VARCHAR(36)  NULL,
    CONSTRAINT pk_partner_bonus_payouts PRIMARY KEY (uuid),
    CONSTRAINT uq_partner_bonus_payouts_group_fy UNIQUE (partner_group_uuid, fiscal_year)
);

-- Per-invoice consumed marker (nullable → existing rows are "unconsumed").
ALTER TABLE invoice_bonuses
    ADD COLUMN IF NOT EXISTS payout_uuid VARCHAR(36) NULL;

CREATE INDEX IF NOT EXISTS idx_invoice_bonuses_payout
    ON invoice_bonuses (payout_uuid);
