package dk.trustworks.intranet.aggregates.finance.resources;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.getDefaultReportingFiscalYear;

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
import dk.trustworks.intranet.aggregates.finance.dto.TeamBirthdayDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCalendarDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamCapacityDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamJkProfitDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamMemberAssignmentsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamPricingModelDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamRegisteredVsInvoicedDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickHoursDTO;
import dk.trustworks.intranet.aggregates.finance.services.HourlyTeamDashboardService;
import dk.trustworks.intranet.aggregates.finance.services.TeamBirthdayService;
import dk.trustworks.intranet.aggregates.finance.services.TeamCalendarService;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionDTO;
import dk.trustworks.intranet.aggregates.userprofile.services.UserProfileExtensionService;
import dk.trustworks.intranet.contracts.dto.ZeroRateWatchlistDTO;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Set;
import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.capToLastCompleteMonth;
import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.getFiscalYearRange;
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

    // JK Team 2.0 WP6 — the kind-aware card set (§4.6.0), calendar (§4.6.5),
    // birthdays (§4.6.6) and the profile extension read for the roster (§4.6.3)
    @Inject
    HourlyTeamDashboardService hourlyTeamDashboardService;

    @Inject
    TeamCalendarService teamCalendarService;

    @Inject
    TeamBirthdayService teamBirthdayService;

    @Inject
    UserProfileExtensionService userProfileExtensionService;

    // -----------------------------------------------------------------------
    // 1. Overview
    // -----------------------------------------------------------------------

    @GET
    @Path("/{teamId}/overview")
    public TeamOverviewDTO getOverview(
            @PathParam("teamId") String teamId,
            @QueryParam("fiscalYear") Integer fiscalYear) {
        int fy = effectiveFiscalYear(fiscalYear);
        log.debugf("GET /finance/team/%s/overview?fiscalYear=%d", teamId, fy);
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        return teamDashboardService.getOverview(teamId, fy);
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
    // 17. JK Team 2.0 WP6 — kind-aware cards, calendar, birthdays, profiles
    // Every endpoint: dashboard:read + LEADER/SPONSOR of the team (validateTeamAccess).
    // The hourly-only endpoints read the team's STUDENT members; a SALARIED team
    // simply gets an empty result, never a widened CONSULTANT query.
    // -----------------------------------------------------------------------

    /** §4.6.6 (D5): upcoming birthdays among current members, both kinds. Day and month only. */
    @GET
    @Path("/{teamId}/birthdays")
    public List<TeamBirthdayDTO> getBirthdays(@PathParam("teamId") String teamId,
                                              @QueryParam("days") @DefaultValue("30") int days) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        Set<String> members = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        return teamBirthdayService.upcoming(members, LocalDate.now(), Math.min(Math.max(days, 0), 366));
    }

    /** §4.6.5: members × months with contract, internal-assignment, ferie and orlov spans, both kinds. */
    @GET
    @Path("/{teamId}/calendar")
    public TeamCalendarDTO getCalendar(@PathParam("teamId") String teamId,
                                       @QueryParam("from") String from,
                                       @QueryParam("months") @DefaultValue("12") int months) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        YearMonth start = from == null || from.isBlank() ? YearMonth.now().minusMonths(1) : parseMonth(from);
        Set<String> members = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        return teamCalendarService.getCalendar(members, start, months);
    }

    /** §4.6.4 / tab matrix "Capacity": declared vs registered per member per week, hourly kind. */
    @GET
    @Path("/{teamId}/capacity")
    public TeamCapacityDTO getCapacity(@PathParam("teamId") String teamId,
                                       @QueryParam("from") String from,
                                       @QueryParam("weeks") @DefaultValue("12") int weeks) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        LocalDate start = from == null || from.isBlank() ? LocalDate.now().minusWeeks(4) : parseDate(from);
        Set<String> members = teamDashboardService.getStudentMemberUuids(teamId, LocalDate.now());
        return hourlyTeamDashboardService.getCapacity(members, start, weeks, LocalDate.now());
    }

    /** §4.6.2 row 2.2.1: JK Profit per junior, month and FY-to-date, hourly kind. */
    @GET
    @Path("/{teamId}/jk-profit")
    public TeamJkProfitDTO getJkProfit(@PathParam("teamId") String teamId,
                                       @QueryParam("fiscalYear") Integer fiscalYear) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        var fy = getFiscalYearRange(effectiveFiscalYear(fiscalYear));
        Set<String> members = teamDashboardService.getStudentMemberUuids(teamId, LocalDate.now());
        return hourlyTeamDashboardService.getJkProfit(members, fy, capToLastCompleteMonth(fy.end()));
    }

    /** §4.6.2: registered vs invoiced restricted to the team's junior members, trailing twelve months. */
    @GET
    @Path("/{teamId}/registered-vs-invoiced")
    public TeamRegisteredVsInvoicedDTO getRegisteredVsInvoiced(@PathParam("teamId") String teamId,
                                                               @QueryParam("end") String end) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        YearMonth endMonth = end == null || end.isBlank() ? YearMonth.now().minusMonths(1) : parseMonth(end);
        Set<String> members = teamDashboardService.getStudentMemberUuids(teamId, LocalDate.now());
        return hourlyTeamDashboardService.getRegisteredVsInvoiced(members, endMonth);
    }

    /** WP5 §4.5 / Staffing (hourly): distinct juniors with an active line per pricing model in the FY. */
    @GET
    @Path("/{teamId}/pricing-model-distribution")
    public TeamPricingModelDistributionDTO getPricingModelDistribution(@PathParam("teamId") String teamId,
                                                                       @QueryParam("fiscalYear") Integer fiscalYear) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        var fy = getFiscalYearRange(effectiveFiscalYear(fiscalYear));
        Set<String> members = teamDashboardService.getStudentMemberUuids(teamId, LocalDate.now());
        return hourlyTeamDashboardService.getPricingModelDistribution(members, fy.start(), fy.end());
    }

    /** WP4b §4.4.3: the step-up watchlist restricted to this team's members. */
    @GET
    @Path("/{teamId}/zero-rate-watchlist")
    public List<ZeroRateWatchlistDTO> getZeroRateWatchlist(@PathParam("teamId") String teamId,
                                                           @QueryParam("withinDays") @DefaultValue("30") int withinDays) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        Set<String> members = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        return hourlyTeamDashboardService.watchlistFor(members, LocalDate.now(), Math.min(Math.max(withinDays, 0), 365));
    }

    /** People (hourly): sick leave in hours — the count, not the 120-day threshold. */
    @GET
    @Path("/{teamId}/sick-hours")
    public List<TeamSickHoursDTO> getSickHours(@PathParam("teamId") String teamId) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        Set<String> members = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        return hourlyTeamDashboardService.getSickHours(members, LocalDate.now());
    }

    /** §4.6.3: education & profile facts plus competence tags for every current member. */
    @GET
    @Path("/{teamId}/profiles")
    public List<UserProfileExtensionDTO> getProfiles(@PathParam("teamId") String teamId) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        Set<String> members = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        return userProfileExtensionService.getMany(members.stream().sorted().toList());
    }

    /** §4.6.4: one member's contract lines, internal assignments and declared-vs-actual per month. */
    @GET
    @Path("/{teamId}/members/{useruuid}/assignments")
    public TeamMemberAssignmentsDTO getMemberAssignments(@PathParam("teamId") String teamId,
                                                         @PathParam("useruuid") String useruuid,
                                                         @QueryParam("from") String from,
                                                         @QueryParam("months") @DefaultValue("6") int months) {
        teamDashboardService.validateTeamAccess(teamId, requestHeaderHolder.getUserUuid());
        Set<String> members = teamDashboardService.getAllTeamMemberUuids(teamId, LocalDate.now());
        if (!members.contains(useruuid)) {
            throw new NotFoundException("Not a current member of this team");
        }
        YearMonth start = from == null || from.isBlank() ? YearMonth.now().minusMonths(2) : parseMonth(from);
        return hourlyTeamDashboardService.getMemberAssignments(useruuid, start, months);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid date: " + value);
        }
    }

    private static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid month: " + value);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int effectiveFiscalYear(Integer fiscalYear) {
        // Default to the FY of the last complete month: in July the new FY has no
        // complete months yet, so defaulting to it would serve empty/partial data.
        return fiscalYear != null ? fiscalYear : getDefaultReportingFiscalYear();
    }
}
