package dk.trustworks.intranet.contracts.services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration semantics for V395 (purpose column) and V396 (ADMIN_FEE_PERCENT retype),
 * spec §8.2 — Framework Agreements Phase 3.
 *
 * <p>Flyway applies both migrations on {@code @QuarkusTest} boot against the local dev DB.
 * The local DB is schema-focused (the V98 seed rows may or may not survive local resets), so:
 * <ul>
 *   <li>column presence/shape and Flyway success are asserted directly,</li>
 *   <li>the prod-seeded admin rows (ids 2/6/8: ski21721-admin 2%, ski21725-admin 4%,
 *       ski21525-admin 4%) are asserted to be retyped <em>if present</em>,</li>
 *   <li>the retype/tag/idempotency semantics are proven by inserting fixture rows that mirror
 *       the exact prod shapes and executing the real V396 statements (read from the classpath)
 *       inside a rolled-back transaction — twice, to prove idempotency.</li>
 * </ul>
 */
@QuarkusTest
class PricingRulePurposeMigrationTest {

    private static final String V396_RESOURCE = "db/migration/V396__Retype_admin_fee_rules.sql";

    /** Rule IDs of the three ADMIN_FEE_PERCENT rows seeded by V98 and present in prod. */
    private static final String PROD_ADMIN_RULE_IDS = "'ski21721-admin','ski21725-admin','ski21525-admin'";

    @Inject
    EntityManager em;

    // --- V395: column presence and shape ---

    @Test
    void purposeColumn_existsAsNullableVarchar20() {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pricing_rule_steps' AND COLUMN_NAME = 'purpose'")
                .getSingleResult();
        assertNotNull(row, "V395 must add pricing_rule_steps.purpose");
        assertEquals("varchar(20)", row[0].toString().toLowerCase(), "purpose must be VARCHAR(20)");
        assertEquals("YES", row[1].toString(), "purpose must be nullable");
    }

    @Test
    void flywayHistory_v395AndV396_appliedSuccessfully() {
        // The shared local dev DB may carry several history rows per version: checkouts
        // without these files mark them deleted via repair-at-start, and out-of-order
        // re-applies them (V395/V396 are idempotent). What must hold is that the LATEST
        // entry per version is a successfully applied SQL migration — not a failed run
        // or a dangling DELETE marker.
        for (String version : List.of("395", "396")) {
            Object[] latest = (Object[]) em.createNativeQuery(
                    "SELECT type, success FROM flyway_schema_history WHERE version = ?1 " +
                    "ORDER BY installed_rank DESC LIMIT 1")
                    .setParameter(1, version)
                    .getSingleResult();
            assertEquals("SQL", latest[0].toString(), "V" + version + " latest history entry must be an applied SQL migration");
            // MariaDB tinyint(1) maps to Boolean here
            assertTrue(latest[1] instanceof Boolean ? (Boolean) latest[1] : ((Number) latest[1]).intValue() == 1,
                    "V" + version + " latest application must have succeeded");
        }
    }

    // --- V396 applied on boot: prod-seeded rows retyped IF present ---

