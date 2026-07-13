package dk.trustworks.intranet.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndividualBonusMonthlyMigrationContractTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void monthly_schema_is_expand_only_and_keeps_legacy_json_untouched() throws IOException {
        String migration = read("V402__Extend_individual_bonus_monthly_model.sql");

        for (String column : List.of(
                "revision", "earning_month", "pay_month", "company_uuid",
                "materialization_status", "snapshot_version", "calculation_snapshot",
                "calculation_fingerprint", "actor_uuid", "salary_lump_sum_uuid", "facts_as_of")) {
            assertTrue(migration.contains(column), "V402 must add " + column);
        }
        assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS"));
        assertTrue(migration.contains("idx_individual_bonus_payout_rule_earning"));
        assertTrue(migration.contains("idx_individual_bonus_payout_user_earning"));
        assertTrue(migration.contains("idx_individual_bonus_payout_company_pay_status"));
        assertFalse(migration.toUpperCase().contains("UPDATE INDIVIDUAL_BONUS_RULE"));
        assertFalse(migration.toUpperCase().contains("UPDATE INDIVIDUAL_BONUS_PAYOUT"));
    }

    @Test
    void proof_storage_contains_hashes_but_no_raw_token_column() throws IOException {
        String migration = read("V403__Create_individual_bonus_preview_proof.sql");

        assertTrue(migration.contains("token_hash"));
        assertTrue(migration.contains("payload_hash"));
        assertTrue(migration.contains("consumed_at"));
        assertTrue(migration.contains("individual_bonus_create_idempotency"));
        assertFalse(migration.matches("(?s).*\\braw_token\\b.*"));
    }

    @Test
    void adjustment_storage_has_race_guards_and_immutable_revision_identity() throws IOException {
        String migration = read("V404__Create_individual_bonus_adjustment.sql");

        assertTrue(migration.contains("PRIMARY KEY (rule_uuid, earning_month)"));
        assertTrue(migration.contains("uk_individual_bonus_adjustment_reconciliation"));
        assertTrue(migration.contains("uk_individual_bonus_adjustment_source"));
        assertTrue(migration.contains("uk_individual_bonus_adjustment_revision"));
        assertTrue(migration.contains("old_snapshot"));
        assertTrue(migration.contains("new_snapshot"));
        assertTrue(migration.contains("version"));
    }

    @Test
    void protected_audit_storage_is_append_only_by_schema_contract() throws IOException {
        String migration = read("V405__Create_individual_bonus_audit_event.sql");

        assertTrue(migration.contains("individual_bonus_audit_event"));
        assertTrue(migration.contains("before_hash"));
        assertTrue(migration.contains("after_hash"));
        assertTrue(migration.contains("proof_action"));
        assertFalse(migration.toUpperCase().contains("UPDATE INDIVIDUAL_BONUS_AUDIT_EVENT"));
        assertFalse(migration.toUpperCase().contains("DELETE FROM INDIVIDUAL_BONUS_AUDIT_EVENT"));
    }

    private static String read(String name) throws IOException {
        return Files.readString(MIGRATIONS.resolve(name));
    }
}
