package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.ActiveClientsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.AvgRevenuePerClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConcentrationIndexDTO;
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
}
