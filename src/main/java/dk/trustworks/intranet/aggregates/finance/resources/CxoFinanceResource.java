package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueYTDDataDTO;
import dk.trustworks.intranet.aggregates.finance.services.CxoFinanceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST API for CxO dashboard finance data.
 * Provides aggregated revenue and margin trends for executive dashboards.
 */
@JBossLog
@Tag(name = "finance")
@Path("/finance/cxo")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class CxoFinanceResource {

    @Inject
    CxoFinanceService cxoFinanceService;

    /**
     * Gets monthly revenue and margin trend data.
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly revenue and margin data points
     */
    @GET
    @Path("/revenue-margin-trend")
    public List<MonthlyRevenueMarginDTO> getRevenueMarginTrend(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/revenue-margin-trend: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        List<MonthlyRevenueMarginDTO> result = cxoFinanceService.getRevenueMarginTrend(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning %d data points", result.size());
        return result;
    }

    /**
     * Gets monthly utilization and capacity trend data for Chart B.
     * Note: Utilization is user-centric, so only practice and company filters apply.
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param practices Comma-separated practice/service line IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly utilization data points
     */
    @GET
    @Path("/utilization-trend")
    public List<MonthlyUtilizationDTO> getUtilizationTrend(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/utilization-trend: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        // Parse multi-value filters
        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        List<MonthlyUtilizationDTO> result = cxoFinanceService.getUtilizationTrend(
                fromDate,
                toDate,
                practiceSet,
                companyIdSet
        );

        log.debugf("Returning %d utilization data points", result.size());
        return result;
    }

    /**
     * Gets Revenue YTD vs Budget data for the CXO Dashboard KPI.
     * Compares fiscal year-to-date actual revenue against budget with prior year comparison.
     *
     * @param asOfDateStr Current date for YTD calculation (ISO-8601 format, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional, applies only to actual revenue)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return Revenue YTD data with actual, budget, attainment %, variance, YoY comparison, and sparkline
     */
    @GET
    @Path("/revenue-ytd-vs-budget")
    public RevenueYTDDataDTO getRevenueYTDvsBudget(
            @QueryParam("asOfDate") @DefaultValue("") String asOfDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/revenue-ytd-vs-budget: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                asOfDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse asOfDate (default to today if not provided)
        LocalDate asOfDate = asOfDateStr != null && !asOfDateStr.trim().isEmpty()
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        RevenueYTDDataDTO result = cxoFinanceService.getRevenueYTDvsBudget(
                asOfDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning Revenue YTD data: Actual=%.2f, Budget=%.2f, Attainment=%.2f%%",
                result.getActualYTD(), result.getBudgetYTD(), result.getAttainmentPercent());

        return result;
    }

    /**
     * Parses comma-separated string into a Set of trimmed values.
     * Returns null if input is null or empty.
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
