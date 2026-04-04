package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.analytics.SalaryByBandDTO;
import dk.trustworks.intranet.aggregates.finance.services.analytics.SalaryAnalyticsProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST resource for unified cost analytics endpoints.
 *
 * Replaces raw SQL in Next.js BFF routes with proper Quarkus service layer.
 * All calculation logic lives in composable provider beans.
 */
@JBossLog
@Tag(name = "finance")
@Path("/finance/analytics")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CostAnalyticsResource {

    @Inject
    SalaryAnalyticsProvider salaryAnalyticsProvider;

    /**
     * Average salary per career band per month (18-month trailing window).
     * Replaces BFF route: /api/cxo/cost/salary-development
     */
    @GET
    @Path("/salary-by-band")
    public List<SalaryByBandDTO> getAvgSalaryByBand(
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getAvgSalaryByBand(fromDate, toDate, companyIds.isEmpty() ? null : companyIds);
    }

    /**
     * Total salary per career band per month (18-month trailing window).
     * Replaces BFF route: /api/executive/total-salary-development
     */
    @GET
    @Path("/total-salary-by-band")
    public List<SalaryByBandDTO> getTotalSalaryByBand(
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getTotalSalaryByBand(fromDate, toDate, companyIds.isEmpty() ? null : companyIds);
    }
}
