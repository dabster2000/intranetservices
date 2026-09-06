-- JK Team 2.0 / WP4b — zero-rate client contracts (spec §4.4.1, D2, D3).
--
-- chk_consultant_positive_rate (V85) made a zero-rate "føl" line impossible to represent, so
-- those arrangements lived off-system. It is NOT simply relaxed to rate >= 0: an accidental
-- zero must still fail. Zero becomes legal only when declared — a reason, the hard price-rise
-- deadline, and the list rate the hours are worth (display and reporting only; never
-- work_full.rate, revenue, or an invoice total).
--
-- Hot-table care: this is the only DDL in the migration. The three columns are trailing
-- nullables (INSTANT in MariaDB); the CHECK swap takes a brief metadata lock on
-- contract_consultants. Deploy in a quiet window; the old application ignores the new
-- columns and satisfies the new CHECK with every rate > 0 row it writes. MariaDB enforces
-- CHECK constraints from 10.2 — confirm the deployed version before promoting.

-- "A quiet window" is not a guarantee: a queued exclusive metadata lock blocks
-- new shared readers on the same table, and the default lock_wait_timeout of
-- 86400s turns a blocked ALTER into a silent boot hang that ECS health-check
-- kills and rolls back. Fail fast instead (the V557 lesson).
SET SESSION lock_wait_timeout = 20;

-- IF NOT EXISTS on every statement, for the same reason V561 states: repair-at-start
-- clears a failed row from flyway_schema_history and re-runs the migration, and MariaDB
-- DDL is not transactional — so a run that added these columns and then failed on the
-- CHECK leaves them behind, and a bare ADD COLUMN can never succeed again. That is
-- exactly what happened on staging 2026-09-06.
ALTER TABLE contract_consultants
    ADD COLUMN IF NOT EXISTS zero_rate_reason VARCHAR(32)   NULL COMMENT 'PILOT_FREE | GOODWILL | INTERNAL_TRANSFER | OTHER — why the line is 0 kr',
    ADD COLUMN IF NOT EXISTS rate_review_date DATE          NULL COMMENT 'Hard deadline for the price step-up on a 0 kr line',
    ADD COLUMN IF NOT EXISTS list_rate        DECIMAL(10,2) NULL COMMENT 'Notional rate the hours are worth on a 0 kr line — display/reporting only';

ALTER TABLE contract_consultants
    DROP CONSTRAINT IF EXISTS chk_consultant_positive_rate;

-- Drop before add, the V85 pattern, so a partial previous run cannot block the retry.
ALTER TABLE contract_consultants
    DROP CONSTRAINT IF EXISTS chk_consultant_rate_declared;

ALTER TABLE contract_consultants
    ADD CONSTRAINT chk_consultant_rate_declared CHECK (
        (rate > 0)
     OR (rate = 0
         AND zero_rate_reason IS NOT NULL
         AND rate_review_date IS NOT NULL
         AND list_rate IS NOT NULL
         AND list_rate > 0)
    );