    @Test
    void prodSeededAdminRows_ifPresent_areRetypedAndTagged() {
        // Local DB may or may not carry the V98 seed rows; whatever exists must be retyped.
        List<?> leftovers = em.createNativeQuery(
                "SELECT rule_id FROM pricing_rule_steps " +
                "WHERE rule_id IN (" + PROD_ADMIN_RULE_IDS + ") " +
                "AND (rule_step_type <> 'PERCENT_DISCOUNT_ON_SUM' OR purpose IS NULL OR purpose <> 'ADMIN_FEE')")
                .getResultList();
        assertTrue(leftovers.isEmpty(),
                "V98-seeded admin rows must be PERCENT_DISCOUNT_ON_SUM + purpose ADMIN_FEE after V396, found: " + leftovers);

        Number remainingAdminType = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM pricing_rule_steps WHERE rule_step_type = 'ADMIN_FEE_PERCENT' " +
                "AND contract_type_code IN ('SKI0217_2021','SKI0217_2025','SKI0215_2025')")
                .getSingleResult();
        assertEquals(0, remainingAdminType.intValue(),
                "no ADMIN_FEE_PERCENT rows may remain on the V98-seeded contract types after V396");
    }

    // --- V396 semantics + idempotency proven against exact prod-shaped fixture rows ---

    @Test
    @TestTransaction
    void v396Statements_retypeProdShapedRows_tagDiscounts_leaveOthers_andAreIdempotent() throws IOException {
        String code = "ZZPURPMIG" + (System.nanoTime() % 1_000_000_000L);
        em.createNativeQuery("INSERT INTO contract_type_definitions (code, name, active) VALUES (?1, 'Purpose migration fixture', 1)")
                .setParameter(1, code).executeUpdate();

        // Exact prod shapes (prod-pricing-fixtures extract 2026-07-10): the 3 ADMIN_FEE_PERCENT
        // rows (ids 2, 6, 8), one paramKey discount (id 5), one general placement (id 7),
        // one fixed fee (id 3). updated_at pinned to prove V396 preserves timestamps.
        insertRule(code, "mig-admin-2021", "2% SKI administrationsgebyr", "ADMIN_FEE_PERCENT", "CURRENT_SUM", "2.0000", null, null, null, null, 20);
        insertRule(code, "mig-admin-2025", "4% SKI administrationsgebyr", "ADMIN_FEE_PERCENT", "CURRENT_SUM", "4.0000", null, null, null, null, 21);
        insertRule(code, "mig-admin-0215", "4% SKI administrationsgebyr", "ADMIN_FEE_PERCENT", "CURRENT_SUM", "4.0000", null, null, "2025-07-01", "2026-01-01", 22);
        insertRule(code, "mig-key", "SKI trapperabat", "PERCENT_DISCOUNT_ON_SUM", "SUM_BEFORE_DISCOUNTS", null, null, "trapperabat", null, null, 10);
        insertRule(code, "mig-general", "Generel rabat", "GENERAL_DISCOUNT_PERCENT", "CURRENT_SUM", null, null, null, null, null, 40);
        insertRule(code, "mig-fee", "Faktureringsgebyr", "FIXED_DEDUCTION", "CURRENT_SUM", null, "2000.00", null, null, null, 30);

        List<String> statements = loadV396Statements();
        assertEquals(2, statements.size(), "V396 must contain exactly the retype and the tag statement");
        assertTrue(statements.get(0).toUpperCase().startsWith("UPDATE"), "first V396 statement must be an UPDATE");
        assertTrue(statements.get(1).toUpperCase().startsWith("UPDATE"), "second V396 statement must be an UPDATE");

        // First run — the real migration statements
        for (String sql : statements) {
            em.createNativeQuery(sql).executeUpdate();
        }

        // The 3 prod-shaped admin rows: retyped + tagged, everything else untouched
        for (String ruleId : List.of("mig-admin-2021", "mig-admin-2025", "mig-admin-0215")) {
            Object[] row = selectRule(code, ruleId);
            assertEquals("PERCENT_DISCOUNT_ON_SUM", row[0], ruleId + " must be retyped");
            assertEquals("ADMIN_FEE", row[1], ruleId + " must be tagged ADMIN_FEE");
        }
        assertEquals("2.0000", selectRule(code, "mig-admin-2021")[2].toString(), "percent must be untouched by the retype");
        assertEquals("CURRENT_SUM", selectRule(code, "mig-admin-2021")[3], "step_base must be untouched by the retype");
        assertEquals("2025-10-17 19:47:37",
                selectRule(code, "mig-admin-2021")[5].toString().replace('T', ' ').substring(0, 19),
                "updated_at must be preserved (ON UPDATE CURRENT_TIMESTAMP suppressed)");

        // Pre-existing percentage discount: tagged DISCOUNT
        assertEquals("DISCOUNT", selectRule(code, "mig-key")[1], "pre-existing PERCENT_DISCOUNT_ON_SUM rows get purpose DISCOUNT");
        assertEquals("trapperabat", selectRule(code, "mig-key")[4], "param_key must be untouched");

        // System/placement rows: purpose stays NULL, type unchanged
        assertEquals("GENERAL_DISCOUNT_PERCENT", selectRule(code, "mig-general")[0]);
        assertNull(selectRule(code, "mig-general")[1], "GENERAL_DISCOUNT_PERCENT rows keep purpose NULL");
        assertEquals("FIXED_DEDUCTION", selectRule(code, "mig-fee")[0]);
        assertNull(selectRule(code, "mig-fee")[1], "FIXED_DEDUCTION rows keep purpose NULL");

        // Second run — idempotent: both statements match zero rows
        for (String sql : statements) {
            assertEquals(0, em.createNativeQuery(sql).executeUpdate(),
                    "re-running V396 must be a no-op: " + sql);
        }
    }

    // --- helpers ---

    private void insertRule(String code, String ruleId, String label, String type, String base,
                            String percent, String amount, String paramKey,
                            String validFrom, String validTo, int priority) {
        em.createNativeQuery(
                "INSERT INTO pricing_rule_steps (contract_type_code, rule_id, label, rule_step_type, step_base, " +
                "percent, amount, param_key, valid_from, valid_to, priority, active, created_at, updated_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, 1, '2025-10-17 19:47:37', '2025-10-17 19:47:37')")
                .setParameter(1, code).setParameter(2, ruleId).setParameter(3, label)
                .setParameter(4, type).setParameter(5, base).setParameter(6, percent)
                .setParameter(7, amount).setParameter(8, paramKey)
                .setParameter(9, validFrom).setParameter(10, validTo).setParameter(11, priority)
                .executeUpdate();
    }

    /** Returns [rule_step_type, purpose, percent, step_base, param_key, updated_at]. */
    private Object[] selectRule(String code, String ruleId) {
        return (Object[]) em.createNativeQuery(
                "SELECT rule_step_type, purpose, percent, step_base, param_key, updated_at " +
                "FROM pricing_rule_steps WHERE contract_type_code = ?1 AND rule_id = ?2")
                .setParameter(1, code).setParameter(2, ruleId)
                .getSingleResult();
    }

    /** Loads the real V396 statements from the classpath so the test can never drift from the migration. */
    private List<String> loadV396Statements() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(V396_RESOURCE)) {
            assertNotNull(in, V396_RESOURCE + " must be on the test classpath");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String withoutComments = Arrays.stream(sql.split("\n"))
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return Arrays.stream(withoutComments.split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }
}
