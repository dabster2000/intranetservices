package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactOpexSignedSourceContractTest {

    @Test
    void v408RetainsAllFourNetNegativeBranchesAndOtherwiseMatchesV383() throws IOException {
        String v383 = Files.readString(Path.of(
                "src/main/resources/db/migration/V383__fact_opex_signed.sql"));
        String v408 = Files.readString(Path.of(
                "src/main/resources/db/migration/V408__Retain_net_negative_fact_opex_buckets.sql"));

        assertEquals(4, occurrences(v408, "AND oa.opex_amount_dkk <> 0"));
        assertFalse(v408.contains("AND oa.opex_amount_dkk > 0"));

        String expected = sqlFromCreate(v383).replace(
                "AND oa.opex_amount_dkk > 0", "AND oa.opex_amount_dkk <> 0");
        assertEquals(normalizeSql(expected), normalizeSql(sqlFromCreate(v408)));
    }

    @Test
    void v409UsesSignedNetSalaryAndVersionedFailClosedCompletenessRules() throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V409__Practice_salary_completeness_metadata.sql"));

        assertTrue(migration.contains("SUM(gl.amount) AS opex_amount_dkk"));
        assertFalse(migration.contains("SUM(ABS(gl.amount))"));
        assertEquals(4, occurrences(migration, "AND oa.opex_amount_dkk <> 0"));
        assertTrue(migration.contains("CONCAT(oa.posting_status, '-'"));
        assertTrue(migration.contains("FROM fact_opex;"));

        assertTrue(migration.contains("SUM(fd.amount) AS signed_salary_gl_dkk"));
        assertFalse(migration.contains("SUM(ABS(fd.amount))"));
        assertTrue(migration.contains("ABS(COALESCE(sg.signed_salary_gl_dkk, 0.0))"));
        assertTrue(migration.contains("ABS(m.signed_salary_gl_dkk) * 100"));
        assertTrue(migration.contains("< m.intended_salary_dkk * 85"));
        assertTrue(migration.contains("> m.intended_salary_dkk * 125"));
        assertFalse(migration.contains("WHEN m.salary_completeness_ratio"),
                "rounded presentation ratios must never decide exact boundary completeness");
        assertTrue(migration.contains("m.missing_salary_cell_count <> 0"));
        assertTrue(migration.contains("m.unexpected_salary_cell_count <> 0"));
        assertTrue(migration.contains("selected_allocated_practice_salary_cells AS"));
        assertTrue(migration.contains("WHERE practice_id IN ('PM', 'BA', 'CYB', 'DEV', 'SA')"));
        assertTrue(migration.contains("allocated_salary_totals AS"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS fact_practice_salary_completeness_mat"));
        assertTrue(migration.contains("materialized_at DATETIME(6) NULL COMMENT 'UTC publication generation'"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS practice_operating_cost_publication"));
        assertTrue(migration.contains("CONSTRAINT chk_practice_operating_cost_singleton CHECK (publication_id = 1)"));
        assertTrue(migration.contains("CREATE PROCEDURE sp_refresh_practice_opex_mat()"));
        assertTrue(migration.contains("CREATE PROCEDURE sp_begin_practice_operating_cost_publication("));
        assertTrue(migration.contains("CREATE PROCEDURE sp_stage_practice_operating_cost_publication("));
        assertTrue(migration.contains("CREATE PROCEDURE sp_finalize_practice_operating_cost_publication("));
        assertTrue(migration.contains("UPDATE fact_opex_mat\n    SET materialized_at = v_generation_at"));
        assertTrue(migration.contains("UPDATE fact_employee_monthly_mat\n    SET materialized_at = v_generation_at"));
        assertTrue(migration.contains("CALL sp_replace_practice_salary_completeness_mat(v_generation_at)"));
        assertTrue(migration.contains("IF v_opex_row_count = 0 OR v_fte_row_count = 0"));
        assertTrue(migration.contains("CREATE PROCEDURE sp_refresh_practice_salary_completeness_mat()"));
        assertTrue(migration.contains("CALL sp_refresh_practice_salary_completeness_mat();"));
        assertTrue(migration.contains(
                "GREATEST(1.00, ABS(COALESCE(sg.signed_salary_gl_dkk, 0.0)) * 0.0001)"));
        assertTrue(migration.contains("ABS(COALESCE(at.allocated_salary_dkk, 0.0)"));
        assertFalse(migration.contains("ac.allocated_salary_dkk"));
        assertTrue(migration.contains("m.allocation_gap_dkk > m.allowed_allocation_gap_dkk"));
        assertTrue(migration.contains("'PRACTICE_SALARY_V1' AS rule_version"));
        assertTrue(migration.contains(
                "CONVERT_TZ(UTC_TIMESTAMP(), 'UTC', 'Europe/Copenhagen')"));
        assertTrue(migration.contains("'BOOKED_PLUS_DRAFT'"));
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static String sqlFromCreate(String migration) {
        return migration.substring(migration.indexOf("CREATE OR REPLACE ALGORITHM"));
    }

    private static String normalizeSql(String sql) {
        return Arrays.stream(sql.split("\\R"))
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("--"))
                .collect(Collectors.joining("\n"));
    }
}
