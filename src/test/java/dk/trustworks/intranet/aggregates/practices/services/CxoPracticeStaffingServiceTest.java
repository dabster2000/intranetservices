package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CxoPracticeStaffingServiceTest {

    @Test
    void completed28DayWindow_hasTwentyWeekdays() {
        CxoPracticeStaffingService.StaffingWindow window =
                CxoPracticeStaffingService.staffingWindow(LocalDate.of(2026, 7, 14));

        assertEquals(LocalDate.of(2026, 6, 16), window.actualFromDate());
        assertEquals(LocalDate.of(2026, 7, 13), window.actualToDate());
        assertEquals(20, CxoPracticeStaffingService.countWeekdays(
                window.actualFromDate(), window.actualToDate()));
    }

    @Test
    void unusedFte_usesUnusedNetHoursAndStandardPeriodCapacity() {
        double standardPeriodHours = 20 * 7.4;

        assertEquals(0.25, CxoPracticeStaffingService.calculateUnusedFte(
                37.0, 74.0, standardPeriodHours), 1e-9);
        assertEquals(0.0, CxoPracticeStaffingService.calculateUnusedFte(
                90.0, 74.0, standardPeriodHours), 1e-9);
        assertEquals(0.0, CxoPracticeStaffingService.calculateUnusedFte(
                0.0, 74.0, 0.0), 1e-9);
    }

    @Test
    void strictThresholds_doNotIncludeValuesAtBoundary() {
        assertTrue(CxoPracticeStaffingService.isPlannedUnallocated(9.999));
        assertFalse(CxoPracticeStaffingService.isPlannedUnallocated(10.0));
        assertTrue(CxoPracticeStaffingService.isActualUnderutilized(49.999));
        assertFalse(CxoPracticeStaffingService.isActualUnderutilized(50.0));
    }

    @Test
    void missingBudgetIsZeroAndWeightedHoursAreDividedAfterSumming() {
        assertEquals(0.0, CxoPracticeStaffingService.utilizationPct(0.0, 160.0), 1e-9);
        // 80/160 (50%) plus 80/80 (100%) is 160/240 = 66.67%, not a 75% average.
        assertEquals(66.6666667,
                CxoPracticeStaffingService.utilizationPct(80.0 + 80.0, 160.0 + 80.0), 1e-6);
    }

    @Test
    void practiceAllowlistNormalizesAndRejectsUnknownCodes() {
        assertNull(CxoPracticeStaffingService.normalizePractice(null));
        assertNull(CxoPracticeStaffingService.normalizePractice("  "));
        assertEquals("PM", CxoPracticeStaffingService.normalizePractice(" pm "));
        assertThrows(IllegalArgumentException.class,
                () -> CxoPracticeStaffingService.normalizePractice("sales"));
    }

    @Test
    void detailIdentityKeepsPracticeSegmentsSeparateForSameConsultant() {
        String pm = CxoPracticeStaffingService.userPracticeKey("user-1", "PM");
        String ba = CxoPracticeStaffingService.userPracticeKey("user-1", "BA");

        assertFalse(pm.equals(ba));
        assertEquals(pm, CxoPracticeStaffingService.userPracticeKey("user-1", "PM"));
    }

    @Test
    void queryContractsUseSummedHoursFallbackAndZeroBudget() {
        String planned = CxoPracticeStaffingService.PLANNED_ROWS_SQL;
        String actual = CxoPracticeStaffingService.ACTUAL_ROWS_SQL;

        assertTrue(planned.contains("SUM(fud.net_available_hours)"));
        assertTrue(planned.contains("SUM(budgetHours)"));
        assertTrue(planned.contains("COALESCE(b.budget_hours, 0)"));
        assertFalse(planned.contains("AVG("));
        assertTrue(actual.contains("SUM(fud.registered_billable_hours)"));
        assertTrue(actual.contains("LEFT JOIN user_practice_history"));
        assertTrue(actual.contains("COALESCE(uph.practice, u.practice)"));
        assertFalse(actual.contains("AVG("));
    }

    @Test
    void summaryOmitsConsultantDetailsWhileSelectedPracticeReturnsNullableEvidence() {
        CxoPracticeStaffingService service = serviceWithRows(
                List.<Object[]>of(new Object[]{
                        "user-1", "Ada", "Lovelace", "PM", "202607", 160.0, 0.0}),
                List.of());

        var summary = service.getStaffing(null, LocalDate.of(2026, 7, 14));
        assertTrue(summary.consultants().isEmpty());
        assertEquals(1, summary.practices().stream()
                .filter(p -> "PM".equals(p.practiceId()))
                .findFirst().orElseThrow().plannedUnallocatedHeadcount());

        var detail = service.getStaffing("PM", LocalDate.of(2026, 7, 14));
        assertEquals(1, detail.consultants().size());
        var consultant = detail.consultants().getFirst();
        assertTrue(consultant.hasPlannedEvidence());
        assertFalse(consultant.hasActualEvidence());
        assertEquals(0.0, consultant.plannedBudgetHours(), 1e-9);
        assertEquals(0.0, consultant.plannedUtilizationPct(), 1e-9);
        assertNull(consultant.actualBillableHours());
        assertNull(consultant.actualUnusedFte());
    }

    @Test
    void oneConsultantChangingPracticeRetainsBothActualSummarySegments() {
        CxoPracticeStaffingService service = serviceWithRows(List.of(), List.of(
                new Object[]{"user-1", "Ada", "Lovelace", "PM", 100.0, 20.0},
                new Object[]{"user-1", "Ada", "Lovelace", "BA", 50.0, 10.0}));

        var response = service.getStaffing("PM", LocalDate.of(2026, 7, 14));

        assertEquals(1, response.practices().stream()
                .filter(p -> "PM".equals(p.practiceId()))
                .findFirst().orElseThrow().actualUnderutilizedHeadcount());
        assertEquals(1, response.practices().stream()
                .filter(p -> "BA".equals(p.practiceId()))
                .findFirst().orElseThrow().actualUnderutilizedHeadcount());
        assertEquals(1, response.consultants().size());
        assertEquals("PM", response.consultants().getFirst().practiceId());
        assertFalse(response.consultants().getFirst().hasPlannedEvidence());
        assertTrue(response.consultants().getFirst().hasActualEvidence());
        assertNull(response.consultants().getFirst().plannedBudgetHours());
    }

    private static CxoPracticeStaffingService serviceWithRows(
            List<Object[]> plannedRows,
            List<Object[]> actualRows) {
        EntityManager em = mock(EntityManager.class);
        Query plannedQuery = mock(Query.class);
        Query actualQuery = mock(Query.class);
        when(em.createNativeQuery(CxoPracticeStaffingService.PLANNED_ROWS_SQL)).thenReturn(plannedQuery);
        when(em.createNativeQuery(CxoPracticeStaffingService.ACTUAL_ROWS_SQL)).thenReturn(actualQuery);
        when(plannedQuery.getResultList()).thenReturn(plannedRows);
        when(actualQuery.getResultList()).thenReturn(actualRows);

        PracticeAttributionService attribution = mock(PracticeAttributionService.class);
        when(attribution.metadata()).thenReturn(new PracticeAttributionService.AttributionMetadata(
                "EFFECTIVE_DATED_SNAPSHOT_WITH_CURRENT_USER_FALLBACK",
                LocalDate.of(2026, 7, 14),
                "Earlier dates fall back to current user.practice"));

        CxoPracticeStaffingService service = new CxoPracticeStaffingService();
        service.em = em;
        service.practiceAttributionService = attribution;
        return service;
    }
}
