package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayTrendPoint;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExecutivePeopleAnalyticsSupportTest {

    @Test
    void firstStatusNullPredecessorIsExplicitlyNotEmployed() {
        String predicate = ExecutivePeopleWorkforceService.previousEmployedPredicate("h");

        assertTrue(predicate.startsWith("COALESCE(("));
        assertTrue(predicate.endsWith("),FALSE)"));
        assertTrue(predicate.contains("h.previous_type"));
        assertTrue(predicate.contains("h.previous_status"));
    }

    @Test
    void salaryTieBreakUsesAuditTimeBeforeRandomUuid() {
        assertEquals("s.activefrom DESC,s.created_at DESC,s.uuid DESC",
                ExecutivePeopleRetentionPayService.salaryTemporalOrder("s"));
    }

    @Test
    void retentionDenominatorUsesBothBusinessStatusPrioritiesBeforeAuditFallbacks() {
        assertEquals(
                "s.statusdate DESC,s.transfer_destination DESC,s.same_company_rehire DESC," +
                        "s.created_at DESC,s.uuid DESC",
                ExecutivePeopleRetentionPayService.retentionStatusTemporalOrder("s"));
    }

    @Test
    void leadershipSpanCountsNonLeaderReportsPerLeader() {
        assertEquals(5.0d, ExecutivePeopleCareerService.spanPerLeader(6, 1));
        assertEquals(2.0d, ExecutivePeopleCareerService.spanPerLeader(6, 2));
        assertEquals(0.0d, ExecutivePeopleCareerService.spanPerLeader(1, 2));
        assertNull(ExecutivePeopleCareerService.spanPerLeader(5, 0));
        assertTrue(ExecutivePeopleCareerService.leadershipDetailAvailable(6, 3));
        assertTrue(ExecutivePeopleCareerService.leadershipDetailAvailable(6, 0));
        assertEquals(false, ExecutivePeopleCareerService.leadershipDetailAvailable(6, 1));
        assertEquals(false, ExecutivePeopleCareerService.leadershipDetailAvailable(2, 0));
    }

    @Test
    void payEndpointsRejectNonEmployedPopulationBeforeQuerying() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.population = "ACTIVE";
        PeopleFilterParams filters = PeopleFilterParams.from(request);
        ExecutivePeopleRetentionPayService service = new ExecutivePeopleRetentionPayService();

        assertThrows(BadRequestException.class, () -> service.payEquity(filters));
        assertThrows(BadRequestException.class, () -> service.payTrend(filters));
    }

    @Test
    void smallExcludedPayPopulationGetsDeterministicEligibleComplement() {
        List<PayEquityRow> equity = new ArrayList<>(List.of(
                payRow("B", 4, 4),
                payRow("A", 4, 4),
                payRow("C", 8, 8)));

        assertTrue(ExecutivePeopleRetentionPayService.complementSmallExcludedPayEquity(equity, 2));
        assertTrue(equity.stream().filter(PayEquityRow::suppressed).map(PayEquityRow::groupKey)
                .toList().equals(List.of("A")));

        List<PayTrendPoint> trend = new ArrayList<>(List.of(
                new PayTrendPoint("2026-06", "NORMAL", 100L, 60_000d, 61_000d, false),
                new PayTrendPoint("2026-07", "NORMAL", 101L, 60_500d, 61_500d, false)));
        assertTrue(ExecutivePeopleRetentionPayService.complementSmallExcludedPayTrend(trend, 1));
        assertNull(trend.getLast().count());
        assertTrue(trend.getLast().suppressed());
    }

    private static PayEquityRow payRow(String key, long male, long female) {
        return new PayEquityRow(
                key, key, 0, "NORMAL", male, female,
                60_000d, 58_000d, 61_000d, 59_000d, 3.33d, false);
    }
}
