package dk.trustworks.intranet.bi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP4c / decision D3 — the billability discriminator.
 *
 * <p>{@code work.billable} has been dead since Dec 2023, so {@code rate > 0} became the de-facto
 * billability test. V572 made a <em>declared</em> zero-rate consultant line legal, at which point
 * {@code rate > 0} could no longer distinguish "deliberately free" from "no contract at all" —
 * {@code work_full} resolves both to rate 0.
 *
 * <p>D3 (2026-09-06): a sold-at-zero hour is sold. {@code rate > 0} keeps meaning <em>revenue</em>;
 * billability becomes {@code rate_basis <> 'NO_CONTRACT'}.
 *
 * <p>These are fast-tier assertions (no {@code @QuarkusTest}, no database) over the migration text
 * on the classpath. They exist because the failure mode is silent: reverting either predicate
 * produces no error, no exception and no failing query — only quietly wrong numbers in
 * {@code fact_user_day}, which is the documented single source of truth for all utilization
 * (users-domain-compact R8). A compiler cannot catch that; this can.
 */
class RateBasisMigrationTest {

    private static final String V578 = "db/migration/V578__Add_rate_basis_to_work_full_views.sql";
    private static final String V579 = "db/migration/V579__Sp_aggregate_work_billable_hours_rate_basis.sql";

    private static String read(String resource) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "Migration not found on the classpath: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("V578 defines rate_basis on both work_full views")
    void v578DefinesRateBasisOnBothViews() throws IOException {
        String sql = read(V578);

        assertTrue(sql.contains("CREATE OR REPLACE VIEW work_full AS"),
                "V578 must recreate work_full");
        assertTrue(sql.contains("VIEW `work_full_optimized` AS"),
                "V578 must recreate work_full_optimized — GrowthAnalyticsService and "
                        + "IndustryWorkTrendService read that view, not work_full");

        assertEquals(2, countOf(sql, "END AS rate_basis"),
                "rate_basis must be defined on BOTH views; a discriminator on only one of them "
                        + "leaves half the consumers unable to tell a declared zero from no contract");
    }

    @Test
    @DisplayName("V578 resolves all three rate_basis states, and falls through conservatively")
    void v578CoversAllThreeStates() throws IOException {
        String sql = read(V578);

        // Count the CASE arms specifically, not bare occurrences — the header comment
        // discusses these same tokens, so a substring count would drift with the prose.
        assertEquals(2, countOf(sql, "THEN 'BILLABLE'"), "BILLABLE arm missing from a view");
        assertEquals(2, countOf(sql, "THEN 'ZERO_DECLARED'"), "ZERO_DECLARED arm missing from a view");
        assertEquals(2, countOf(sql, "THEN 'NO_CONTRACT'"), "Explicit no-match arm missing from a view");

        assertEquals(2, countOf(sql, "ELSE 'NO_CONTRACT'"),
                "The ELSE must fall through to NO_CONTRACT. A matched line with rate = 0 and no "
                        + "zero_rate_reason cannot exist today, but if one ever appears it must NOT be "
                        + "silently promoted to billable — preserve current behaviour instead");

        // Note "ccc.zero_rate_reason" contains "cc.zero_rate_reason" as a substring, so match the
        // full predicate. If a derived table failed to expose the column the CASE would be invalid
        // SQL and the migration would fail loudly on apply — that path needs no assertion here.
        assertEquals(2, countOf(sql, "ccc.zero_rate_reason IS NOT NULL"),
                "Both views must test zero_rate_reason to reach ZERO_DECLARED");
    }

    @Test
    @DisplayName("V579 counts hours by rate_basis but keeps revenue on rate > 0")
    void v579SplitsHoursFromRevenue() throws IOException {
        String sql = read(V579);

        assertTrue(sql.contains("SUM(CASE WHEN rate_basis <> 'NO_CONTRACT' THEN workduration ELSE 0 END)"),
                "billable_hours must use the discriminator. sp_aggregate_work has NO consultant_type "
                        + "filter, so it is the one place a junior's declared zero-rate hours reach a "
                        + "rate test and get written to fact_user_day as zero");

        assertTrue(sql.contains("SUM(CASE WHEN rate > 0 THEN workduration * rate ELSE 0 END)"),
                "revenue must stay on rate > 0 — a declared zero genuinely produces no money, and "
                        + "moving it would inflate reported revenue by the list value of free work");

        assertTrue(sql.contains("ENGINE=InnoDB"),
                "The V456 lock-contention design (InnoDB session temp, non-locking snapshot read) "
                        + "must be preserved — only the two SUM predicates change");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = haystack.indexOf(needle);
        while (i >= 0) { n++; i = haystack.indexOf(needle, i + needle.length()); }
        return n;
    }
}
