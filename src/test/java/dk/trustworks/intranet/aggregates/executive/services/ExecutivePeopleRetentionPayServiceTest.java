package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayQuartileRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.RetentionCohortPoint;
import dk.trustworks.intranet.aggregates.executive.people.PeopleAnalyticsRepository;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import jakarta.persistence.Tuple;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutivePeopleRetentionPayServiceTest {

    @Test
    void milestonePrivacyCarriesSmallEventsForwardUntilThePooledIntervalIsSafe() {
        List<RetentionCohortPoint> points = ExecutivePeopleRetentionPayService.privacySafeMilestones(
                10,
                List.of(
                        new ExecutivePeopleRetentionPayService.CohortObservation(0, 10, 0),
                        new ExecutivePeopleRetentionPayService.CohortObservation(1, 10, 1),
                        new ExecutivePeopleRetentionPayService.CohortObservation(6, 9, 0),
                        new ExecutivePeopleRetentionPayService.CohortObservation(12, 9, 2),
                        new ExecutivePeopleRetentionPayService.CohortObservation(24, 7, 1),
                        new ExecutivePeopleRetentionPayService.CohortObservation(36, 6, 2)),
                36);

        assertEquals(List.of(0, 6, 12, 24, 36), points.stream().map(RetentionCohortPoint::month).toList());
        assertEquals(100.0d, points.get(0).survivalPct());
        assertTrue(points.get(1).suppressed());
        assertNull(points.get(1).intervalEvents());
        assertEquals("INTERVAL_EVENTS_BELOW_PRIVACY_THRESHOLD", points.get(1).suppressionReason());
        assertFalse(points.get(2).suppressed());
        assertEquals(0, points.get(2).intervalStartMonth());
        assertEquals(3L, points.get(2).intervalEvents());
        assertEquals(70.0d, points.get(2).survivalPct());
        assertTrue(points.get(3).suppressed());
        assertFalse(points.get(4).suppressed());
        assertEquals(12, points.get(4).intervalStartMonth());
        assertEquals(3L, points.get(4).intervalEvents());
        assertEquals(40.0d, points.get(4).survivalPct());
    }

    @Test
    void payEquityAddsOverallRowAndUsesMeanGapForNeutralReviewScreen() {
        PeopleAnalyticsRepository repository = mock(PeopleAnalyticsRepository.class);
        List<Tuple> payRows = List.of(
                payRow("EXPERIENCED", 10, 10, 60_000d, 57_000d, 62_000d, 55_800d),
                payRow("OVERALL", 10, 10, 60_000d, 57_000d, 62_000d, 55_800d));
        when(repository.tuples(eq("pay-equity"), anyString(), anyMap())).thenReturn(payRows);
        Tuple population = mock(Tuple.class);
        when(population.get("people_count")).thenReturn(20L);
        when(repository.tuples(eq("pay-population-count"), anyString(), anyMap()))
                .thenReturn(List.of(population));
        ExecutivePeopleRetentionPayService service = new ExecutivePeopleRetentionPayService();
        service.repository = repository;

        var response = service.payEquity(filters());
        PayEquityRow overall = response.data().getFirst();

        assertEquals("OVERALL", overall.groupKey());
        assertEquals(5.0d, overall.payGapPct());
        assertEquals(10.0d, overall.meanPayGapPct());
        assertEquals(Boolean.TRUE, overall.reviewThresholdMet());
        assertEquals("OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_AT_LEAST_FIVE_PERCENT", overall.reviewReason());
        assertEquals(20L, response.meta().sampleSize());
    }

    @Test
    void payQuartilesUseFourDeterministicNtilesAndWithinQuartileGenderShares() {
        PeopleAnalyticsRepository repository = mock(PeopleAnalyticsRepository.class);
        List<Tuple> quartileRows = List.of(
                quartileRow(1, 12, 8),
                quartileRow(2, 10, 10),
                quartileRow(3, 8, 12),
                quartileRow(4, 6, 14));
        when(repository.tuples(eq("pay-quartiles"), anyString(), anyMap())).thenReturn(quartileRows);
        Tuple population = mock(Tuple.class);
        when(population.get("people_count")).thenReturn(80L);
        when(repository.tuples(eq("pay-population-count"), anyString(), anyMap()))
                .thenReturn(List.of(population));
        ExecutivePeopleRetentionPayService service = new ExecutivePeopleRetentionPayService();
        service.repository = repository;

        var response = service.payQuartiles(filters());
        List<PayQuartileRow> quartiles = response.data();

        assertEquals(List.of("Q1_LOWEST", "Q2_LOWER_MIDDLE", "Q3_UPPER_MIDDLE", "Q4_HIGHEST"),
                quartiles.stream().map(PayQuartileRow::key).toList());
        assertEquals(60.0d, quartiles.getFirst().maleSharePct());
        assertEquals(40.0d, quartiles.getFirst().femaleSharePct());
        assertEquals(80L, response.meta().sampleSize());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(repository).tuples(eq("pay-quartiles"), sql.capture(), anyMap());
        assertTrue(sql.getValue().contains("NTILE(4)"));
    }

    private static PeopleFilterParams filters() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.asOfDate = "2026-07-10";
        request.months = "24";
        return PeopleFilterParams.from(request);
    }

    private static Tuple payRow(
            String group,
            long maleCount,
            long femaleCount,
            double maleMedian,
            double femaleMedian,
            double maleMean,
            double femaleMean) {
        Tuple row = mock(Tuple.class);
        when(row.get("group_key", String.class)).thenReturn(group);
        when(row.get("male_count")).thenReturn(maleCount);
        when(row.get("female_count")).thenReturn(femaleCount);
        when(row.get("male_median")).thenReturn(maleMedian);
        when(row.get("female_median")).thenReturn(femaleMedian);
        when(row.get("male_mean")).thenReturn(maleMean);
        when(row.get("female_mean")).thenReturn(femaleMean);
        return row;
    }

    private static Tuple quartileRow(int quartile, long male, long female) {
        Tuple row = mock(Tuple.class);
        when(row.get("quartile_number")).thenReturn((long) quartile);
        when(row.get("male_count")).thenReturn(male);
        when(row.get("female_count")).thenReturn(female);
        return row;
    }
}
