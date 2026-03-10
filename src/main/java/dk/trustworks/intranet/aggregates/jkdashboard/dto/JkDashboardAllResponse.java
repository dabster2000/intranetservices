package dk.trustworks.intranet.aggregates.jkdashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Combined response containing all JK Dashboard data.
 * <p>
 * Used by the {@code GET /jk-dashboard/all} endpoint to return everything
 * in a single request, avoiding the performance problem of 11 concurrent
 * API calls each redundantly computing getBillingTraceability().
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JkDashboardAllResponse {
    private JkKpiResponse kpis;
    private JkProfitabilityResponse profitability;
    private RevenueLeakageResponse revenueLeakage;
    private List<BillingTraceabilityRow> billingTraceability;
    private RateAnalysisResponse rateAnalysis;
    private SalaryAnalysisResponse salaryAnalysis;
    private List<JkTeamMemberSummary> teamOverview;
    private List<JkConversionEntry> conversions;
    private List<MentorPairingEntry> mentorPairings;
    private List<ClientConcentrationEntry> clientConcentration;
    private List<JkPnlEntry> perJkPnl;
}
