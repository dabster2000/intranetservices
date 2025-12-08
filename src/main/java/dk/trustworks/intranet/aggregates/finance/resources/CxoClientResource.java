package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.ActiveClientsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.AvgEngagementLengthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.AvgRevenuePerClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientDetailTableDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientPortfolioBubbleDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientRetentionTrendDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientRevenueParetoDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConcentrationIndexDTO;
import dk.trustworks.intranet.aggregates.finance.dto.EngagementByCompanyDTO;
import dk.trustworks.intranet.aggregates.finance.dto.IndustryDistributionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ServiceLinePenetrationDTO;
import dk.trustworks.intranet.aggregates.finance.services.CxoClientService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for CxO dashboard client metrics.
 * Provides client-level KPIs for executive dashboards.
 */
@JBossLog
@Tag(name = "clients")
@Path("/clients/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class CxoClientResource {

    @Inject
    CxoClientService cxoClientService;

    /**
     * Gets Active Clients (TTM) KPI data.
     * Returns the count of distinct clients with revenue in a trailing 12-month window,
     * along with year-over-year comparison and monthly sparkline trend.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional, e.g., "PUBLIC,HEALTH")
     * @param serviceLines Comma-separated service line IDs (optional, e.g., "PM,DEV")
     * @param contractTypes Comma-separated contract type IDs (optional, e.g., "T&M,FIXED")
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ActiveClientsDTO with current count, prior count, YoY change, and sparkline
     */
    @GET
    @Path("/active-count")
    public Response getActiveClients(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/active-count: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ActiveClientsDTO result = cxoClientService.getActiveClients(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Active clients - Current: %d, Prior: %d, YoY: %+d (%.2f%%)",
                result.getCurrentTTMCount(), result.getPriorTTMCount(),
                result.getYoyChange(), result.getYoyChangePercent());

        return Response.ok(result).build();
    }

    /**
     * Gets HHI (Herfindahl-Hirschman Index) Concentration metric.
     * Measures revenue concentration risk across client portfolio.
     * Lower values indicate more diversification (better).
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ConcentrationIndexDTO with current HHI, prior HHI, change, and sparkline
     */
    @GET
    @Path("/concentration-index")
    public Response getConcentrationIndex(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/concentration-index: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ConcentrationIndexDTO result = cxoClientService.getConcentrationIndex(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("HHI Concentration - Current: %.2f, Prior: %.2f, Change: %+.2f",
                result.getCurrentHHI(), result.getPriorHHI(), result.getChangePoints());

        return Response.ok(result).build();
    }

    /**
     * Gets Average Revenue Per Client (TTM) KPI data.
     * Measures average revenue generated per active client in the TTM window.
     * Indicates account value and commercial effectiveness.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return AvgRevenuePerClientDTO with current/prior averages, YoY change %, and sparkline
     */
    @GET
    @Path("/avg-revenue-per-client")
    public Response getAvgRevenuePerClient(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/avg-revenue-per-client: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        AvgRevenuePerClientDTO result = cxoClientService.getAvgRevenuePerClient(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Avg Revenue Per Client - Current: %.2f DKK, Prior: %.2f DKK, YoY: %+.2f%%",
                result.getCurrentTTMAvgRevenue(), result.getPriorTTMAvgRevenue(), result.getYoyChangePercent());

        return Response.ok(result).build();
    }

    /**
     * Gets Client Revenue Pareto chart data (Chart A).
     * Returns top 20 clients by TTM revenue with cumulative percentage distribution.
     * Used for horizontal bar chart with Pareto line overlay.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ClientRevenueParetoDTO with top 20 clients and cumulative distribution
     */
    @GET
    @Path("/revenue-pareto")
    public Response getClientRevenuePareto(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/revenue-pareto: fromDate=%s, toDate=%s", fromDate, toDate);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ClientRevenueParetoDTO result = cxoClientService.getClientRevenuePareto(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Revenue Pareto fetched: %d clients", result.getClients().size());

        return Response.ok(result).build();
    }

    /**
     * Gets Client Portfolio Bubble chart data (Chart B).
     * Returns clients positioned by revenue (X) and margin (Y), grouped by sector.
     * Used for bubble chart visualization of portfolio health.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ClientPortfolioBubbleDTO with clients grouped by sector
     */
    @GET
    @Path("/portfolio-bubble")
    public Response getClientPortfolioBubble(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/portfolio-bubble: fromDate=%s, toDate=%s", fromDate, toDate);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ClientPortfolioBubbleDTO result = cxoClientService.getClientPortfolioBubble(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Portfolio Bubble fetched: %d sectors", result.getSectorData().size());

        return Response.ok(result).build();
    }

    /**
     * Gets Client Detail Table data for Table E.
     * Returns all clients with comprehensive TTM metrics including revenue, margin,
     * growth, active projects, service line count, and last invoice date.
     *
     * Used for:
     * - Populating the interactive Client Portfolio Details grid
     * - CSV export functionality
     * - Client-level analysis and filtering
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ClientDetailTableDTO with list of all clients and metrics
     */
    @GET
    @Path("/client-detail-table")
    public Response getClientDetailTable(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/client-detail-table: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ClientDetailTableDTO result = cxoClientService.getClientDetailTable(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Client Detail Table: %d clients returned", result.getTotalCount());

        return Response.ok(result).build();
    }

    /**
     * Parse comma-separated string into Set of trimmed values.
     * Returns null if input is null or blank.
     *
     * @param input Comma-separated string (e.g., "PM,DEV,BA")
     * @return Set of trimmed non-empty values, or null if no valid values
     */
    private Set<String> parseCommaSeparated(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String value : input.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Gets Client Retention & Growth Trend (Chart C).
     * Returns quarterly retention metrics for the past 8 fiscal quarters.
     *
     * Fiscal quarters: Q1=Jul-Sep, Q2=Oct-Dec, Q3=Jan-Mar, Q4=Apr-Jun
     *
     * @param asOfDate Anchor date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ClientRetentionTrendDTO with 8 quarters of retention data
     */
    @GET
    @Path("/retention-trend")
    public Response getClientRetentionTrend(
            @QueryParam("asOfDate") LocalDate asOfDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/retention-trend: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, companyIds=%s",
                asOfDate, sectors, serviceLines, contractTypes, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ClientRetentionTrendDTO result = cxoClientService.getClientRetentionTrend(
                asOfDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Client Retention Trend: %d quarters returned", result.getQuarters().size());

        return Response.ok(result).build();
    }

    /**
     * Gets Service Line Penetration heatmap data (Chart D).
     * Returns revenue matrix showing which service lines are used by top clients.
     *
     * Matrix dimensions: Top 15 clients × All service lines
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional, serviceLines excluded intentionally)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ServiceLinePenetrationDTO with client × service line revenue matrix
     */
    @GET
    @Path("/service-line-penetration")
    public Response getServiceLinePenetration(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/service-line-penetration: fromDate=%s, toDate=%s, sectors=%s, contractTypes=%s, companyIds=%s",
                fromDate, toDate, sectors, contractTypes, companyIds);

        // Parse multi-value filters (serviceLines intentionally excluded per design)
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ServiceLinePenetrationDTO result = cxoClientService.getServiceLinePenetration(
                fromDate,
                toDate,
                sectorSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Service Line Penetration: %d clients × %d service lines",
                result.getClientNames().size(), result.getServiceLines().size());

        return Response.ok(result).build();
    }

    /**
     * Gets Average Engagement Length KPI data.
     * Returns the portfolio-wide average customer relationship duration in months,
     * with YoY comparison and monthly sparkline trend.
     *
     * Definition: Engagement length is calculated from the work table as the duration
     * between MIN(registered) and MAX(registered) for each client.
     *
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return AvgEngagementLengthDTO with average, prior average, change %, and sparkline
     */
    @GET
    @Path("/avg-engagement-length")
    public Response getAvgEngagementLength(
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/avg-engagement-length: toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, companyIds=%s",
                toDate, sectors, serviceLines, contractTypes, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        AvgEngagementLengthDTO result = cxoClientService.getAvgEngagementLength(
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                companyIdSet
        );

        log.debugf("Avg Engagement Length: %.2f months, Prior: %.2f months, Change: %+.2f%%",
                result.getAvgEngagementMonths(), result.getPriorYearAvgMonths(), result.getChangePercent());

        return Response.ok(result).build();
    }

    /**
     * Gets Engagement by Company chart data.
     * Returns top clients by engagement duration with their relationship metrics.
     *
     * Includes first/last engagement dates, total hours, and unique consultants
     * for each client. Sorted by engagement duration descending.
     *
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param limit Maximum number of clients to return (default 20)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return EngagementByCompanyDTO with client list and portfolio average
     */
    @GET
    @Path("/engagement-by-company")
    public Response getEngagementByCompany(
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("limit") Integer limit,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/engagement-by-company: toDate=%s, sectors=%s, limit=%d, companyIds=%s",
                toDate, sectors, limit, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Default limit to 20 if not provided
        int effectiveLimit = (limit != null && limit > 0) ? limit : 20;

        // Call service layer
        EngagementByCompanyDTO result = cxoClientService.getEngagementByCompany(
                toDate,
                sectorSet,
                effectiveLimit,
                companyIdSet
        );

        log.debugf("Engagement by Company: %d clients, portfolio avg %.2f months",
                Integer.valueOf(result.getClients().size()), Double.valueOf(result.getPortfolioAvgMonths()));

        return Response.ok(result).build();
    }

    /**
     * Gets Industry Distribution chart data.
     * Returns client portfolio distribution by industry segment including
     * client counts and revenue amounts per segment.
     *
     * Segments include: PUBLIC, HEALTH, FINANCIAL, ENERGY, EDUCATION, OTHER
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return IndustryDistributionDTO with segments and totals
     */
    @GET
    @Path("/industry-distribution")
    public Response getIndustryDistribution(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /clients/cxo/industry-distribution: fromDate=%s, toDate=%s, companyIds=%s",
                fromDate, toDate, companyIds);

        // Parse multi-value filters
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        IndustryDistributionDTO result = cxoClientService.getIndustryDistribution(
                fromDate,
                toDate,
                companyIdSet
        );

        log.debugf("Industry Distribution: %d segments, %d total clients, %.2f M total revenue",
                Integer.valueOf(result.getSegments().size()), Integer.valueOf(result.getTotalClients()), Double.valueOf(result.getTotalRevenueM()));

        return Response.ok(result).build();
    }
}
