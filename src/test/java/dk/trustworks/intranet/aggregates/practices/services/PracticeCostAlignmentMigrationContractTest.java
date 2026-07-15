package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeCostAlignmentMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V411__Align_practice_cost_to_effective_history.sql";

    @Test
    void definesImmutableBasisQueueWatermarksAndSharedControl() throws IOException {
        String sql = migration();

        for (String table : new String[]{
                "practice_basis_generation",
                "practice_user_effective_basis_mat",
                "practice_user_daily_capacity_basis_mat",
                "practice_basis_dependency_manifest_mat",
                "fact_practice_cost_generation_mat",
                "fact_practice_fte_generation_mat",
                "fact_practice_cost_completeness_generation_mat",
                "practice_cost_basis_refresh_request",
                "practice_cost_generation_signal",
                "practice_contribution_publication_control",
                "practice_revenue_source_watermark",
                "practice_revenue_async_mutation_attempt"}) {
            assertTrue(sql.contains("CREATE TABLE " + table), table);
        }

        assertTrue(sql.contains("BIGINT UNSIGNED NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("FULL_BI', 'INCREMENTAL_BI', 'PRACTICE_BASIS_INPUT'"));
        assertTrue(sql.contains("'PENDING', 'RUNNING', 'READY', 'NO_CHANGE', 'FAILED', 'SUPERSEDED'"));
        assertTrue(sql.contains("latest_cost_basis_request_id"));
        assertTrue(sql.contains("certified_cost_basis_request_vector"));
        assertTrue(sql.contains("GET_LOCK('bi_refresh', 30)"));
        assertTrue(sql.contains("refused an active FACT_USER_DAY owner"));
        assertTrue(sql.contains("async_mutation_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("async_completed_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("async_pending_count BIGINT UNSIGNED NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("UNIQUE KEY uq_prama_token (attempt_token)"));

        assertFalse(sql.contains("CALL sp_begin_practice_operating_cost_publication"));
        assertFalse(sql.contains("CALL sp_stage_practice_operating_cost_publication"));
        assertFalse(sql.contains("CALL sp_finalize_practice_operating_cost_publication"));
    }

    @Test
    void durableCostSignalCanReferenceOnlyOneChangedReadyRequestGeneration() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("PRIMARY KEY (cost_generation_at)"));
        assertTrue(sql.contains("UNIQUE KEY uq_pcgs_request (cost_basis_request_id)"));
        assertTrue(sql.contains("FOREIGN KEY (cost_basis_request_id)"));
        assertTrue(sql.contains("FOREIGN KEY (practice_basis_generation_id)"));
    }

    @Test
    void seedsOnlyTheThreeCostRelevantSourcesAndSafeServingDefaults() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("('FINANCE_GL', 0, 'READY'"));
        assertTrue(sql.contains("('ACCOUNT_CLASSIFICATION', 0, 'READY'"));
        assertTrue(sql.contains("('PRACTICE_BASIS_INPUT', 0, 'READY'"));
        assertTrue(sql.contains("VALUES (1, FALSE, FALSE, TRUE"));
        assertTrue(sql.contains("trg_accounting_accounts_practice_revenue_au"));
        assertTrue(sql.contains("trg_user_practice_history_practice_revenue_au"));
        assertTrue(sql.contains("trg_userstatus_practice_revenue_au"));
        assertTrue(sql.contains("CREATE PROCEDURE sp_enqueue_practice_cost_basis_refresh"));
        assertTrue(sql.contains("CALL sp_enqueue_practice_cost_basis_refresh('COST_GL_INPUT', 'DIRTY_MARKER'"));
        assertTrue(sql.contains("CALL sp_enqueue_practice_cost_basis_refresh('PRACTICE_BASIS_INPUT', 'DIRTY_MARKER'"));
        assertTrue(sql.contains("latest_cost_basis_request_vector=v_vector"));
    }

    @Test
    void costGenerationsAreImmutableAndNeverCertifiedByRelabelingLegacyFacts() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("PRIMARY KEY (generation_id, company_id, practice_code, month_key"));
        assertTrue(sql.contains("REFERENCES practice_basis_generation (generation_id) ON DELETE CASCADE"));
        assertFalse(sql.contains("UPDATE fact_opex_mat SET materialized_at"));
        assertFalse(sql.contains("UPDATE fact_employee_monthly_mat SET materialized_at"));
    }

    private static String migration() throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertTrue(input != null, MIGRATION);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
