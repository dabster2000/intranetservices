package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.AllTeamsUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBenchConsultantDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBillingRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TimeRegistrationComplianceDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBudgetFulfillmentDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamClientConcentrationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContractTimelineDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContributionMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamExpiringContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamForwardAllocationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamOverviewDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRevenueCostTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRevenuePerMemberDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamUtilizationHeatmapDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamUtilizationTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.UnprofitableConsultantDTO;
import dk.trustworks.intranet.aggregates.finance.services.TeamDashboardService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST resource for Team Lead Dashboard finance and staffing data.
 * All endpoints are read-only (GET) and require the requesting user to be
 * a LEADER of the specified team.
 *
 * <p>URL pattern: {@code /finance/team/{teamId}/...}
 *
 * <p>The resource is thin — all business logic lives in {@link TeamDashboardService}.
 */
@JBossLog
@Tag(name = "finance")
@Path("/finance/team")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class TeamDashboardResource {

    @Inject
    TeamDashboardService teamDashboardService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // -----------------------------------------------------------------------
    // 1. Overview
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/overview")
    public TeamOverviewDTO getOverview(@PathParam("teamId") String teamId) {
        log.debugf("GET /finance/team/%s/overview", teamId);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getOverview(teamId);
    }

    // -----------------------------------------------------------------------
    // 2. Utilization Trend (15 months: FY + 3 months before)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/utilization-trend")
    public List<TeamUtilizationTrendDTO> getUtilizationTrend(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/utilization-trend?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getUtilizationTrend(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 3. Utilization Heatmap (per member x per month)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/utilization-heatmap")
    public TeamUtilizationHeatmapDTO getUtilizationHeatmap(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/utilization-heatmap?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getUtilizationHeatmap(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 4. Budget Fulfillment (current month)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/budget-fulfillment")
    public List<TeamBudgetFulfillmentDTO> getBudgetFulfillment(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/budget-fulfillment?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getBudgetFulfillment(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 5. All Teams Utilization (for ranking chart)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/all-teams-utilization")
    public List<AllTeamsUtilizationDTO> getAllTeamsUtilization(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/all-teams-utilization?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getAllTeamsUtilization(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 6. Contract Timeline
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/contract-timeline")
    public TeamContractTimelineDTO getContractTimeline(
            @PathParam("teamId") String teamId,
            @QueryParam("lookbackMonths") @DefaultValue("6") int lookbackMonths) {
        log.debugf("GET /finance/team/%s/contract-timeline?lookbackMonths=%d", teamId, lookbackMonths);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        int capped = Math.min(Math.max(lookbackMonths, 1), 36);
        return teamDashboardService.getContractTimeline(teamId, capped);
    }

    // -----------------------------------------------------------------------
    // 7. Forward Allocation (next 6 months)
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/forward-allocation")
    public TeamForwardAllocationDTO getForwardAllocation(@PathParam("teamId") String teamId) {
        log.debugf("GET /finance/team/%s/forward-allocation", teamId);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getForwardAllocation(teamId);
    }

    // -----------------------------------------------------------------------
    // 8. Expiring Contracts
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/expiring-contracts")
    public List<TeamExpiringContractDTO> getExpiringContracts(
            @PathParam("teamId") String teamId,
            @QueryParam("days") @DefaultValue("90") int days) {
        int effectiveDays = Math.min(days, 180);
        log.debugf("GET /finance/team/%s/expiring-contracts?days=%d", teamId, effectiveDays);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getExpiringContracts(teamId, effectiveDays);
    }

    // -----------------------------------------------------------------------
    // 9. Bench Consultants
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/bench-consultants")
    public List<TeamBenchConsultantDTO> getBenchConsultants(@PathParam("teamId") String teamId) {
        log.debugf("GET /finance/team/%s/bench-consultants", teamId);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getBenchConsultants(teamId);
    }

    // -----------------------------------------------------------------------
    // 10. Revenue vs Cost Trend
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/revenue-cost-trend")
    public List<TeamRevenueCostTrendDTO> getRevenueCostTrend(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/revenue-cost-trend?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getRevenueCostTrend(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 11. Revenue Per Member
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/revenue-per-member")
    public List<TeamRevenuePerMemberDTO> getRevenuePerMember(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/revenue-per-member?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getRevenuePerMember(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 12. Billing Rate Analysis
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/billing-rate-analysis")
    public List<TeamBillingRateDTO> getBillingRateAnalysis(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/billing-rate-analysis?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getBillingRateAnalysis(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 13. Contribution Margin
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/contribution-margin")
    public TeamContributionMarginDTO getContributionMargin(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/contribution-margin?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getContributionMargin(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 14. Client Concentration
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/client-concentration")
    public List<TeamClientConcentrationDTO> getClientConcentration(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/client-concentration?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getClientConcentration(teamId, fy);
    }

    // -----------------------------------------------------------------------
    // 15. Consultant Profitability
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/consultant-profitability")
    public List<UnprofitableConsultantDTO> getConsultantProfitability(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear,
            @QueryParam("userId") String userId,
            @QueryParam("period") @DefaultValue("ttm") String period) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/consultant-profitability?fiscalYear=%d&userId=%s&period=%s",
                teamId, fy, userId, period);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getConsultantProfitability(teamId, fy, userId, period);
    }

    // -----------------------------------------------------------------------
    // 16. Time Registration Compliance
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/consultant-compliance")
    public TimeRegistrationComplianceDTO getConsultantCompliance(
            @PathParam("teamId") String teamId,
            @QueryParam("userId") String userId) {
        log.debugf("GET /finance/team/%s/consultant-compliance?userId=%s", teamId, userId);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        if (userId == null || userId.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("userId query parameter is required");
        }
        return teamDashboardService.getConsultantCompliance(teamId, userId);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int effectiveFiscalYear(Integer fiscalYear) {
        return fiscalYear != null ? fiscalYear : teamDashboardService.currentFiscalYear();
    }
}
