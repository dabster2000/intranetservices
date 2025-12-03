package dk.trustworks.intranet.aggregates.delivery.resources;

import dk.trustworks.intranet.aggregates.delivery.dto.AvgProjectMarginDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.BenchCountDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.ForecastUtilizationDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.OverloadCountDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.RealizationRateDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.UtilizationTTMDTO;
import dk.trustworks.intranet.aggregates.delivery.services.CxoDeliveryService;
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
 * REST API for CxO dashboard delivery metrics.
 * Provides utilization and delivery-level KPIs for executive dashboards.
 */
@JBossLog
@Tag(name = "delivery")
@Path("/delivery/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class CxoDeliveryResource {

    @Inject
    CxoDeliveryService cxoDeliveryService;

    /**
     * Gets Company Billable Utilization (TTM) KPI data.
     * Returns company-wide utilization percentage over a trailing 12-month window,
     * along with year-over-year comparison and monthly sparkline trend.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param practices Comma-separated practice IDs (optional, e.g., "PM,DEV,BA")
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return UtilizationTTMDTO with current/prior percentages, YoY change points, and sparkline
     */
    @GET
    @Path("/utilization-ttm")
    public Response getUtilizationTTM(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /delivery/cxo/utilization-ttm: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        // Parse multi-value filters
        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        UtilizationTTMDTO result = cxoDeliveryService.getUtilizationTTM(
                fromDate,
                toDate,
                practiceSet,
                companyIdSet
        );

        log.debugf("Utilization TTM - Current: %.2f%%, Prior: %.2f%%, YoY: %+.2f points",
                result.getCurrentTTMPercent(), result.getPriorTTMPercent(), result.getYoyChangePoints());

        return Response.ok(result).build();
    }

    /**
     * Gets Forecast Utilization (Next 8 Weeks) KPI data.
     * Returns forecasted utilization percentage for the next 8-week period,
     * along with prior period comparison and weekly sparkline trend.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to next Monday)
     * @param toDate End date (ISO-8601 format, optional, defaults to 8 weeks ahead)
     * @param practices Comma-separated practice IDs (optional, e.g., "PM,DEV,BA")
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ForecastUtilizationDTO with current/prior percentages, change points, and sparkline
     */
    @GET
    @Path("/forecast-utilization")
    public Response getForecastUtilization(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /delivery/cxo/forecast-utilization: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        // Parse multi-value filters
        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ForecastUtilizationDTO result = cxoDeliveryService.getForecastUtilization(
                fromDate,
                toDate,
                practiceSet,
                companyIdSet
        );

        log.debugf("Forecast Utilization - Current: %.2f%%, Prior: %.2f%%, Change: %+.2f points",
                result.getCurrentForecastPercent(), result.getPriorForecastPercent(), result.getChangePoints());

        return Response.ok(result).build();
    }

    /**
     * Gets Bench FTE Count (< 50% Utilization) KPI data.
     * Returns count of consultants currently on the bench with low utilization,
     * along with prior period comparison.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 1 month ago)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param practices Comma-separated practice IDs (optional, e.g., "PM,DEV,BA")
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return BenchCountDTO with current/prior counts and absolute change
     */
    @GET
    @Path("/bench-count")
    public Response getBenchCount(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /delivery/cxo/bench-count: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        // Parse multi-value filters
        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        BenchCountDTO result = cxoDeliveryService.getBenchCount(
                fromDate,
                toDate,
                practiceSet,
                companyIdSet
        );

        log.debugf("Bench Count - Current: %d, Prior: %d, Change: %+d",
                result.getCurrentBenchCount(), result.getPriorBenchCount(), result.getChange());

        return Response.ok(result).build();
    }

    /**
     * Gets Overload Count (> 95% Utilization) KPI data.
     * Returns count of consultants currently overloaded with high utilization,
     * along with prior period comparison.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 1 month ago)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param practices Comma-separated practice IDs (optional, e.g., "PM,DEV,BA")
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return OverloadCountDTO with current/prior counts and absolute change
     */
    @GET
    @Path("/overload-count")
    public Response getOverloadCount(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /delivery/cxo/overload-count: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        // Parse multi-value filters
        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        OverloadCountDTO result = cxoDeliveryService.getOverloadCount(
                fromDate,
                toDate,
                practiceSet,
                companyIdSet
        );

        log.debugf("Overload Count - Current: %d, Prior: %d, Change: %+d",
                result.getCurrentOverloadCount(), result.getPriorOverloadCount(), result.getChange());

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
     * Gets Realization Rate (TTM) KPI data.
     * Returns realization rate percentage over a trailing 12-month window,
     * along with year-over-year comparison and monthly sparkline trend.
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param practices Comma-separated practice IDs (optional, e.g., "PM,DEV,BA")
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return RealizationRateDTO with current/prior percentages, YoY change points, and sparkline
     */
    @GET
    @Path("/realization-rate")
    public Response getRealizationRate(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /delivery/cxo/realization-rate: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        // Parse multi-value filters
        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        RealizationRateDTO result = cxoDeliveryService.getRealizationRate(
                fromDate,
                toDate,
                practiceSet,
                companyIdSet
        );

        log.debugf("Realization Rate TTM - Current: %.2f%%, Prior: %.2f%%, YoY: %+.2f points",
                result.getCurrentTTMPercent(), result.getPriorTTMPercent(), result.getYoyChangePoints());

        return Response.ok(result).build();
    }

    /**
     * Gets Average Project Margin (TTM) KPI data.
     * Returns average project margin percentage over a trailing 12-month window,
     * along with year-over-year comparison and monthly sparkline trend.
     *
     * IMPORTANT: Supports ALL 5 filters (project-centric):
     * - sectors, serviceLines, contractTypes, clientId, companyIds
     *
     * @param fromDate Start date (ISO-8601 format, optional, defaults to 12 months before toDate)
     * @param toDate End date (ISO-8601 format, optional, defaults to today)
     * @param sectors Comma-separated sector IDs (optional, e.g., "PUBLIC,HEALTH")
     * @param serviceLines Comma-separated service line IDs (optional, e.g., "PM,DEV")
     * @param contractTypes Comma-separated contract type IDs (optional, e.g., "T&M,FIXED")
     * @param clientId Single client UUID (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return AvgProjectMarginDTO with current/prior percentages, YoY change points, and sparkline
     */
    @GET
    @Path("/avg-project-margin")
    public Response getAvgProjectMargin(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /delivery/cxo/avg-project-margin: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        AvgProjectMarginDTO result = cxoDeliveryService.getAvgProjectMargin(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Avg Project Margin TTM - Current: %.2f%%, Prior: %.2f%%, YoY: %+.2f points",
                result.getCurrentTTMPercent(), result.getPriorTTMPercent(), result.getYoyChangePoints());

        return Response.ok(result).build();
    }
}
