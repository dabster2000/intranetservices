package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeContributionV413MigrationContractTest {
    private static final String MIGRATION =
            "db/migration/V413__Repair_practice_contribution_delivery_lifecycle.sql";

    @Test
    void repairsForensicCentScaleAndAddsImmutableCostWindowEvidence() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("item_cent_adjustment_dkk DECIMAL(48,4)"));
        assertTrue(sql.contains("cost_month_end_practice_fallback_employee_month_count"));
        for (String prefix : new String[]{"booked_", "booked_plus_draft_"}) {
            assertTrue(sql.contains(prefix + "available BOOLEAN"));
            assertTrue(sql.contains(prefix + "reason VARCHAR(64)"));
            assertTrue(sql.contains(prefix + "anchor_month DATE"));
            assertTrue(sql.contains(prefix + "current_start_month DATE"));
            assertTrue(sql.contains(prefix + "current_end_month DATE"));
            assertTrue(sql.contains(prefix + "prior_start_month DATE"));
            assertTrue(sql.contains(prefix + "prior_end_month DATE"));
        }
        assertTrue(sql.contains("chk_pocp_booked_window_shape"));
        assertTrue(sql.contains("chk_pocp_booked_plus_draft_window_shape"));
    }

    @Test
    void darkCursorCatchUpCannotSkipADeletedUnconsumedGapOrPublishedGeneration() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("last_pruned_fact_change_log_id <= w.last_fact_change_log_id"));
        assertTrue(sql.contains("last_pruned_fact_change_log_id > last_fact_change_log_id"));
        assertTrue(sql.contains("retention_gap_reason = 'FACT_CHANGE_LOG_RETENTION_GAP'"));
        assertTrue(sql.contains("GREATEST(\n           w.last_fact_change_log_id"));
        assertTrue(sql.contains("p.status = 'UNINITIALIZED'"));
        assertTrue(sql.contains("p.published_generation_id IS NULL"));
        assertTrue(sql.contains("p.previous_generation_id IS NULL"));
        assertTrue(sql.contains("p.attempt_generation_id IS NULL"));
        assertTrue(sql.contains("c.refresh_enabled = FALSE"));
        assertTrue(sql.contains("c.contribution_serving_enabled = FALSE"));
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM fact_practice_net_revenue_item_mat)"));
    }

    @Test
    void dependencyMarkerUsesOnlyPublishedAndAttemptGenerations() throws IOException {
        String sql = migration();
        int start = sql.indexOf("CREATE PROCEDURE sp_mark_practice_revenue_dependency_changed");
        String procedure = sql.substring(start);
        assertTrue(procedure.contains("p.published_generation_id, p.attempt_generation_id"));
        assertFalse(procedure.contains("p.previous_generation_id"));
    }

    @Test
    void supersessionProcedureOnlyRetiresStrictlyOlderPendingRequests() throws IOException {
        String sql = migration();
        int start = sql.indexOf("CREATE PROCEDURE sp_supersede_dominated_cost_requests");
        assertTrue(start > 0, "sp_supersede_dominated_cost_requests");
        String procedure = sql.substring(start);
        assertTrue(procedure.contains("status = 'SUPERSEDED'"));
        assertTrue(procedure.contains("superseded_by_request_id = p_new_request_id"));
        // Never supersede the newest, never self-link: only strictly-older PENDING rows are touched.
        assertTrue(procedure.contains("status = 'PENDING' AND request_id < p_new_request_id"));
    }

    @Test
    void everyEnqueueProducerRetiresDominatedRequestsAfterAdvancingTheLatestPointer() throws IOException {
        String sql = migration();
        for (String producer : new String[]{
                "CREATE PROCEDURE sp_enqueue_practice_cost_basis_refresh",
                "CREATE PROCEDURE sp_nightly_bi_refresh",
                "CREATE PROCEDURE sp_incremental_bi_refresh",
                "CREATE PROCEDURE sp_advance_practice_dependency_manifest_input"}) {
            int start = sql.indexOf(producer);
            assertTrue(start > 0, producer);
            String next = sql.indexOf("CREATE PROCEDURE", start + producer.length()) < 0
                    ? sql.substring(start)
                    : sql.substring(start, sql.indexOf("CREATE PROCEDURE", start + producer.length()));
            assertTrue(next.contains("CALL sp_supersede_dominated_cost_requests"),
                    producer + " must supersede dominated requests");
        }
    }

    @Test
    void dependencyManifestEscalationAdvancesMonotonicVersionAndIsIdempotentPerFingerprint()
            throws IOException {
        String sql = migration();
        int start = sql.indexOf("CREATE PROCEDURE sp_advance_practice_dependency_manifest_input");
        assertTrue(start > 0, "sp_advance_practice_dependency_manifest_input");
        String procedure = sql.substring(start);
        assertTrue(procedure.contains(
                "dependency_manifest_input_version = dependency_manifest_input_version + 1"));
        assertTrue(procedure.contains("cause='DEPENDENCY_MANIFEST_INPUT' AND status='PENDING'"));
        assertTrue(procedure.contains("dependency_fingerprint <=> p_dependency_fingerprint"));
        assertTrue(procedure.contains("dependency_fingerprint, affected_start_date, affected_end_date"));
    }

    @Test
    void stalePendingRepairSupersedesEveryDominatedPendingRowAndIsNoOpSafe() throws IOException {
        String sql = migration();
        int repair = sql.lastIndexOf("UPDATE practice_cost_basis_refresh_request r");
        assertTrue(repair > 0, "stale PENDING repair");
        String update = sql.substring(repair);
        assertTrue(update.contains("SELECT MAX(request_id) AS newest FROM practice_cost_basis_refresh_request"));
        assertTrue(update.contains("SET r.status = 'SUPERSEDED'"));
        assertTrue(update.contains("r.superseded_by_request_id = m.newest"));
        // No-op / idempotent: only PENDING rows strictly below the newest request are repaired.
        assertTrue(update.contains("WHERE r.status = 'PENDING' AND r.request_id < m.newest"));
    }

    @Test
    void nightlyFullBiRequestCapturesTheLiveIncrementalVersionNeverHardcodedZero() throws IOException {
        String sql = migration();
        int start = sql.indexOf("CREATE PROCEDURE sp_nightly_bi_refresh");
        assertTrue(start > 0, "sp_nightly_bi_refresh");
        String proc = sql.substring(start,
                sql.indexOf("DROP PROCEDURE IF EXISTS sp_incremental_bi_refresh"));
        // Capture the live incremental version alongside the full version.
        assertTrue(proc.contains(
                "SELECT full_refresh_version, incremental_refresh_version\n      INTO v_full_version, v_incremental_version"),
                "nightly must capture the live incremental version");
        // The FULL_BI request and its vector use the captured version, not a hardcoded 0.
        assertTrue(proc.contains(
                "v_full_version, v_incremental_version, v_basis_version, v_finance_version"),
                "FULL_BI expected-version columns must use the captured incremental version");
        assertTrue(proc.contains(
                "SHA2(CONCAT_WS('|', v_full_version, v_incremental_version, v_basis_version"),
                "the FULL_BI input vector must include the captured incremental version");
        assertFalse(proc.contains("v_full_version, 0, v_basis_version"),
                "no hardcoded 0 incremental version may remain in the nightly producer");
    }

    private static String migration() throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertTrue(input != null, MIGRATION);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
