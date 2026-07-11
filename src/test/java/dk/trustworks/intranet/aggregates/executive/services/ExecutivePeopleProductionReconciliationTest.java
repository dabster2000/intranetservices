package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.CareerLadderRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.GenderTrendPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.HeadcountCompositionPoint;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.Response;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.WorkforceSummary;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.WorkforceFlowPoint;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit, read-only production reconciliation. Disabled in normal test runs;
 * enable with {@code -Dhr.people.integration=true} and a read-only datasource.
 */
@QuarkusTest
@EnabledIfSystemProperty(named = "hr.people.integration", matches = "true")
class ExecutivePeopleProductionReconciliationTest {

    @Inject
    ExecutivePeopleWorkforceService workforce;

    @Inject
    ExecutivePeopleCareerService career;

    @Inject
    ExecutivePeopleRetentionPayService retentionPay;

    @Test
    void reconcilesFixed2026July10SnapshotAndExecutesAllQueryFamilies() {
        PeopleFilterParams filters = fixedFilters();

        Response<WorkforceSummary> summary = workforce.workforceSummary(filters);
        assertEquals(142L, summary.data().employeeCount());
        assertEquals(131L, summary.data().activeCount());
        assertEquals(11L, summary.data().onLeaveCount());
        assertEquals(5L, summary.data().externalCount());
        assertEquals(130.41d, summary.data().contractedFte(), 0.01d);

        List<HeadcountCompositionPoint> headcount = workforce.headcountComposition(filters).data();
        HeadcountCompositionPoint liveHeadcount = headcount.getLast();
        assertEquals(LocalDate.of(2026, 7, 10), liveHeadcount.date());
        assertEquals(142L, liveHeadcount.employeeTotal());
        assertEquals(5L, liveHeadcount.external());
        assertEquals(130.41d, liveHeadcount.contractedFte(), 0.01d);

        List<GenderTrendPoint> gender = workforce.genderTrend(filters).data();
        GenderTrendPoint liveGender = gender.getLast();
        assertEquals(57L, liveGender.female());
        assertEquals(85L, liveGender.male());
        assertEquals(0L, liveGender.unknown());
        assertEquals(142L, liveGender.total());

        Response<List<CareerLadderRow>> ladder = career.careerLadder(filters);
        assertEquals(21, ladder.data().size());
        CareerLadderRow unassigned = ladder.data().stream()
                .filter(row -> row.level().equals("UNASSIGNED"))
                .findFirst().orElseThrow();
        assertTrue(unassigned.suppressed());
        assertNull(unassigned.count()); // production contains exactly one; privacy must win

        assertFalse(workforce.statusTrend(filters).data().isEmpty());
        List<WorkforceFlowPoint> actualFlow = workforce.workforceFlow(filters).data();
        assertFalse(actualFlow.isEmpty());
        assertTrue(actualFlow.stream().allMatch(point ->
                !YearMonth.parse(point.month()).isAfter(YearMonth.of(2026, 7))));
        assertTrue(workforce.upcomingChanges(filters).data().summary().stream().allMatch(change ->
                change.date().isAfter(filters.asOfDate())
                        && !change.date().isAfter(filters.asOfDate().plusDays(filters.horizonDays()))));
        assertFalse(workforce.tenureDistribution(filters).data().isEmpty());
        assertFalse(career.careerMix(filters).data().isEmpty());
        assertFalse(career.practiceCareerMatrix(filters).data().isEmpty());
        assertFalse(career.leadershipCoverage(filters).data().isEmpty());
        assertFalse(retentionPay.retentionRate(filters).data().isEmpty());
        assertFalse(retentionPay.retentionCohorts(filters).data().isEmpty());
        assertFalse(retentionPay.payEquity(filters).data().isEmpty());
        assertEquals(24, retentionPay.payTrend(filters).data().size());
    }

    private static PeopleFilterParams fixedFilters() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-10";
        request.months = "24";
        request.horizonDays = "90";
        return PeopleFilterParams.from(request);
    }
}
