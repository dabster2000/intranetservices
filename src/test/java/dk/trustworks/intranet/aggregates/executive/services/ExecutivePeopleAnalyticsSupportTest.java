package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayEquityRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayQuartileRow;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecutivePeopleAnalyticsDTOs.PayTrendPoint;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterParams;
import dk.trustworks.intranet.aggregates.executive.people.PeopleFilterRequest;
import dk.trustworks.intranet.userservice.model.enums.DstEmploymentFunction;
import dk.trustworks.intranet.userservice.model.enums.DstEmploymentStatus;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(ExecutivePeopleCareerService.leadershipDetailAvailable(6, 1));
        assertEquals(false, ExecutivePeopleCareerService.leadershipDetailAvailable(2, 0));
        assertNull(ExecutivePeopleCareerService.leadershipDetailUnavailableReason(6));
        assertEquals("TEAM_BELOW_PRIVACY_THRESHOLD",
                ExecutivePeopleCareerService.leadershipDetailUnavailableReason(2));
        assertEquals("LEADER_ROLE_HIDDEN_BELOW_PRIVACY_THRESHOLD",
                ExecutivePeopleCareerService.leadershipDetailPrivacyReason(6, 1));
    }

    @Test
    void payEndpointsRejectNonEmployedPopulationBeforeQuerying() {
        PeopleFilterRequest request = new PeopleFilterRequest();
        request.population = "ACTIVE";
        PeopleFilterParams filters = PeopleFilterParams.from(request);
        ExecutivePeopleRetentionPayService service = new ExecutivePeopleRetentionPayService();

        assertThrows(BadRequestException.class, () -> service.payEquity(filters));
        assertThrows(BadRequestException.class, () -> service.payQuartiles(filters));
        assertThrows(BadRequestException.class, () -> service.payTrend(filters));
    }

    @Test
    void smallExcludedPayPopulationGetsDeterministicEligibleComplement() {
        List<PayEquityRow> equity = new ArrayList<>(List.of(
                payRow("OVERALL", 16, 16),
                payRow("B", 4, 4),
                payRow("A", 4, 4),
                payRow("C", 8, 8)));

        assertTrue(ExecutivePeopleRetentionPayService.complementSmallExcludedPayEquity(equity, 2));
        assertEquals(List.of("B", "A"), equity.stream()
                .filter(PayEquityRow::suppressed)
                .map(PayEquityRow::groupKey)
                .toList());
        assertFalse(equity.getFirst().suppressed());

        List<PayTrendPoint> trend = new ArrayList<>(List.of(
                new PayTrendPoint("2026-06", "NORMAL", 100L, 60_000d, 61_000d, false),
                new PayTrendPoint("2026-07", "NORMAL", 101L, 60_500d, 61_500d, false)));
        assertTrue(ExecutivePeopleRetentionPayService.complementSmallExcludedPayTrend(trend, 1));
        assertNull(trend.getLast().count());
        assertTrue(trend.getLast().suppressed());
    }

    @Test
    void singleSuppressedPayGroupGetsOneDeterministicComplementBesideOverall() {
        List<PayEquityRow> equity = new ArrayList<>(List.of(
                payRow("OVERALL", 16, 16),
                suppressedPayRow("A"),
                payRow("B", 4, 4),
                payRow("C", 8, 8)));

        assertTrue(ExecutivePeopleRetentionPayService.complementSmallExcludedPayEquity(equity, 0));
        assertEquals(List.of("A", "B"), equity.stream()
                .filter(row -> row.suppressed() && !"OVERALL".equals(row.groupKey()))
                .map(PayEquityRow::groupKey)
                .toList());
        assertFalse(equity.getFirst().suppressed());
        assertEquals("COMPLEMENTARY_PRIVACY_SUPPRESSION", equity.get(2).reviewReason());
    }

    @Test
    void multipleSuppressedPayGroupsAlreadyProtectVisibleOverall() {
        List<PayEquityRow> equity = new ArrayList<>(List.of(
                payRow("OVERALL", 16, 16),
                suppressedPayRow("A"),
                suppressedPayRow("B"),
                payRow("C", 8, 8)));

        assertFalse(ExecutivePeopleRetentionPayService.complementSmallExcludedPayEquity(equity, 0));
        assertFalse(equity.getFirst().suppressed());
        assertFalse(equity.getLast().suppressed());
    }

    @Test
    void payReviewScreenUsesAbsoluteMeanGapWithoutMakingAComplianceFinding() {
        assertEquals(5.0d, ExecutivePeopleRetentionPayService.signedGapPct(100d, 95d));
        assertEquals(-5.0d, ExecutivePeopleRetentionPayService.signedGapPct(100d, 105d));
        assertNull(ExecutivePeopleRetentionPayService.signedGapPct(0d, 95d));
        assertEquals("OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_AT_LEAST_FIVE_PERCENT",
                ExecutivePeopleRetentionPayService.reviewReason(false, true));
        assertEquals("OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_BELOW_FIVE_PERCENT",
                ExecutivePeopleRetentionPayService.reviewReason(false, false));
        assertEquals("PAY_CELL_BELOW_PRIVACY_THRESHOLD",
                ExecutivePeopleRetentionPayService.reviewReason(true, null));
        assertEquals(
                "DISCO-08 function/category classification covers 120 of 142 eligible recorded-gender contractual-pay records; 22 remain unassigned.",
                ExecutivePeopleRetentionPayService.discoCoverageCaveat(142, 22));
        assertTrue(ExecutivePeopleRetentionPayService.discoCoverageCaveat(142, 2)
                .contains("privacy-suppressed"));
        assertEquals(
                ExecutivePeopleRetentionPayService.discoGroupKey(
                        DstEmploymentFunction.CEO, DstEmploymentStatus.EMPLOYEES),
                ExecutivePeopleRetentionPayService.discoGroupKey(
                        DstEmploymentFunction.CXO, DstEmploymentStatus.EMPLOYEES),
                "Enum values sharing DISCO code 112010 must aggregate into one legal function/category cell");
        String codeCase = ExecutivePeopleRetentionPayService.discoCodeCase("dst");
        assertTrue(codeCase.contains("WHEN 'CEO' THEN '112010'"));
        assertTrue(codeCase.contains("WHEN 'CXO' THEN '112010'"));
    }

    @Test
    void exhaustivePayQuartilesUseDeterministicComplementarySuppression() {
        List<PayQuartileRow> onePersonCell = new ArrayList<>(List.of(
                quartile("Q1_LOWEST", 1, 5, true),
                quartile("Q2_LOWER_MIDDLE", 5, 5, false),
                quartile("Q3_UPPER_MIDDLE", 6, 6, false),
                quartile("Q4_HIGHEST", 7, 7, false)));
        assertTrue(ExecutivePeopleRetentionPayService.suppressQuartileComplement(onePersonCell));
        assertTrue(onePersonCell.get(1).suppressed(), "smallest visible quartile is the deterministic complement");

        List<PayQuartileRow> zeroGenderCell = new ArrayList<>(List.of(
                quartile("Q1_LOWEST", 0, 5, true),
                quartile("Q2_LOWER_MIDDLE", 5, 5, false),
                quartile("Q3_UPPER_MIDDLE", 6, 6, false),
                quartile("Q4_HIGHEST", 7, 7, false)));
        assertTrue(ExecutivePeopleRetentionPayService.suppressQuartileComplement(zeroGenderCell));
        assertTrue(zeroGenderCell.get(1).suppressed());

        List<PayQuartileRow> smallExcludedPopulation = new ArrayList<>(List.of(
                quartile("Q1_LOWEST", 5, 5, false),
                quartile("Q2_LOWER_MIDDLE", 6, 6, false),
                quartile("Q3_UPPER_MIDDLE", 7, 7, false),
                quartile("Q4_HIGHEST", 8, 8, false)));
        assertTrue(ExecutivePeopleRetentionPayService.suppressQuartileComplement(
                smallExcludedPopulation, true));
        assertTrue(smallExcludedPopulation.getFirst().suppressed());

        assertEquals("Q1_LOWEST", ExecutivePeopleRetentionPayService.quartileKey(1));
        assertEquals("Q2_LOWER_MIDDLE", ExecutivePeopleRetentionPayService.quartileKey(2));
        assertEquals("Q3_UPPER_MIDDLE", ExecutivePeopleRetentionPayService.quartileKey(3));
        assertEquals("Q4_HIGHEST", ExecutivePeopleRetentionPayService.quartileKey(4));
    }

    private static PayEquityRow payRow(String key, long male, long female) {
        return new PayEquityRow(
                key, key, 0, "NORMAL", male, female,
                60_000d, 58_000d, 61_000d, 59_000d, 3.33d, 3.28d,
                false, "OBSERVED_ABSOLUTE_MEAN_CONTRACTUAL_PAY_GAP_BELOW_FIVE_PERCENT", false);
    }

    private static PayEquityRow suppressedPayRow(String key) {
        return new PayEquityRow(
                key, key, 0, "NORMAL", null, null,
                null, null, null, null, null, null,
                null, "PAY_CELL_BELOW_PRIVACY_THRESHOLD", true);
    }

    private static PayQuartileRow quartile(String key, long male, long female, boolean suppressed) {
        return new PayQuartileRow(
                key, key, 0, "NORMAL",
                suppressed ? null : male,
                suppressed ? null : female,
                suppressed ? null : 50d,
                suppressed ? null : 50d,
                suppressed);
    }
}
