package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthBaselineDTO;
import dk.trustworks.intranet.aggregates.finance.dto.growth.GrowthTimelineDTO;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastService;
import dk.trustworks.intranet.aggregates.finance.services.GrowthAnalyticsService;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.QueryParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    @Inject
    CashForecastService cashForecastService;

    /** Bank ledger data starts with the cost-data era; nothing can be forecast from before it. */
    private static final LocalDate EARLIEST_AS_OF = LocalDate.of(2024, 7, 1);

    /**
     * Multi-year monthly timeline: net revenue (2017-07 →), total cost (2024-07 →,
     * null before), combined bank liquidity (balance + net flow, from the
     * e-conomic import) and point-in-time headcount by employee type incl. hires
     * and terminations.
     *
     * <p>Group-level only — liquidity is managed by moving money between the
     * three companies, so this endpoint deliberately has no company filter.</p>
     *
     * @param costSource BOOKED or BOOKED_PLUS_DRAFT (defaults to BOOKED)
     */
    @GET
    @Path("/timeline")
    public GrowthTimelineDTO timeline(@QueryParam("costSource") String costSource) {
        return growthAnalyticsService.getTimeline(CostSource.fromQueryParam(costSource));
    }

    /**
     * Measured trailing-12-month actuals that seed the scenario simulation:
     * current headcount and average salary per employee type, payroll overhead
     * factor, non-payroll OPEX, subcontractor share of revenue, realized rates
     * and billable hours per person, plus the liquidity seeds (combined bank
     * balance, measured cash conversion, seasonal flow pattern, dividend
     * history). Group-level only, like the timeline.
     *
     * @param costSource BOOKED or BOOKED_PLUS_DRAFT (defaults to BOOKED)
     */
    @GET
    @Path("/simulation-baseline")
    public GrowthBaselineDTO simulationBaseline(@QueryParam("costSource") String costSource) {
        return growthAnalyticsService.getSimulationBaseline(CostSource.fromQueryParam(costSource));
    }

    /**
     * Direct-method cash forecast: the combined bank balance rolled forward day
     * by day from {@code asOf} with scheduled collections (open receivables,
     * unbilled work, contracted work, assumed extensions), payroll, VAT,
     * corporate tax, supplier payments and dividends, aggregated to months with
     * end-of-month and intra-month-low balances. Every driver is measured; see
     * {@code CashForecastEngine}.
     *
     * @param asOf              ISO date to forecast from (default today). A past date
     *                          builds the forecast from what was known then and
     *                          returns the real balances that followed (backtest)
     * @param horizonMonths     1–12 (default 6)
     * @param dividendAnnualDkk annual dividend assumption; default = last 12 months' measured payouts
     * @param extensionFillPct  0–100 share of the gap between contracted work and the revenue
     *                          run rate assumed to be filled by extensions (default 100)
     */
    @GET
    @Path("/cash-forecast")
    public CashForecastDTO cashForecast(
            @QueryParam("asOf") String asOf,
            @QueryParam("horizonMonths") Integer horizonMonths,
            @QueryParam("dividendAnnualDkk") Double dividendAnnualDkk,
            @QueryParam("extensionFillPct") Double extensionFillPct) {
        LocalDate today = LocalDate.now();
        LocalDate asOfDate = today;
        if (asOf != null && !asOf.isBlank()) {
            try {
                asOfDate = LocalDate.parse(asOf.trim());
            } catch (DateTimeParseException e) {
                throw new BadRequestException("asOf must be an ISO date (YYYY-MM-DD)");
            }
        }
        if (asOfDate.isAfter(today) || asOfDate.isBefore(EARLIEST_AS_OF)) {
            throw new BadRequestException("asOf must be between " + EARLIEST_AS_OF + " and today");
        }
        int horizon = horizonMonths != null ? horizonMonths : 6;
        if (horizon < 1 || horizon > 12) {
            throw new BadRequestException("horizonMonths must be between 1 and 12");
        }
        if (dividendAnnualDkk != null && (dividendAnnualDkk < 0 || dividendAnnualDkk > 1_000_000_000d)) {
            throw new BadRequestException("dividendAnnualDkk must be between 0 and 1,000,000,000");
        }
        double fill = extensionFillPct != null ? extensionFillPct : 100d;
        if (fill < 0 || fill > 100) {
            throw new BadRequestException("extensionFillPct must be between 0 and 100");
        }
        return cashForecastService.forecast(asOfDate, horizon, dividendAnnualDkk, fill);
    }
}
