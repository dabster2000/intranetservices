-- =============================================================================
-- Migration V456: Reduce lock contention between the incremental BI drain and
-- user-facing saves on `work`
--
-- Background (2026-07-24 prod incident)
--   Four POST /work requests hit 1205 lock-wait timeouts (full 50s waits) while
--   ev_bi_incremental_refresh (V164, every 5 min) drained a ~3400-row
--   fact_change_log backlog via sp_incremental_bi_refresh (V417 body). Two
--   contention mechanisms:
--
--   1. sp_aggregate_work's `UPDATE fact_user_day JOIN (SELECT .. FROM
--      work_full ..)` locks every scanned work row for the statement duration
--      — verified on MariaDB 10.11 to block a concurrent `UPDATE work` at BOTH
--      REPEATABLE READ and READ COMMITTED (multi-table UPDATE reads are
--      locking reads even for the join-only table). The drain runs it once per
--      affected user-month, back-to-back, S-locking month-wide ranges of the
--      very rows consultants are saving. (The pure INSERT..SELECT shapes —
--      sp_recalculate_availability's work-derived tables and
--      sp_refresh_fact_tables' actual_rates CTE feeding INSERT IGNORE — were
--      verified NOT to lock work rows on this engine config, and are
--      additionally exempted from source locking by READ COMMITTED, see (a).)
--
--   2. The terminal marking transaction ran
--      `UPDATE fact_change_log SET processed_at = NOW() WHERE processed_at IS NULL`
--      over the whole backlog in one explicit transaction. That next-key-locks
--      the `processed_at IS NULL` prefix of idx_unprocessed INCLUDING the
--      insert gap, so trg_work_after_update's INSERT INTO fact_change_log
--      blocked inside the user's save transaction — surfacing as the user's
--      UPDATE work waiting. Reproduced verbatim: a trigger-style insert hits
--      1205 against the old marking shape, and completes in ~60ms against the
--      new one.
--
-- Changes vs. the V417 body (recalc pipeline itself is unchanged)
--   a. The drain session runs at READ COMMITTED (saved/restored around the
--      body, incl. the error path). READ COMMITTED makes INSERT..SELECT source
--      reads consistent (non-locking) in every binlog configuration, and drops
--      gap locking on the fact_change_log marking UPDATEs. It does NOT fix the
--      multi-table UPDATE in sp_aggregate_work — that proc is redefined below.
--   b. The backlog is snapshotted by `v_max_id = MAX(id) of pending rows` at
--      start. Only rows with id <= v_max_id are drained AND marked, so rows
--      inserted while the drain runs are never marked processed without being
--      recalculated (latent data-loss bug in the V417 body), and the marking
--      UPDATEs never touch the index gap where concurrent trigger inserts land.
--   c. Marking is done per user-month in autocommit statements (no explicit
--      wrapping transaction), right after the fact refresh — each statement
--      holds row locks for milliseconds instead of holding the whole pending
--      index range for the duration of the drain.
--   d. One drain iteration processes at most 200 user-months (oldest change
--      first). Anything beyond the cap stays pending and is picked up by the
--      next 5-minute event run, bounding worst-case drain duration after a
--      backlog spike. Unmarked-but-capped rows are NOT marked (per-user-month
--      marking only covers the ranges actually recalculated).
--   e. sp_aggregate_work (V168 body) is redefined: the work_full aggregate is
--      snapshotted into a session temp table with a plain INSERT..SELECT
--      (non-locking source read), and the multi-table UPDATE then joins
--      fact_user_day against the session-private temp only — no lock ever
--      touches work/work_full. Output is byte-identical to the old body.
--      This fixes ALL callers (incremental drain + sp_nightly_bi_refresh).
--
-- Idempotency: drop-and-recreate; safe to re-apply.
-- Callers of sp_incremental_bi_refresh: only the MariaDB event
-- ev_bi_incremental_refresh (V164). No Java callers of either proc.
-- sp_nightly_bi_refresh does not mark fact_change_log and is untouched.
-- =============================================================================

DROP PROCEDURE IF EXISTS sp_incremental_bi_refresh;

DELIMITER $$

CREATE PROCEDURE sp_incremental_bi_refresh()
proc_body: BEGIN
    DECLARE v_max_id BIGINT DEFAULT 0;
    DECLARE v_month_cap INT DEFAULT 200;
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE v_previous_refresh_state VARCHAR(16) DEFAULT 'UNINITIALIZED';
    DECLARE v_refresh_token CHAR(36);
    DECLARE v_old_isolation VARCHAR(32) DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;
        IF v_old_isolation IS NOT NULL THEN
            SET @@session.tx_isolation = v_old_isolation;
        END IF;
        IF v_lock_acquired = 1 THEN
            UPDATE bi_refresh_watermark
               SET refresh_state = 'FAILED', active_refresh_token = NULL
             WHERE pipeline_name = 'FACT_USER_DAY'
               AND active_refresh_token = v_refresh_token;
            DO RELEASE_LOCK('bi_refresh');
        END IF;
        RESIGNAL;
    END;

    SELECT GET_LOCK('bi_refresh', 0) INTO v_lock_acquired;
    IF COALESCE(v_lock_acquired, 0) <> 1 THEN LEAVE proc_body; END IF;

    -- Snapshot the backlog upper bound. Rows inserted after this point get a
    -- higher id and are left for the next run — both for recalculation and for
    -- marking, so the marking never scans the live insert gap.
    SELECT COALESCE(MAX(id), 0) INTO v_max_id
      FROM fact_change_log WHERE processed_at IS NULL;
    IF v_max_id = 0 THEN
        DO RELEASE_LOCK('bi_refresh');
        LEAVE proc_body;
    END IF;

    SELECT refresh_state INTO v_previous_refresh_state
      FROM bi_refresh_watermark WHERE pipeline_name = 'FACT_USER_DAY';
    SET v_refresh_token = UUID();
    UPDATE bi_refresh_watermark
       SET refresh_state = 'RUNNING', active_refresh_token = v_refresh_token
     WHERE pipeline_name = 'FACT_USER_DAY' AND active_refresh_token IS NULL;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY incremental refresh could not be started';
    END IF;

    -- READ COMMITTED for the whole drain: INSERT..SELECT / UPDATE..JOIN reads
    -- stop taking shared next-key locks on work/work_full, and the marking
    -- UPDATEs stop gap-locking fact_change_log's pending index range.
    SET v_old_isolation = @@session.tx_isolation;
    SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

    CREATE TEMPORARY TABLE tmp_affected_ranges (
        useruuid VARCHAR(36) NOT NULL,
        month_start DATE NOT NULL,
        month_end DATE NOT NULL,
        PRIMARY KEY (useruuid, month_start)
    ) ENGINE=MEMORY;

    INSERT IGNORE INTO tmp_affected_ranges (useruuid, month_start, month_end)
    SELECT useruuid,
           DATE_FORMAT(MIN(affected_date), '%Y-%m-01'),
           DATE_FORMAT(MIN(affected_date) + INTERVAL 1 MONTH, '%Y-%m-01')
      FROM fact_change_log
     WHERE processed_at IS NULL
       AND id <= v_max_id
     GROUP BY useruuid, DATE_FORMAT(affected_date, '%Y-%m-01')
     ORDER BY MIN(id)
     LIMIT v_month_cap;

    BEGIN
        DECLARE done INT DEFAULT FALSE;
        DECLARE v_useruuid VARCHAR(36);
        DECLARE v_month_start DATE;
        DECLARE v_month_end DATE;
        DECLARE cur CURSOR FOR
            SELECT useruuid, month_start, month_end FROM tmp_affected_ranges;
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
        OPEN cur;
        read_loop: LOOP
            FETCH cur INTO v_useruuid, v_month_start, v_month_end;
            IF done THEN LEAVE read_loop; END IF;
            CALL sp_recalculate_availability(v_month_start, v_month_end, v_useruuid);
            CALL sp_aggregate_work(v_month_start, v_month_end);
            CALL sp_recalculate_budgets(v_month_start, v_month_end, v_useruuid);
        END LOOP;
        CLOSE cur;
    END;

    CALL sp_refresh_fact_tables();
    CALL sp_refresh_opex_mat_post_pass();

    -- Mark only what was actually recalculated: per user-month, bounded by
    -- v_max_id, one autocommit statement each. No explicit transaction — each
    -- statement releases its row locks immediately, and concurrent trigger
    -- inserts (id > v_max_id) are never in the scanned range.
    BEGIN
        DECLARE done INT DEFAULT FALSE;
        DECLARE v_useruuid VARCHAR(36);
        DECLARE v_month_start DATE;
        DECLARE v_month_end DATE;
        DECLARE cur_mark CURSOR FOR
            SELECT useruuid, month_start, month_end FROM tmp_affected_ranges;
        DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
        OPEN cur_mark;
        mark_loop: LOOP
            FETCH cur_mark INTO v_useruuid, v_month_start, v_month_end;
            IF done THEN LEAVE mark_loop; END IF;
            UPDATE fact_change_log
               SET processed_at = NOW()
             WHERE processed_at IS NULL
               AND id <= v_max_id
               AND useruuid = v_useruuid
               AND affected_date >= v_month_start
               AND affected_date < v_month_end;
        END LOOP;
        CLOSE cur_mark;
    END;

    START TRANSACTION;
    UPDATE bi_refresh_watermark
       SET last_incremental_refresh_at = UTC_TIMESTAMP(6),
           incremental_refresh_version = incremental_refresh_version + 1,
           refresh_state = v_previous_refresh_state,
           active_refresh_token = NULL
     WHERE pipeline_name = 'FACT_USER_DAY'
       AND active_refresh_token = v_refresh_token;
    IF ROW_COUNT() <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'FACT_USER_DAY incremental refresh could not be certified';
    END IF;
    COMMIT;

    DROP TEMPORARY TABLE IF EXISTS tmp_affected_ranges;
    SET @@session.tx_isolation = v_old_isolation;
    DO RELEASE_LOCK('bi_refresh');
END$$

DELIMITER ;

-- =============================================================================
-- sp_aggregate_work: decouple the work_full read from the locking UPDATE.
-- The V168 body's `UPDATE fact_user_day JOIN (SELECT .. FROM work_full ..)`
-- takes locks on every work row scanned by the derived table, at both
-- REPEATABLE READ and READ COMMITTED (verified on MariaDB 10.11) — blocking
-- concurrent user-facing work saves for the statement duration. Snapshot the
-- aggregate into a session temp table first (plain INSERT..SELECT = non-locking
-- source read), then join fact_user_day against the temp only. Semantics are
-- unchanged: zero-reset of the period, then per user-day billable hours and
-- revenue from work_full where rate > 0.
-- =============================================================================

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

    INSERT INTO tmp_work_day_agg (useruuid, registered, billable_hours, revenue)
    SELECT useruuid, registered,
        SUM(CASE WHEN rate > 0 THEN workduration ELSE 0 END),
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
