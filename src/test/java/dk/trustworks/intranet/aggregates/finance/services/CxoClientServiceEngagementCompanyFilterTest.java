package dk.trustworks.intranet.aggregates.finance.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the CXO engagement queries' company filter.
 *
 * <p>The raw {@code work} table has NO company column — company is only reachable
 * via {@code userstatus.companyuuid} at the registered date (the rule the
 * {@code work_full} views use, see V83/V95/V259). An earlier version filtered on
 * {@code w2.companyuuid} / {@code w.companyuuid}, which failed in production with
 * MariaDB error 1054 and — because the exception was swallowed — silently rendered
 * the dashboard KPI as 0 months / 0 clients whenever a company filter was applied.</p>
 *
 * <p>These tests run DB-free (no {@code @QuarkusTest}) so they are part of the
 * fast tier that gates deploys: the generated SQL is captured from a mocked
 * {@link EntityManager} and every {@code work}-alias column reference is checked
 * against the real {@code work} table schema.</p>
 */
class CxoClientServiceEngagementCompanyFilterTest {

    /** Columns of the {@code work} table (schema truth; no company dimension). */
    private static final Set<String> WORK_COLUMNS = Set.of(
            "uuid", "clientuuid", "projectuuid", "taskuuid", "contractuuid",
            "useruuid", "workduration", "comments", "rate", "billable",
            "workas", "registered", "paid_out", "updated_at", "created_at");

    private static final Set<String> COMPANY_IDS =
            Set.of("e4b0a2a4-0963-4153-b0a2-a409637153a2");

    private CxoClientService service;
    private EntityManager em;
    private Query query;

    @BeforeEach
    void setUp() {
        service = new CxoClientService();
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        service.em = em;
    }

    /** Every {@code w.}/{@code w2.}-qualified column in the SQL must exist on the work table. */
    private static void assertOnlyRealWorkColumns(String sql) {
        Matcher m = Pattern.compile("\\b(w2?)\\.([a-z_]+)").matcher(sql);
        while (m.find()) {
            String column = m.group(2);
            assertTrue(WORK_COLUMNS.contains(column),
                    "SQL references non-existent work column '" + m.group() + "' in: " + sql);
        }
    }

    @Test
    void queryEngagementMetrics_withCompanyFilter_returnsNonDegenerateResult() {
        // Values observed on prod for company e4b0a2a4 on 2026-08-20
        when(query.getSingleResult()).thenReturn(new Object[]{81.80531689, 13L});

        CxoClientService.EngagementMetrics metrics = service.queryEngagementMetrics(
                LocalDate.of(2026, 8, 20), null, COMPANY_IDS);

        assertEquals(81.80531689, metrics.avgMonths(), 1e-9,
                "avg engagement months must pass through, not degrade to 0.0");
        assertEquals(13, metrics.clientCount(),
                "client count must pass through, not degrade to 0");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(em).createNativeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();

        assertOnlyRealWorkColumns(sql);
        assertFalse(sql.contains("w2.companyuuid"),
                "work has no companyuuid column (prod error 1054)");
        assertTrue(sql.contains(":companyIds"), "company filter must be applied");
        assertTrue(sql.contains("userstatus"),
                "company must be resolved via userstatus at the registered date (work_full rule)");
        assertTrue(sql.contains("us.statusdate <= w2.registered"),
                "must use the status row in effect at the registered date");
    }

    @Test
    void queryEngagementMetrics_queryFailure_propagatesInsteadOfReturningZeros() {
        when(query.getSingleResult()).thenThrow(
                new RuntimeException("JDBC exception executing SQL: Unknown column"));

        assertThrows(RuntimeException.class, () -> service.queryEngagementMetrics(
                        LocalDate.of(2026, 8, 20), null, COMPANY_IDS),
                "a failed query must not be swallowed into a fake 0.0/0 result");
    }

    @Test
    void getEngagementByCompany_withCompanyFilter_usesRealWorkColumns() {
        when(query.getResultList()).thenReturn(List.of());

        service.getEngagementByCompany(LocalDate.of(2026, 8, 20), null, 20, COMPANY_IDS);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(em).createNativeQuery(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();

        assertOnlyRealWorkColumns(sql);
        assertFalse(sql.contains("w.companyuuid"),
                "work has no companyuuid column (same latent 1054 as queryEngagementMetrics)");
        assertTrue(sql.contains(":companyIds"), "company filter must be applied");
        assertTrue(sql.contains("us.statusdate <= w.registered"),
                "company must be resolved via userstatus at the registered date (work_full rule)");
    }
}
