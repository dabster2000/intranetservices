package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeRevenuePublicationMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V412__Create_practice_revenue_publication.sql";

    @Test
    void definesVersionedItemAllocationDependencyLineageAndPublicationTables() throws IOException {
        String sql = migration();
        for (String table : new String[]{
                "fact_practice_net_revenue_item_mat",
                "fact_practice_net_revenue_allocation_mat",
                "fact_practice_revenue_dependency_mat",
                "practice_invoice_item_delivery_source",
                "practice_revenue_publication"}) {
            assertTrue(sql.contains("CREATE TABLE " + table), table);
        }

        assertTrue(sql.contains("PRIMARY KEY (generation_id, item_control_key)"));
        assertTrue(sql.contains("DECIMAL(65,20)"));
        assertTrue(sql.contains("DECIMAL(38,18)"));
        assertTrue(sql.contains("DECIMAL(17,8)"));
        assertTrue(sql.contains("'SOURCE_ITEM', 'DOCUMENT_RESIDUAL', 'DOCUMENT_EVIDENCE'"));
        assertTrue(sql.contains("'PM', 'BA', 'CYB', 'DEV', 'SA', 'JK', 'UD', 'EXTERNAL', 'OTHER', 'UNASSIGNED'"));
        assertTrue(sql.contains("'DATED_DELIVERY', 'SCHEDULED_CAPACITY', 'MONTH_END_PRACTICE'"));
        assertTrue(sql.contains("source_document_count"));
        assertTrue(sql.contains("valued_item_count"));
        assertTrue(sql.contains("shared_control_version"));
    }

    @Test
    void extendsTheSharedWatermarkToExactlyTheFrozenNineCategories() throws IOException {
        String combined = resource(
                "db/migration/V411__Align_practice_cost_to_effective_history.sql") + migration();
        for (String source : new String[]{
                "INVOICE_DOCUMENT", "FINANCE_GL", "CURRENCY", "ACCOUNT_CLASSIFICATION",
                "INVOICE_ATTRIBUTION", "SELF_BILLED", "PHANTOM_ATTRIBUTION",
                "DELIVERY_EVIDENCE", "PRACTICE_BASIS_INPUT"}) {
            assertTrue(combined.contains("'" + source + "'"), source);
        }
        assertTrue(combined.contains("last_fact_change_log_id"));
        assertTrue(combined.contains("last_pruned_fact_change_log_id"));
        assertTrue(combined.contains("recovery_target_fact_change_log_id"));
        assertTrue(combined.contains("retention_gap_reason"));
    }

    @Test
    void preservesHotWorkTriggerContractAndReplacesContractConsultantTriggers() throws IOException {
        String sql = migration();
        assertFalse(sql.contains(" ON work FOR EACH ROW"));
        assertFalse(sql.contains(" ON `work` FOR EACH ROW"));
        assertTrue(sql.contains("DROP TRIGGER IF EXISTS trg_contract_consultants_after_insert"));
        assertTrue(sql.contains("CREATE TRIGGER trg_contract_consultants_after_insert"));
        assertTrue(sql.contains("INTERVAL 24 MONTH"));
        assertTrue(sql.contains("'DELIVERY_EVIDENCE', 'CONTRACT_CONSULTANT'"));
        assertTrue(sql.contains("sp_mark_practice_revenue_document_and_credit_dependents_changed"));
    }

    @Test
    void freezesLegacyCreditRowsAsNoServerProof() throws IOException {
        String sql = migration();
        assertTrue(sql.contains("credit_copy_kind VARCHAR(24) NOT NULL DEFAULT 'NONE'"));
        assertTrue(sql.contains("credit_copy_kind = 'NONE'"));
        assertTrue(sql.contains("credit_copy_scope = NULL"));
        assertTrue(sql.contains("credit_copy_fingerprint = NULL"));
        assertTrue(sql.contains("source_distribution_fingerprint"));
        assertTrue(sql.contains("attribution_dependency_fingerprint"));
    }

    private static String migration() throws IOException {
        return resource(MIGRATION);
    }

    private static String resource(String name) throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            assertTrue(input != null, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
