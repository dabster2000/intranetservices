package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthBaselineDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthTimelineDTO;
import dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for the executive dashboard's Growth &amp; Scenarios tab: the
 * multi-year growth timeline (revenue, cost, people by employee type) and the
 * measured baseline seeding the client-side scenario simulation.
 *
 * <p>Class-level {@code dashboard:read} scope inherits to all endpoints, matching
 * the rest of the executive dashboard resource set.</p>
 */
@JBossLog
@Tag(name = "growth")
@Path("/finance/growth")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class GrowthAnalyticsResource {

    @Inject
    GrowthAnalyticsService growthAnalyticsService;

    /**
     * Multi-year monthly timeline: net revenue (2017-07 →), total cost (2024-07 →,
     * null before) and point-in-time headcount by employee type incl. hires and
     * terminations.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means group view
     * @param costSource BOOKED or BOOKED_PLUS_DRAFT (defaults to BOOKED)
     */
    @GET
    @Path("/timeline")
    public GrowthTimelineDTO timeline(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSource) {
        return growthAnalyticsService.getTimeline(
                CxoSqlSupport.parseCommaSeparated(companyIds),
                CostSource.fromQueryParam(costSource));
    }

    /**
     * Measured trailing-12-month actuals that seed the scenario simulation:
     * current headcount and average salary per employee type, payroll overhead
     * factor, non-payroll OPEX, subcontractor share of revenue, realized rates
     * and billable hours per person.
     *
     * @param companyIds optional comma-separated UUID list; absent/blank means group view
     * @param costSource BOOKED or BOOKED_PLUS_DRAFT (defaults to BOOKED)
     */
    @GET
    @Path("/simulation-baseline")
    public GrowthBaselineDTO simulationBaseline(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSource) {
        return growthAnalyticsService.getSimulationBaseline(
                CxoSqlSupport.parseCommaSeparated(companyIds),
                CostSource.fromQueryParam(costSource));
    }
}
