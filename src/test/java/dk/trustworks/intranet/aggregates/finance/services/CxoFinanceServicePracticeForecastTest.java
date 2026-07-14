package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.PracticeForecastMonthDTO;
import dk.trustworks.intranet.aggregates.practices.services.PracticeAttributionService;
import dk.trustworks.intranet.aggregates.utilization.dto.ActualDataStatus;
import dk.trustworks.intranet.aggregates.utilization.services.FactUserDayFreshnessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CxoFinanceServicePracticeForecastTest {

    @Test
    void laggedCertifiedRowsRemainUsableWithExplicitCutoff() {
        LocalDate today = LocalDate.of(2026, 7, 14);
        Instant refreshedAt = Instant.parse("2026-07-13T19:40:05Z");
        FactUserDayFreshnessService.Freshness freshness = new FactUserDayFreshnessService.Freshness(
                LocalDate.of(2026, 7, 13),
                LocalDate.of(2026, 7, 12),
                ActualDataStatus.SOURCE_LAGGED,
                1,
                refreshedAt);

        EntityManager em = mock(EntityManager.class);
        Query actualQuery = mock(Query.class);
        Query budgetQuery = mock(Query.class);
        when(em.createNativeQuery(CxoFinanceService.practiceActualMonthlySql(true, false), Tuple.class))
                .thenReturn(actualQuery);
        when(em.createNativeQuery(CxoFinanceService.practiceBudgetMonthlySql(false), Tuple.class))
                .thenReturn(budgetQuery);
        List<Tuple> actualRows = List.of(
                actualTuple("DEV", "202606", 100.0, 80.0),
                actualTuple("DEV", "202607", 100.0, 40.0));
        List<Tuple> budgetRows = List.of(
                budgetTuple("DEV", "202608", 80.0, 100.0));
        when(actualQuery.getResultList()).thenReturn(actualRows);
        when(budgetQuery.getResultList()).thenReturn(budgetRows);

        CxoFinanceService service = service(em, freshness, today);
        List<PracticeForecastMonthDTO> result = service.getPracticeUtilizationForecast(
                Set.of("DEV"), null, today);

        assertEquals(12, result.size());
        PracticeForecastMonthDTO june = month(result, "202606");
        assertEquals(80.0, june.getActualUtilizationPct(), 1e-9);
        assertEquals(LocalDate.of(2026, 6, 30), june.getActualThroughDate());

        PracticeForecastMonthDTO july = month(result, "202607");
        assertEquals(40.0, july.getActualUtilizationPct(), 1e-9);
        assertEquals(LocalDate.of(2026, 7, 12), july.getActualThroughDate());
        assertEquals(LocalDate.of(2026, 7, 13), july.getRequestedActualThroughDate());
        assertEquals(LocalDate.of(2026, 7, 12), july.getActualDataThroughDate());
        assertEquals(ActualDataStatus.SOURCE_LAGGED, july.getActualDataStatus());
        assertEquals(1, july.getActualSourceLagDays());
        assertEquals(refreshedAt, july.getSourceRefreshedAt());

        PracticeForecastMonthDTO august = month(result, "202608");
        assertEquals(80.0, august.getBudgetUtilizationPct(), 1e-9);
        assertEquals(0.0, august.getBudgetGapHoursToTarget(), 1e-9);
        assertNull(august.getActualUtilizationPct());

        verify(actualQuery).setParameter("actualToDate", LocalDate.of(2026, 7, 12));
    }

    @Test
    void unavailableSourceSkipsActualQueryButRetainsBudgetAndTarget() {
        LocalDate today = LocalDate.of(2026, 7, 14);
        FactUserDayFreshnessService.Freshness freshness = new FactUserDayFreshnessService.Freshness(
                LocalDate.of(2026, 7, 13), null,
                ActualDataStatus.UNAVAILABLE, null, null);

        EntityManager em = mock(EntityManager.class);
        Query budgetQuery = mock(Query.class);
        when(em.createNativeQuery(CxoFinanceService.practiceBudgetMonthlySql(false), Tuple.class))
                .thenReturn(budgetQuery);
        List<Tuple> budgetRows = List.of(
                budgetTuple("DEV", "202608", 70.0, 100.0));
        when(budgetQuery.getResultList()).thenReturn(budgetRows);

        CxoFinanceService service = service(em, freshness, today);
        List<PracticeForecastMonthDTO> result = service.getPracticeUtilizationForecast(
                Set.of("DEV"), null, today);

        assertEquals(12, result.size());
        result.forEach(row -> {
            assertEquals(ActualDataStatus.UNAVAILABLE, row.getActualDataStatus());
            assertNull(row.getActualDataThroughDate());
            assertNull(row.getActualUtilizationPct());
            assertNull(row.getActualThroughDate());
        });
        PracticeForecastMonthDTO august = month(result, "202608");
        assertEquals(70.0, august.getBudgetUtilizationPct(), 1e-9);
        assertEquals(10.0, august.getBudgetGapHoursToTarget(), 1e-9);
        assertEquals(80.0, august.getTargetUtilizationPct(), 1e-9);

        verify(em, never()).createNativeQuery(
                CxoFinanceService.practiceActualMonthlySql(true, false), Tuple.class);
    }

    private static CxoFinanceService service(
            EntityManager em,
            FactUserDayFreshnessService.Freshness freshness,
            LocalDate today) {
        FactUserDayFreshnessService freshnessService = mock(FactUserDayFreshnessService.class);
        when(freshnessService.resolve(today)).thenReturn(freshness);
        PracticeAttributionService attributionService = mock(PracticeAttributionService.class);
        when(attributionService.metadata()).thenReturn(new PracticeAttributionService.AttributionMetadata(
                "EFFECTIVE_DATED_SNAPSHOT_WITH_CURRENT_USER_FALLBACK",
                LocalDate.of(2026, 7, 14),
                "Earlier dates fall back to current user.practice"));

        CxoFinanceService service = new CxoFinanceService();
        service.em = em;
        service.factUserDayFreshnessService = freshnessService;
        service.practiceAttributionService = attributionService;
        return service;
    }

    private static Tuple actualTuple(
            String practiceId, String monthKey, double available, double billable) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("practice_id")).thenReturn(practiceId);
        when(tuple.get("month_key")).thenReturn(monthKey);
        when(tuple.get("net_available_hours")).thenReturn(available);
        when(tuple.get("billable_hours")).thenReturn(billable);
        return tuple;
    }

    private static Tuple budgetTuple(
            String practiceId, String monthKey, double budget, double available) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("practice_id")).thenReturn(practiceId);
        when(tuple.get("month_key")).thenReturn(monthKey);
        when(tuple.get("budget_hours")).thenReturn(budget);
        when(tuple.get("net_available_hours")).thenReturn(available);
        return tuple;
    }

    private static PracticeForecastMonthDTO month(
            List<PracticeForecastMonthDTO> rows, String monthKey) {
        return rows.stream()
                .filter(row -> monthKey.equals(row.getMonthKey()))
                .findFirst()
                .orElseThrow();
    }
}
