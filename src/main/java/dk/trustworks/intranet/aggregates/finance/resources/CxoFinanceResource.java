package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.BacklogCoverageDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BillableUtilizationLast4WeeksDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientRetentionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ForecastUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.GrossMarginTTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyCostCenterMixDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyExpenseMixDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyOverheadPerFTEDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyPayrollHeadcountDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyPipelineBacklogDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexBridgeDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexDetailRowDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RepeatBusinessShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.Top5ClientsShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RealizationRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenuePerBillableFTETTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueYTDDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TTMRevenueGrowthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.VoluntaryAttritionDTO;
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
     * Gets TTM Revenue Growth % KPI data.
     * Calculates year-over-year revenue growth comparing current 12-month period vs prior 12-month period.
     *
     * @param fromDateStr Start date (ISO-8601 format, optional - not used for TTM but needed for consistency)
     * @param toDateStr End date (ISO-8601 format, optional - determines anchor date, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return TTMRevenueGrowthDTO with current TTM, prior TTM, growth %, and 12-month sparkline
     */
    @GET
    @Path("/ttm-revenue-growth")
    public TTMRevenueGrowthDTO getTTMRevenueGrowth(
            @QueryParam("fromDate") @DefaultValue("") String fromDateStr,
            @QueryParam("toDate") @DefaultValue("") String toDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/ttm-revenue-growth: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDateStr, toDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse dates (toDate determines anchor, default to today)
        LocalDate fromDate = fromDateStr != null && !fromDateStr.trim().isEmpty()
                ? LocalDate.parse(fromDateStr)
                : null;
        LocalDate toDate = toDateStr != null && !toDateStr.trim().isEmpty()
                ? LocalDate.parse(toDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        TTMRevenueGrowthDTO result = cxoFinanceService.getTTMRevenueGrowth(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning TTM Revenue Growth data: Current=%.2f, Prior=%.2f, Growth=%.2f%%",
                result.getCurrentTTMRevenue(), result.getPriorTTMRevenue(), result.getGrowthPercent());

        return result;
    }

    /**
     * Gets Gross Margin % (TTM) KPI data.
     * Calculates year-over-year gross margin comparing current 12-month period vs prior 12-month period.
     * Gross Margin % = ((Revenue - Direct Delivery Costs) / Revenue) × 100
     *
     * @param fromDateStr Start date (ISO-8601 format, optional - not used for TTM but needed for consistency)
     * @param toDateStr End date (ISO-8601 format, optional - determines anchor date, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return GrossMarginTTMDTO with current/prior revenue, cost, margin %, change, and 12-month sparkline
     */
    @GET
    @Path("/gross-margin-ttm")
    public GrossMarginTTMDTO getGrossMarginTTM(
            @QueryParam("fromDate") @DefaultValue("") String fromDateStr,
            @QueryParam("toDate") @DefaultValue("") String toDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/gross-margin-ttm: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDateStr, toDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse dates (toDate determines anchor, default to today)
        LocalDate fromDate = fromDateStr != null && !fromDateStr.trim().isEmpty()
                ? LocalDate.parse(fromDateStr)
                : null;
        LocalDate toDate = toDateStr != null && !toDateStr.trim().isEmpty()
                ? LocalDate.parse(toDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        GrossMarginTTMDTO result = cxoFinanceService.getGrossMarginTTM(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning Gross Margin TTM data: Current Revenue=%.2f, Current Cost=%.2f, Current Margin=%.2f%%, " +
                        "Prior Margin=%.2f%%, Change=%.2f pp",
                result.getCurrentTTMRevenue(), result.getCurrentTTMCost(), result.getCurrentMarginPercent(),
                result.getPriorMarginPercent(), result.getMarginChangePct());

        return result;
    }

    /**
     * Gets Realization Rate % KPI data (TTM).
     * Measures how much of the expected billable value (at contracted rates) is actually invoiced.
     *
     * Formula: Realization Rate = (Actual Billed Revenue / Expected Revenue at Contract Rate) × 100
     * Expected Revenue = Σ(workduration × rate) from work_full entries
     *
     * A rate below 100% indicates value leakage through discounts, write-offs, or unbilled time.
     *
     * @param fromDateStr Start date (not used for TTM but needed for consistency)
     * @param toDateStr End date (ISO-8601 format, determines anchor for TTM calculation, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return RealizationRateDTO with current/prior realization %, change, and 12-month sparkline
     */
    @GET
    @Path("/realization-rate")
    public RealizationRateDTO getRealizationRate(
            @QueryParam("fromDate") @DefaultValue("") String fromDateStr,
            @QueryParam("toDate") @DefaultValue("") String toDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/realization-rate: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDateStr, toDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse dates (toDate determines anchor, default to today)
        LocalDate fromDate = fromDateStr != null && !fromDateStr.trim().isEmpty()
                ? LocalDate.parse(fromDateStr)
                : null;
        LocalDate toDate = toDateStr != null && !toDateStr.trim().isEmpty()
                ? LocalDate.parse(toDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        RealizationRateDTO result = cxoFinanceService.getRealizationRate(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning Realization Rate data: Current Expected=%.2f, Current Billed=%.2f, " +
                        "Current Rate=%.2f%%, Prior Rate=%.2f%%, Change=%.2f pp",
                result.getCurrentExpectedRevenue(), result.getCurrentBilledRevenue(),
                result.getCurrentRealizationPercent(), result.getPriorRealizationPercent(),
                result.getRealizationChangePct());

        return result;
    }

    /**
     * Gets Backlog Coverage (Months) KPI data.
     * Calculates how many months of revenue are covered by signed backlog.
     * Formula: Coverage (Months) = Total Backlog Revenue / Average Monthly Revenue
     *
     * @param asOfDateStr Current date for backlog calculation (ISO-8601 format, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return BacklogCoverageDTO with current backlog, avg monthly revenue, coverage months, prior coverage, and change %
     */
    @GET
    @Path("/backlog-coverage")
    public BacklogCoverageDTO getBacklogCoverage(
            @QueryParam("asOfDate") @DefaultValue("") String asOfDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/backlog-coverage: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
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
        BacklogCoverageDTO result = cxoFinanceService.getBacklogCoverage(
                asOfDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning Backlog Coverage data: Backlog=%.2f, Avg Monthly Revenue=%.2f, Coverage=%.2f months, " +
                        "Prior Coverage=%.2f months, Change=%.2f%%",
                result.getTotalBacklogRevenue(), result.getAverageMonthlyRevenue(), result.getCoverageMonths(),
                result.getPriorCoverageMonths(), result.getCoverageChangePct());

        return result;
    }

    /**
     * Gets Revenue per Billable FTE (TTM) KPI data.
     * Calculates revenue efficiency by dividing TTM revenue by average billable FTE count.
     * Formula: Revenue per FTE = Total TTM Revenue / Average Billable FTE Count
     *
     * @param fromDateStr Start date (ISO-8601 format, optional - not used for TTM but needed for consistency)
     * @param toDateStr End date (ISO-8601 format, optional - determines anchor date, defaults to today)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return RevenuePerBillableFTETTMDTO with current/prior revenue per FTE, change %, and 12-month sparkline
     */
    @GET
    @Path("/revenue-per-fte")
    public RevenuePerBillableFTETTMDTO getRevenuePerBillableFTETTM(
            @QueryParam("fromDate") @DefaultValue("") String fromDateStr,
            @QueryParam("toDate") @DefaultValue("") String toDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/revenue-per-fte: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDateStr, toDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse dates (toDate determines anchor, default to today)
        LocalDate fromDate = fromDateStr != null && !fromDateStr.trim().isEmpty()
                ? LocalDate.parse(fromDateStr)
                : null;
        LocalDate toDate = toDateStr != null && !toDateStr.trim().isEmpty()
                ? LocalDate.parse(toDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        RevenuePerBillableFTETTMDTO result = cxoFinanceService.getRevenuePerBillableFTETTM(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning Revenue per FTE data: Current Revenue=%.2f, Current FTE=%.2f, Current Rev/FTE=%.2f, " +
                        "Prior Rev/FTE=%.2f, Change=%.2f%%",
                result.getCurrentTTMRevenue(), result.getCurrentAvgBillableFTE(), result.getCurrentRevenuePerFTE(),
                result.getPriorRevenuePerFTE(), result.getRevenuePerFTEChangePct());

        return result;
    }

    /**
     * Gets Billable Utilization (Last 4 Weeks) KPI data.
     * Measures operational efficiency by comparing billable hours to total available hours
     * over a rolling 4-week window.
     *
     * Formula: Utilization % = (Billable Hours / Total Available Hours) × 100
     *
     * Note: This is a user-centric metric, so only practice (service line) and company
     * filters apply. Sector, contract type, and client filters are NOT applicable.
     *
     * @param asOfDateStr Anchor date (ISO-8601 format, defaults to today)
     * @param serviceLines Comma-separated practice IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return BillableUtilizationLast4WeeksDTO with current/prior utilization %, change, and hour totals
     */
    @GET
    @Path("/billable-utilization-4w")
    public BillableUtilizationLast4WeeksDTO getBillableUtilizationLast4Weeks(
            @QueryParam("asOfDate") @DefaultValue("") String asOfDateStr,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/billable-utilization-4w: asOfDate=%s, serviceLines=%s, companyIds=%s",
                asOfDateStr, serviceLines, companyIds);

        // Parse asOfDate (default to today if not provided)
        LocalDate asOfDate = asOfDateStr != null && !asOfDateStr.trim().isEmpty()
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        BillableUtilizationLast4WeeksDTO result = cxoFinanceService.getBillableUtilizationLast4Weeks(
                asOfDate,
                serviceLineSet,
                companyIdSet
        );

        log.debugf("Returning Billable Utilization (4w) data: Current=%.1f%% (%.0f / %.0f hours), " +
                        "Prior=%.1f%% (%.0f / %.0f hours), Change=%+.1fpp",
                result.getCurrentUtilizationPercent(), result.getCurrentBillableHours(), result.getCurrentAvailableHours(),
                result.getPriorUtilizationPercent(), result.getPriorBillableHours(), result.getPriorAvailableHours(),
                result.getUtilizationChangePct());

        return result;
    }

    /**
     * Gets Forecast Utilization (Next 8 Weeks) KPI data.
     * Forward-looking measure of consultant utilization based on scheduled work
     * in the backlog for the next 8 weeks. Indicates pipeline health and capacity planning needs.
     *
     * Formula: Forecast Utilization % = (Forecast Billable Hours / Total Capacity Hours) × 100
     *
     * Note: This is a user-centric metric, so only practice (service line) and company
     * filters apply. Sector, contract type, and client filters are NOT applicable.
     *
     * @param asOfDateStr Anchor date (ISO-8601 format, defaults to today)
     * @param serviceLines Comma-separated practice IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ForecastUtilizationDTO with current/prior utilization %, change, and hour totals
     */
    @GET
    @Path("/forecast-utilization-8w")
    public ForecastUtilizationDTO getForecastUtilization(
            @QueryParam("asOfDate") @DefaultValue("") String asOfDateStr,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/forecast-utilization-8w: asOfDate=%s, serviceLines=%s, companyIds=%s",
                asOfDateStr, serviceLines, companyIds);

        // Parse asOfDate (default to today if not provided)
        LocalDate asOfDate = asOfDateStr != null && !asOfDateStr.trim().isEmpty()
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ForecastUtilizationDTO result = cxoFinanceService.getForecastUtilization(
                asOfDate,
                serviceLineSet,
                companyIdSet
        );

        log.debugf("Returning Forecast Utilization (8w) data: Current=%.1f%% (%.0f / %.0f hours), " +
                        "Prior=%.1f%%, Change=%+.1fpp",
                result.getForecastUtilizationPercent(), result.getTotalForecastBillableHours(), result.getTotalCapacityHours(),
                result.getPriorForecastUtilizationPercent(),
                result.getUtilizationChangePct());

        return result;
    }

    /**
     * Gets Voluntary Attrition (12-Month Rolling) KPI data.
     * Measures the 12-month rolling rate of employees who voluntarily left the organization.
     * Key indicator of employee satisfaction and organizational health.
     *
     * Formula: Voluntary Attrition % = (Voluntary Leavers / Average Headcount) × 100
     *
     * Note: This is a user-centric metric, so only practice (service line) and company
     * filters apply. Sector, contract type, and client filters are NOT applicable.
     *
     * Known limitation: Currently all leavers are counted as "voluntary" since
     * the userstatus table lacks a termination_reason field.
     *
     * @param asOfDateStr Anchor date (ISO-8601 format, defaults to today)
     * @param serviceLines Comma-separated practice IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return VoluntaryAttritionDTO with current/prior attrition %, change, leaver counts, and sparkline
     */
    @GET
    @Path("/voluntary-attrition-12m")
    public VoluntaryAttritionDTO getVoluntaryAttrition(
            @QueryParam("asOfDate") @DefaultValue("") String asOfDateStr,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/voluntary-attrition-12m: asOfDate=%s, serviceLines=%s, companyIds=%s",
                asOfDateStr, serviceLines, companyIds);

        // Parse asOfDate (default to today if not provided)
        LocalDate asOfDate = asOfDateStr != null && !asOfDateStr.trim().isEmpty()
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        // Parse multi-value filters
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        VoluntaryAttritionDTO result = cxoFinanceService.getVoluntaryAttrition(
                asOfDate,
                serviceLineSet,
                companyIdSet
        );

        log.debugf("Returning Voluntary Attrition (12m) data: Current=%.1f%% (%d leavers / %.1f avg headcount), " +
                        "Prior=%.1f%%, Change=%+.1fpp",
                result.getCurrentAttritionPercent(), result.getTotalVoluntaryLeavers12m(), result.getAverageHeadcount12m(),
                result.getPriorAttritionPercent(),
                result.getAttritionChangePct());

        return result;
    }

    /**
     * Gets monthly pipeline, backlog, and target trend data for Chart C.
     * Forward-looking chart showing coverage horizon (typically 6 months).
     *
     * @param fromDate Start date (ISO-8601 format, typically current month)
     * @param toDate End date (ISO-8601 format, typically current month + 6)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional - target series hidden when set)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly pipeline, backlog, and target data with coverage metrics
     */
    @GET
    @Path("/pipeline-backlog-trend")
    public List<MonthlyPipelineBacklogDTO> getPipelineBacklogTrend(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/pipeline-backlog-trend: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        List<MonthlyPipelineBacklogDTO> result = cxoFinanceService.getPipelineBacklogTrend(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Returning %d pipeline/backlog data points", result.size());
        return result;
    }

    /**
     * Gets Client Retention Rate data for 12-month periods.
     * Measures percentage of clients with revenue in current 12-month window
     * who also had revenue in previous 12-month window.
     *
     * @param fromDate Start date (ISO-8601 format, optional - used for dashboard context)
     * @param toDate End date (ISO-8601 format, determines current 12m window anchor)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional - if provided, returns empty DTO as this is portfolio-level)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return ClientRetentionDTO with retention percentages and client counts
     */
    @GET
    @Path("/client-retention")
    public ClientRetentionDTO getClientRetention(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/client-retention: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer
        ClientRetentionDTO result = cxoFinanceService.getClientRetention(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Client retention result: current=%d clients, retained=%d (%.2f%%), trend=%.2f pp",
                result.getCurrentWindowClientCount(),
                result.getRetainedClientCount(),
                result.getCurrentRetentionPercent(),
                result.getRetentionChangePct());

        return result;
    }

    /**
     * Gets Repeat Business Share KPI data.
     * Measures the percentage of revenue from clients with ≥2 projects in the last 24 months,
     * indicating business diversification and client stickiness.
     *
     * Formula: Repeat Business Share % = (Repeat Client Revenue / Total Revenue) × 100
     * Repeat Client Definition: Client with ≥2 distinct projects in the 24-month window
     *
     * Note: This is a portfolio-level metric requiring multiple clients. If clientId is provided,
     * returns empty DTO. Uses FIXED 24-month rolling window (not dashboard-driven).
     *
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional - if provided, returns empty DTO as this is portfolio-level)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return RepeatBusinessShareDTO with current/prior percentages, revenues, and 12-month sparkline
     */
    @GET
    @Path("/repeat-business-share")
    public RepeatBusinessShareDTO getRepeatBusinessShare(
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/repeat-business-share: sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer (uses fixed 24-month rolling window from today)
        RepeatBusinessShareDTO result = cxoFinanceService.getRepeatBusinessShare(
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Repeat business share result: total=%.2f DKK, repeat=%.2f DKK (%.2f%%), trend=%.2f pp, sparkline length=%d",
                result.getTotalRevenue(),
                result.getRepeatClientRevenue(),
                result.getCurrentRepeatSharePercent(),
                result.getRepeatShareChangePct(),
                result.getSparklineData() != null ? result.getSparklineData().length : 0);

        return result;
    }

    /**
     * Gets Top 5 Clients' Revenue Share KPI data.
     * Measures revenue concentration risk by calculating what percentage of TTM revenue
     * comes from the 5 largest clients. Lower concentration indicates better diversification.
     *
     * Formula: Top 5 Share % = (Top 5 Clients Revenue / Total Revenue) × 100
     * Top 5 Definition: The 5 clients with the highest revenue in the TTM window
     *
     * Status Logic: INVERTED - Lower concentration is better (lower risk, better diversification)
     *
     * Note: This is a portfolio-level metric requiring multiple clients. If clientId is provided,
     * returns empty DTO. Uses dashboard-driven TTM window (fromDate/toDate parameters).
     *
     * @param fromDate Start date (ISO-8601 format, required)
     * @param toDate End date (ISO-8601 format, required)
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional - if provided, returns empty DTO as this is portfolio-level)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return Top5ClientsShareDTO with current/prior percentages, revenues, and change (percentage points)
     */
    @GET
    @Path("/top-5-clients-share")
    public Top5ClientsShareDTO getTop5ClientsShare(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/top-5-clients-share: fromDate=%s, toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fromDate, toDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer (dashboard-driven TTM window)
        Top5ClientsShareDTO result = cxoFinanceService.getTop5ClientsShare(
                fromDate,
                toDate,
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Top 5 clients share result: total=%.2f DKK, top5=%.2f DKK (%.2f%%), trend=%+.2f pp",
                result.getTotalRevenue(),
                result.getTop5ClientsRevenue(),
                result.getCurrentTop5SharePercent(),
                result.getTop5ShareChangePct());

        return result;
    }

    /**
     * Gets OPEX Bridge (Waterfall) data comparing prior vs current fiscal year.
     * Shows category-level changes driving YoY OPEX variance.
     *
     * @param asOfDateStr Anchor date (defaults to today) - determines current fiscal year
     * @param costCenters Comma-separated cost center IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return OpexBridgeDTO with prior FY, category changes, current FY totals
     */
    @GET
    @Path("/opex-bridge")
    public OpexBridgeDTO getOpexBridge(
            @QueryParam("asOfDate") @DefaultValue("") String asOfDateStr,
            @QueryParam("costCenters") String costCenters,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/opex-bridge: asOfDate=%s, costCenters=%s, companyIds=%s",
                asOfDateStr, costCenters, companyIds);

        LocalDate asOfDate = (asOfDateStr != null && !asOfDateStr.trim().isEmpty())
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        Set<String> costCenterSet = parseCommaSeparated(costCenters);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        OpexBridgeDTO result = cxoFinanceService.getOpexBridge(asOfDate, costCenterSet, companyIdSet);

        log.debugf("OPEX Bridge result: priorFY=%.2f DKK, currentFY=%.2f DKK, yoyChange=%+.2f%%",
                result.getPriorFYOpex(), result.getCurrentFYOpex(), result.getYoyChangePercent());

        return result;
    }

    /**
     * Gets monthly expense mix data by category (100% stacked column chart).
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param costCenters Comma-separated cost center IDs (optional)
     * @param expenseCategories Comma-separated expense category IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly expense mix data points
     */
    @GET
    @Path("/expense-mix-by-category")
    public List<MonthlyExpenseMixDTO> getExpenseMixByCategory(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("costCenters") String costCenters,
            @QueryParam("expenseCategories") String expenseCategories,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/expense-mix-by-category: fromDate=%s, toDate=%s, costCenters=%s, expenseCategories=%s, companyIds=%s",
                fromDate, toDate, costCenters, expenseCategories, companyIds);

        Set<String> costCenterSet = parseCommaSeparated(costCenters);
        Set<String> expenseCategorySet = parseCommaSeparated(expenseCategories);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyExpenseMixDTO> result = cxoFinanceService.getExpenseMixByCategory(
                fromDate, toDate, costCenterSet, expenseCategorySet, companyIdSet);

        log.debugf("Returning %d monthly expense mix data points", result.size());
        return result;
    }

    /**
     * Gets monthly expense mix data by cost center (100% stacked column chart).
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param costCenters Comma-separated cost center IDs (optional)
     * @param expenseCategories Comma-separated expense category IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly cost center mix data points
     */
    @GET
    @Path("/expense-mix-by-cost-center")
    public List<MonthlyCostCenterMixDTO> getExpenseMixByCostCenter(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("costCenters") String costCenters,
            @QueryParam("expenseCategories") String expenseCategories,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/expense-mix-by-cost-center: fromDate=%s, toDate=%s, costCenters=%s, expenseCategories=%s, companyIds=%s",
                fromDate, toDate, costCenters, expenseCategories, companyIds);

        Set<String> costCenterSet = parseCommaSeparated(costCenters);
        Set<String> expenseCategorySet = parseCommaSeparated(expenseCategories);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyCostCenterMixDTO> result = cxoFinanceService.getExpenseMixByCostCenter(
                fromDate, toDate, costCenterSet, expenseCategorySet, companyIdSet);

        log.debugf("Returning %d monthly cost center mix data points", result.size());
        return result;
    }

    /**
     * Gets monthly payroll and headcount structure data (combo chart).
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param practices Comma-separated practice IDs (for FTE filtering, optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly payroll/headcount data points
     */
    @GET
    @Path("/payroll-headcount-structure")
    public List<MonthlyPayrollHeadcountDTO> getPayrollHeadcountStructure(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/payroll-headcount-structure: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                fromDate, toDate, practices, companyIds);

        Set<String> practiceSet = parseCommaSeparated(practices);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyPayrollHeadcountDTO> result = cxoFinanceService.getPayrollHeadcountStructure(
                fromDate, toDate, practiceSet, companyIdSet);

        log.debugf("Returning %d monthly payroll/headcount data points", result.size());
        return result;
    }

    /**
     * Gets monthly overhead per FTE trend (TTM calculations, dual-line chart).
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly overhead per FTE data points (TTM rolling)
     */
    @GET
    @Path("/overhead-per-fte")
    public List<MonthlyOverheadPerFTEDTO> getOverheadPerFTE(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/overhead-per-fte: fromDate=%s, toDate=%s, companyIds=%s",
                fromDate, toDate, companyIds);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyOverheadPerFTEDTO> result = cxoFinanceService.getOverheadPerFTE(
                fromDate, toDate, companyIdSet);

        log.debugf("Returning %d monthly overhead per FTE data points", result.size());
        return result;
    }

    /**
     * Gets detailed expense drill-down data (account-level granularity).
     *
     * @param fromDate Start date (ISO-8601 format, optional)
     * @param toDate End date (ISO-8601 format, optional)
     * @param costCenters Comma-separated cost center IDs (optional)
     * @param expenseCategories Comma-separated expense category IDs (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of detailed expense rows
     */
    @GET
    @Path("/expense-detail")
    public List<OpexDetailRowDTO> getExpenseDetail(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("costCenters") String costCenters,
            @QueryParam("expenseCategories") String expenseCategories,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/expense-detail: fromDate=%s, toDate=%s, costCenters=%s, expenseCategories=%s, companyIds=%s",
                fromDate, toDate, costCenters, expenseCategories, companyIds);

        Set<String> costCenterSet = parseCommaSeparated(costCenters);
        Set<String> expenseCategorySet = parseCommaSeparated(expenseCategories);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<OpexDetailRowDTO> result = cxoFinanceService.getExpenseDetail(
                fromDate, toDate, costCenterSet, expenseCategorySet, companyIdSet);

        log.debugf("Returning %d expense detail rows", result.size());
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
