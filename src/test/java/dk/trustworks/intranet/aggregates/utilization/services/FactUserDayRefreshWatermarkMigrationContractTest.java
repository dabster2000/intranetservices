package dk.trustworks.intranet.aggregates.utilization.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactUserDayRefreshWatermarkMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V410__Track_fact_user_day_refresh_watermark.sql";

    @Test
    void migrationCertifiesOnlySuccessfulFullRefreshAndPreservesIncrementalCertification()
            throws IOException {
        String sql = migrationSql();

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS bi_refresh_watermark"));
        assertTrue(sql.contains("certified_complete_through_date DATE NULL"));
        assertTrue(sql.contains("'UNINITIALIZED', 'RUNNING', 'READY', 'FAILED'"));
        assertTrue(sql.contains("active_refresh_token CHAR(36) NULL"));
        assertTrue(sql.contains("VALUES ('FACT_USER_DAY', NULL, NULL, NULL, 'UNINITIALIZED', NULL)"));

        int fullBegin = sql.indexOf("CALL sp_begin_practice_operating_cost_publication(v_refresh_token);");
        int fullFacts = sql.indexOf("CALL sp_refresh_fact_tables();");
        int fullOpex = sql.indexOf("CALL sp_refresh_practice_opex_mat();");
        int fullPublication = sql.indexOf("CALL sp_stage_practice_operating_cost_publication(v_refresh_token);");
        int fullFinalize = sql.indexOf("CALL sp_finalize_practice_operating_cost_publication(v_refresh_token);");
        int certification = sql.indexOf("SET certified_complete_through_date = CASE");
        int fullCommit = sql.indexOf("COMMIT;", fullFinalize);
        assertTrue(fullBegin >= 0 && fullFacts > fullBegin && fullOpex > fullFacts
                        && fullPublication > fullOpex
                        && certification > fullPublication && fullFinalize > certification
                        && fullCommit > fullFinalize,
                "actual and cost publication must be finalized together after all pipeline work succeeds");
        assertTrue(sql.contains("SET v_copenhagen_today = DATE(CONVERT_TZ("));
        assertTrue(sql.contains("UTC_TIMESTAMP(), 'UTC', 'Europe/Copenhagen')"));
        assertTrue(sql.contains("DATE_SUB(v_copenhagen_today, INTERVAL p_lookback_months MONTH)"));
        assertTrue(sql.contains("DATE_ADD(v_copenhagen_today, INTERVAL p_forward_months MONTH)"));
        assertFalse(sql.contains("CURDATE()"),
                "refresh bounds and certification must use the same Copenhagen reporting date");
        assertTrue(sql.contains("WHEN v_start <= v_certified_date AND v_end > v_certified_date"));

        String incremental = sql.substring(sql.indexOf("CREATE PROCEDURE sp_incremental_bi_refresh"));
        assertTrue(incremental.contains("CALL sp_begin_practice_operating_cost_publication(v_refresh_token);"));
        assertTrue(incremental.contains("CALL sp_refresh_practice_opex_mat();"));
        assertTrue(incremental.contains("CALL sp_stage_practice_operating_cost_publication(v_refresh_token);"));
        assertTrue(incremental.contains("CALL sp_finalize_practice_operating_cost_publication(v_refresh_token);"));
        assertTrue(incremental.contains("last_incremental_refresh_at = UTC_TIMESTAMP(6)"));
        assertTrue(incremental.contains("refresh_state = v_previous_refresh_state"));
        int incrementalStage = incremental.indexOf(
                "CALL sp_stage_practice_operating_cost_publication(v_refresh_token);");
        int changesProcessed = incremental.indexOf("UPDATE fact_change_log", incrementalStage);
        int incrementalState = incremental.indexOf(
                "SET last_incremental_refresh_at = UTC_TIMESTAMP(6)", changesProcessed);
        int incrementalFinalize = incremental.indexOf(
                "CALL sp_finalize_practice_operating_cost_publication(v_refresh_token);",
                incrementalState);
        int incrementalCommit = incremental.indexOf("COMMIT;", incrementalFinalize);
        assertTrue(changesProcessed > incrementalStage && incrementalState > changesProcessed
                        && incrementalFinalize > incrementalState && incrementalCommit > incrementalFinalize,
                "change-log, actual watermark, and cost publication must commit as one final step");
        assertTrue(sql.contains("FACT_USER_DAY full refresh could not be started"));
        assertTrue(sql.contains("FACT_USER_DAY incremental refresh could not be started"));
        assertTrue(sql.contains("FACT_USER_DAY full-refresh publication could not be certified"));
        assertTrue(sql.contains("FACT_USER_DAY incremental refresh state could not be restored"));
        assertTrue(incremental.contains("active_refresh_token = v_refresh_token"));
        assertFalse(incremental.contains("SET certified_complete_through_date"));
        assertFalse(sql.contains("fact_user_day.last_update" + " FROM"),
                "migration must not bootstrap from row update timestamps");
    }

    @Test
    void failureHandlersMarkBothPublicationsFailedBeforeReleasingLock() throws IOException {
        String sql = migrationSql();

        String release = "DO RELEASE_LOCK('bi_refresh');";
        String failed = "SET refresh_state = 'FAILED'";
        String failPractice = "CALL sp_fail_practice_operating_cost_publication(v_refresh_token);";
        int firstHandler = sql.indexOf("DECLARE EXIT HANDLER FOR SQLEXCEPTION");
        int firstRelease = sql.indexOf(release, firstHandler);
        int firstFailed = sql.indexOf(failed, firstHandler);
        int firstPracticeFailed = sql.indexOf(failPractice, firstHandler);
        int firstRollback = sql.indexOf("ROLLBACK;", firstHandler);
        assertTrue(firstRollback > firstHandler && firstPracticeFailed > firstRollback
                && firstFailed > firstPracticeFailed
                && firstRelease > firstFailed);

        int secondHandler = sql.indexOf("DECLARE EXIT HANDLER FOR SQLEXCEPTION", firstHandler + 1);
        int secondRelease = sql.indexOf(release, secondHandler);
        int secondFailed = sql.indexOf(failed, secondHandler);
        int secondPracticeFailed = sql.indexOf(failPractice, secondHandler);
        int secondRollback = sql.indexOf("ROLLBACK;", secondHandler);
        assertTrue(secondRollback > secondHandler && secondPracticeFailed > secondRollback
                && secondFailed > secondPracticeFailed
                && secondRelease > secondFailed);
        assertTrue(sql.indexOf("RESIGNAL;", firstFailed) > firstFailed);
        assertTrue(sql.indexOf("RESIGNAL;", secondFailed) > secondFailed);
    }

    private static String migrationSql() throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) throw new IOException("Missing migration " + MIGRATION);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
