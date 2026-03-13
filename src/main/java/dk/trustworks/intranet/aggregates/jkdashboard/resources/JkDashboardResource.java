package dk.trustworks.intranet.aggregates.jkdashboard.resources;

import dk.trustworks.intranet.aggregates.jkdashboard.dto.*;
import dk.trustworks.intranet.aggregates.jkdashboard.services.JkDashboardService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST resource for the JK (Junior Consultant / Student) Team Dashboard.
 * <p>
 * All endpoints are read-only (GET) and require SYSTEM role (JWT system token).
 * Authorization is enforced in the BFF layer which checks ADMIN role before proxying.
 */
@JBossLog
@Tag(name = "jk-dashboard")
@Path("/jk-dashboard")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class JkDashboardResource {

    @Inject
    JkDashboardService jkDashboardService;

    @GET
    @Path("/all")
    public Response getAll(@QueryParam("fiscalYear") int fiscalYear) {
        log.infof("GET /jk-dashboard/all: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        JkDashboardAllResponse result = jkDashboardService.getAll(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/kpis")
    public Response getKpis(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/kpis: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        JkKpiResponse result = jkDashboardService.getKpis(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/profitability")
    public Response getProfitability(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/profitability: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        JkProfitabilityResponse result = jkDashboardService.getProfitability(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/revenue-leakage")
    public Response getRevenueLeakage(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/revenue-leakage: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        RevenueLeakageResponse result = jkDashboardService.getRevenueLeakage(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/billing-traceability")
    public Response getBillingTraceability(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/billing-traceability: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        List<BillingTraceabilityRow> result = jkDashboardService.getBillingTraceability(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/rate-analysis")
    public Response getRateAnalysis(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/rate-analysis: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        RateAnalysisResponse result = jkDashboardService.getRateAnalysis(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/salary-analysis")
    public Response getSalaryAnalysis(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/salary-analysis: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        SalaryAnalysisResponse result = jkDashboardService.getSalaryAnalysis(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/team-overview")
    public Response getTeamOverview(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/team-overview: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        List<JkTeamMemberSummary> result = jkDashboardService.getTeamOverview(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/conversions")
    public Response getConversions() {
        log.debugf("GET /jk-dashboard/conversions");
        List<JkConversionEntry> result = jkDashboardService.getConversions();
        return Response.ok(result).build();
    }

    @GET
    @Path("/mentor-pairings")
    public Response getMentorPairings(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/mentor-pairings: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        List<MentorPairingEntry> result = jkDashboardService.getMentorPairings(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/client-concentration")
    public Response getClientConcentration(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/client-concentration: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        List<ClientConcentrationEntry> result = jkDashboardService.getClientConcentration(fiscalYear);
        return Response.ok(result).build();
    }

    @GET
    @Path("/per-jk-pnl")
    public Response getPerJkPnl(@QueryParam("fiscalYear") int fiscalYear) {
        log.debugf("GET /jk-dashboard/per-jk-pnl: fiscalYear=%d", fiscalYear);
        validateFiscalYear(fiscalYear);
        List<JkPnlEntry> result = jkDashboardService.getPerJkPnl(fiscalYear);
        return Response.ok(result).build();
    }

    /**
     * Validates that the fiscal year is within a reasonable range.
     */
    private void validateFiscalYear(int fiscalYear) {
        int currentYear = java.time.LocalDate.now().getYear();
        if (fiscalYear < 2020 || fiscalYear > currentYear + 1) {
            throw new BadRequestException("fiscalYear must be between 2020 and " + (currentYear + 1));
        }
    }
}
