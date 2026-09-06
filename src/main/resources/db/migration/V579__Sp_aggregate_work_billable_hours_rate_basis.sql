-- JK Team 2.0 / WP4c — sp_aggregate_work billability (spec §4.4c.3, decision D3).
--
-- WHY THIS ONE MATTERS MOST
-- -------------------------
-- Almost every hours-counting site in the codebase already filters
-- `consultant_type = 'CONSULTANT'`, so juniors (STUDENT) never reach the rate test and
-- are unaffected by a declared zero. sp_aggregate_work does NOT: it has no type
-- predicate at all. It writes fact_user_day.registered_billable_hours for every user
-- from `SUM(CASE WHEN rate > 0 ...)`, which is the documented single source of truth for
-- all utilization queries (users-domain-compact R8).
--
-- So a junior on a føl contract does not merely read oddly in one report — a *wrong
-- stored fact* is written for them every refresh. That is what this migration fixes.
--
-- THE CHANGE
-- ----------
-- Hours use the V578 discriminator:   rate_basis <> 'NO_CONTRACT'
-- Revenue keeps the money test:       rate > 0
--
-- Those two now legitimately diverge for a zero-rate line: hours count, revenue is 0.
-- That divergence is the point — it is how a føl engagement becomes visible as sold-out
-- capacity earning nothing — and it is the one thing to tell finance before they see it.
--
-- SCOPE OF EFFECT
-- ---------------
-- Verified 2026-09-06: zero declared zero-rate consultant lines exist in production, so
-- this changes NO existing row. It is forward-only; no backfill or recompute is needed.
-- If that is no longer true when this is promoted, re-run the exposure check in
-- docs/design/billability-zero-rate-inventory.md §8 and add a fact_user_day recompute for
-- the affected range to the release.
--
-- Everything else in the procedure — the InnoDB temp table, the non-locking snapshot
-- read, the zero-reset, the join back to fact_user_day — is byte-identical to V456. The
-- lock-contention design that migration introduced is preserved deliberately; only the
-- two SUM predicates differ. Rollback is re-applying V456's procedure body.

DROP PROCEDURE IF EXISTS sp_aggregate_work;

DELIMITER $$

CREATE PROCEDURE sp_aggregate_work(
    IN p_start_date DATE,
    IN p_end_date   DATE
)
BEGIN
    DROP TEMPORARY TABLE IF EXISTS tmp_work_day_agg;
    -- InnoDB (not MEMORY): sp_nightly_bi_refresh calls this over multi-year
    -- ranges that can exceed max_heap_table_size.
    CREATE TEMPORARY TABLE tmp_work_day_agg (
        useruuid       VARCHAR(36) NOT NULL,
        registered     DATE        NOT NULL,
        billable_hours DOUBLE      NOT NULL,
        revenue        DOUBLE      NOT NULL,
        PRIMARY KEY (useruuid, registered)
    ) ENGINE=InnoDB;

    -- billable_hours: sold work, whether or not it was priced (WP4c / D3).
    -- revenue:        money, which a declared zero genuinely does not produce.
    INSERT INTO tmp_work_day_agg (useruuid, registered, billable_hours, revenue)
    SELECT useruuid, registered,
        SUM(CASE WHEN rate_basis <> 'NO_CONTRACT' THEN workduration ELSE 0 END),
        SUM(CASE WHEN rate > 0 THEN workduration * rate ELSE 0 END)
    FROM work_full
    WHERE registered >= p_start_date AND registered < p_end_date
    GROUP BY useruuid, registered;

    -- Reset billable hours and revenue for the period
    UPDATE fact_user_day
    SET registered_billable_hours = 0,
        registered_amount = 0,
        last_update = NOW()
    WHERE document_date >= p_start_date
      AND document_date < p_end_date;

    -- Apply the snapshot — locks only fact_user_day and the session temp
    UPDATE fact_user_day bdd
    JOIN tmp_work_day_agg w
      ON bdd.useruuid = w.useruuid AND bdd.document_date = w.registered
    SET bdd.registered_billable_hours = w.billable_hours,
        bdd.registered_amount = w.revenue,
        bdd.last_update = NOW()
    WHERE bdd.document_date >= p_start_date
      AND bdd.document_date < p_end_date;

    DROP TEMPORARY TABLE IF EXISTS tmp_work_day_agg;
END$$

DELIMITER ;
