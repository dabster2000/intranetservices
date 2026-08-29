package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthTimelineMonthDTO;
import dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.HeadcountMonth;
import dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.StatusRow;
import dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.WorkStats;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.assembleTimeline;
import static dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.fiscalYearOf;
import static dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.foldHeadcount;
import static dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.monthKey;
import static dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService.perPersonMonthlyHours;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DB-free tests for the pure fold/assembly logic behind the Growth &amp; Scenarios
 * endpoints ({@code /finance/growth/timeline}, {@code /finance/growth/simulation-baseline}).
 */
class GrowthAnalyticsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 29);

    private static StatusRow row(String user, String status, String type, String date) {
        return new StatusRow(user, status, type, LocalDate.parse(date));
    }

    private static HeadcountMonth month(Map<String, HeadcountMonth> fold, String key) {
        return fold.getOrDefault(key, HeadcountMonth.EMPTY);
    }

    // -------------------------------------------------------------------------
    // foldHeadcount — point-in-time counts
    // -------------------------------------------------------------------------

    @Test
    void activeConsultantCountsFromHireMonthOn() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "CONSULTANT", "2026-03-15")),
                YearMonth.of(2026, 2), YearMonth.of(2026, 5), TODAY);

        assertEquals(0, month(fold, "202602").consultants());
        assertEquals(1, month(fold, "202603").consultants());
        assertEquals(1, month(fold, "202605").consultants());
        assertEquals(1, month(fold, "202603").hires());
        assertEquals(0, month(fold, "202604").hires());
    }

    @Test
    void terminationRemovesFromCountAndIsCounted() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "STAFF", "2025-01-01"),
                        row("u1", "TERMINATED", "STAFF", "2026-04-10")),
                YearMonth.of(2026, 3), YearMonth.of(2026, 5), TODAY);

        assertEquals(1, month(fold, "202603").staff());
        assertEquals(0, month(fold, "202604").staff());
        assertEquals(1, month(fold, "202604").terminations());
        assertEquals(0, month(fold, "202605").terminations());
    }

    @Test
    void leaveMovesToOnLeaveWithoutHireOrTermination() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "CONSULTANT", "2025-01-01"),
                        row("u1", "MATERNITY_LEAVE", "CONSULTANT", "2026-02-01"),
                        row("u1", "ACTIVE", "CONSULTANT", "2026-05-01")),
                YearMonth.of(2026, 1), YearMonth.of(2026, 6), TODAY);

        assertEquals(1, month(fold, "202601").consultants());
        assertEquals(0, month(fold, "202602").consultants());
        assertEquals(1, month(fold, "202602").onLeave());
        assertEquals(1, month(fold, "202605").consultants());
        assertEquals(0, month(fold, "202605").onLeave());
        // Leave flips are not employment transitions.
        long totalHires = fold.values().stream().mapToLong(HeadcountMonth::hires).sum();
        long totalTerms = fold.values().stream().mapToLong(HeadcountMonth::terminations).sum();
        assertEquals(0, totalHires);
        assertEquals(0, totalTerms);
    }

    @Test
    void rehireAfterTerminationCountsAsSecondHire() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "CONSULTANT", "2025-01-01"),
                        row("u1", "TERMINATED", "CONSULTANT", "2025-06-01"),
                        row("u1", "ACTIVE", "CONSULTANT", "2026-02-01")),
                YearMonth.of(2025, 1), YearMonth.of(2026, 3), TODAY);

        assertEquals(1, month(fold, "202501").hires());
        assertEquals(1, month(fold, "202506").terminations());
        assertEquals(1, month(fold, "202602").hires());
        assertEquals(1, month(fold, "202602").consultants());
        assertEquals(0, month(fold, "202512").consultants());
    }

    @Test
    void preboardingDoesNotCountUntilActive() {
        var fold = foldHeadcount(
                List.of(row("u1", "PREBOARDING", "CONSULTANT", "2026-03-01"),
                        row("u1", "ACTIVE", "CONSULTANT", "2026-05-01")),
                YearMonth.of(2026, 3), YearMonth.of(2026, 6), TODAY);

        assertEquals(0, month(fold, "202603").consultants());
        assertEquals(0, month(fold, "202603").hires());
        assertEquals(1, month(fold, "202605").consultants());
        assertEquals(1, month(fold, "202605").hires());
    }

    @Test
    void futureDatedRowsAreClampedOutOfCurrentMonth() {
        // Termination dated after TODAY but inside the current month must not count yet.
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "CONSULTANT", "2025-01-01"),
                        row("u1", "TERMINATED", "CONSULTANT", "2026-08-31")),
                YearMonth.of(2026, 8), YearMonth.of(2026, 8), TODAY);

        assertEquals(1, month(fold, "202608").consultants());
        // The transition itself is still bucketed on its own date's month.
        assertEquals(1, month(fold, "202608").terminations());
    }

    @Test
    void typeSwitchMovesCountWithoutNewHire() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "STUDENT", "2025-09-01"),
                        row("u1", "ACTIVE", "CONSULTANT", "2026-02-01")),
                YearMonth.of(2025, 9), YearMonth.of(2026, 3), TODAY);

        assertEquals(1, month(fold, "202509").students());
        assertEquals(0, month(fold, "202509").consultants());
        assertEquals(0, month(fold, "202602").students());
        assertEquals(1, month(fold, "202602").consultants());
        long totalHires = fold.values().stream().mapToLong(HeadcountMonth::hires).sum();
        assertEquals(1, totalHires);
    }

    @Test
    void nonCountedTypesAreIgnored() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "EXTERNAL", "2025-01-01"),
                        row("u2", "ACTIVE", "CONSULTANT", "2025-01-01")),
                YearMonth.of(2025, 2), YearMonth.of(2025, 2), TODAY);

        HeadcountMonth m = month(fold, "202502");
        assertEquals(1, m.consultants());
        assertEquals(0, m.staff());
        assertEquals(1, m.consultants() + m.students() + m.staff());
    }

    @Test
    void multipleUsersAggregatePerMonth() {
        var fold = foldHeadcount(
                List.of(row("u1", "ACTIVE", "CONSULTANT", "2025-01-01"),
                        row("u2", "ACTIVE", "CONSULTANT", "2025-02-15"),
                        row("u3", "ACTIVE", "STUDENT", "2025-02-01"),
                        row("u4", "ACTIVE", "STAFF", "2025-03-01")),
                YearMonth.of(2025, 1), YearMonth.of(2025, 3), TODAY);

        assertEquals(1, month(fold, "202501").consultants());
        assertEquals(2, month(fold, "202502").consultants());
        assertEquals(1, month(fold, "202502").students());
        assertEquals(1, month(fold, "202503").staff());
        assertEquals(2, month(fold, "202502").hires());
    }

    // -------------------------------------------------------------------------
    // assembleTimeline
    // -------------------------------------------------------------------------

    @Test
    void costFieldsAreNullBeforeCostEraAndZeroDefaultedInside() {
        List<GrowthTimelineMonthDTO> months = assembleTimeline(
                YearMonth.of(2024, 6), YearMonth.of(2024, 8),
                Map.of("202406", 10.0, "202407", 12.0),
                Map.of("202407", 8.0),
                Map.of(),
                Map.of());

        assertEquals(3, months.size());
        GrowthTimelineMonthDTO june = months.get(0);
        assertEquals("202406", june.monthKey());
        assertNull(june.opexCost());
        assertNull(june.glDirectCost());
        assertNull(june.totalCost());
        assertEquals(10.0, june.netRevenue());

        GrowthTimelineMonthDTO july = months.get(1);
        assertEquals(8.0, july.opexCost());
        assertEquals(0.0, july.glDirectCost());
        assertEquals(8.0, july.totalCost());

        GrowthTimelineMonthDTO august = months.get(2);
        assertEquals(0.0, august.netRevenue());
        assertEquals(0.0, august.opexCost());
        assertEquals(0.0, august.totalCost());
    }

    @Test
    void timelineCarriesFiscalYearAndHeadcount() {
        List<GrowthTimelineMonthDTO> months = assembleTimeline(
                YearMonth.of(2026, 6), YearMonth.of(2026, 7),
                Map.of(), Map.of(), Map.of(),
                Map.of("202607", new HeadcountMonth(5, 2, 1, 1, 3, 1)));

        assertEquals(2025, months.get(0).fiscalYear());
        assertEquals(2026, months.get(1).fiscalYear());
        GrowthTimelineMonthDTO july = months.get(1);
        assertEquals(5, july.consultants());
        assertEquals(2, july.students());
        assertEquals(1, july.staff());
        assertEquals(1, july.onLeave());
        assertEquals(3, july.hires());
        assertEquals(1, july.terminations());
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    @Test
    void fiscalYearBoundaryIsJuly() {
        assertEquals(2025, fiscalYearOf(YearMonth.of(2026, 6)));
        assertEquals(2026, fiscalYearOf(YearMonth.of(2026, 7)));
        assertEquals(2026, fiscalYearOf(YearMonth.of(2027, 6)));
    }

    @Test
    void monthKeyIsZeroPadded() {
        assertEquals("202603", monthKey(YearMonth.of(2026, 3)));
        assertEquals("202612", monthKey(YearMonth.of(2026, 12)));
    }

    @Test
    void perPersonMonthlyHoursGuardsEmptyInputs() {
        assertNull(perPersonMonthlyHours(null, 10));
        assertNull(perPersonMonthlyHours(new WorkStats(1200, 1250.0), 0));
        assertEquals(10.0, perPersonMonthlyHours(new WorkStats(1200, 1250.0), 10));
    }
}
