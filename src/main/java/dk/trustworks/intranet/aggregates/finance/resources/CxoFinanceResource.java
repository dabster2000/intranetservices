package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.BacklogCoverageDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BillableUtilizationLast4WeeksDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BudgetActualGapDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BudgetActualGapMonthlyDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BudgetHoursByMonthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.FutureNetAvailableDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyAbsenceWaterfallDTO;
import dk.trustworks.intranet.aggregates.finance.dto.UtilizationConsultantDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientRetentionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConsultantUtilizationRankingDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConsultantWithoutContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TimeToFirstContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.UnprofitableConsultantDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ForecastUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.GrossMarginTTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyAccumulatedEbitdaDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyAccumulatedRevenueDTO;
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
import dk.trustworks.intranet.aggregates.finance.dto.EbitdaSourceDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueSourceDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.SalaryGLAnomalyDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelBonusDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelConsultantsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.CareerLevelEconomicsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.VoluntaryAttritionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.CostToRevenueDataPointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.GrossMarginTrendDataPointDTO;
import dk.trustworks.intranet.aggregates.finance.dto.cxo.RevenuePracticeDTO;
import dk.trustworks.intranet.aggregates.finance.model.CareerLevelBonus;
import dk.trustworks.intranet.aggregates.finance.health.SalaryGLAnomalyCheck;
import dk.trustworks.intranet.aggregates.finance.services.ConsultantInsightsService;
import dk.trustworks.intranet.aggregates.finance.services.CxoFinanceService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.aggregates.finance.usecases.CareerLevelEconomicsUseCase;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import dk.trustworks.intranet.financeservice.model.enums.RevenueBasis;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
@RolesAllowed({"dashboard:read"})
public class CxoFinanceResource {

    @Inject
    CxoFinanceService cxoFinanceService;

    @Inject
    ConsultantInsightsService consultantInsightsService;

    @Inject
    CareerLevelEconomicsUseCase careerLevelEconomicsUseCase;

    @Inject
    SalaryGLAnomalyCheck salaryGLAnomalyCheck;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    dk.trustworks.intranet.services.PracticeService practiceService;

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
        Set<String> practiceSet = practiceService.normalizePracticeFilter(parseCommaSeparated(practices));
        // practices= accepts registry storage codes or uuids (§4.5); values are
        // validated against the registry and normalized to storage codes.
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
            @QueryParam("asOfDate") String asOfDateStr,
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
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
            @QueryParam("asOfDate") String asOfDateStr,
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
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
            @QueryParam("asOfDate") String asOfDateStr,
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
            @QueryParam("asOfDate") String asOfDateStr,
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
            @QueryParam("asOfDate") String asOfDateStr,
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
     * Gets Repeat Business Share KPI data by counting distinct ACTIVE CONSULTANTs per client.
     * Measures portfolio stickiness using consultant-based definition of repeat clients.
     *
     * Fixed 24-month rolling window (not dashboard-driven).
     * Repeat Client = Client with ≥2 distinct active consultants in window.
     * Formula: Repeat Business Share % = (Repeat Client Revenue / Total Revenue) × 100
     *
     * Expected result: Higher % than project-based metric (~44 repeat clients vs ~24).
     * Many clients have single projects but multiple consultants working together.
     *
     * @param sectors Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId Client UUID filter (optional - if provided, returns empty DTO as this is portfolio-level)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return RepeatBusinessShareDTO with current/prior percentages, revenues, and 12-month sparkline
     */
    @GET
    @Path("/repeat-business-share/by-consultants")
    public RepeatBusinessShareDTO getRepeatBusinessShareByConsultants(
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/repeat-business-share/by-consultants: sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                sectors, serviceLines, contractTypes, clientId, companyIds);

        // Parse multi-value filters
        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        // Call service layer (uses fixed 24-month rolling window from today)
        RepeatBusinessShareDTO result = cxoFinanceService.getRepeatBusinessShareByConsultants(
                sectorSet,
                serviceLineSet,
                contractTypeSet,
                clientId,
                companyIdSet
        );

        log.debugf("Repeat business share by consultants result: total=%.2f DKK, repeat=%.2f DKK (%.2f%%), trend=%.2f pp, sparkline length=%d",
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
            @QueryParam("asOfDate") String asOfDateStr,
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
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam) {

        log.debugf("GET /finance/cxo/expense-mix-by-category: fromDate=%s, toDate=%s, costCenters=%s, expenseCategories=%s, companyIds=%s, costSource=%s",
                fromDate, toDate, costCenters, expenseCategories, companyIds, costSourceParam);

        Set<String> costCenterSet = parseCommaSeparated(costCenters);
        Set<String> expenseCategorySet = parseCommaSeparated(expenseCategories);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyExpenseMixDTO> result = cxoFinanceService.getExpenseMixByCategory(
                fromDate, toDate, costCenterSet, expenseCategorySet, companyIdSet,
                CostSource.fromQueryParam(costSourceParam));

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
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam) {

        log.debugf("GET /finance/cxo/expense-mix-by-cost-center: fromDate=%s, toDate=%s, costCenters=%s, expenseCategories=%s, companyIds=%s, costSource=%s",
                fromDate, toDate, costCenters, expenseCategories, companyIds, costSourceParam);

        Set<String> costCenterSet = parseCommaSeparated(costCenters);
        Set<String> expenseCategorySet = parseCommaSeparated(expenseCategories);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyCostCenterMixDTO> result = cxoFinanceService.getExpenseMixByCostCenter(
                fromDate, toDate, costCenterSet, expenseCategorySet, companyIdSet,
                CostSource.fromQueryParam(costSourceParam));

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

        Set<String> practiceSet = practiceService.normalizePracticeFilter(parseCommaSeparated(practices));
        // practices= accepts registry storage codes or uuids (§4.5); values are
        // validated against the registry and normalized to storage codes.
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
     * Returns 12 monthly data points (Jul–Jun) showing accumulated revenue for the current fiscal year.
     *
     * @param asOfDateStr Reference date for fiscal year detection (ISO-8601, defaults to today)
     * @param sectors     Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId    Client UUID filter (optional)
     * @param companyIds  Comma-separated company UUIDs (optional)
     * @return 12 data points sorted by fiscal month (1=Jul, 12=Jun), each with monthly + accumulated revenue
     */
    @GET
    @Path("/accumulated-revenue")
    public List<MonthlyAccumulatedRevenueDTO> getAccumulatedRevenue(
            @QueryParam("asOfDate") String asOfDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/accumulated-revenue: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                asOfDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        LocalDate asOfDate = (asOfDateStr != null && !asOfDateStr.trim().isEmpty())
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyAccumulatedRevenueDTO> result = cxoFinanceService.getAccumulatedRevenue(
                asOfDate, sectorSet, serviceLineSet, contractTypeSet, clientId, companyIdSet);

        log.debugf("Returning %d accumulated revenue data points", result.size());
        return result;
    }

    /**
     * Returns raw invoice and invoice-item source data behind the accumulated revenue chart.
     * Intended for Excel export — contains individual invoices, line items, and credit notes.
     *
     * @param asOfDateStr  Reference date for fiscal year detection (ISO-8601, defaults to today)
     * @param sectors      Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId     Client UUID filter (optional)
     * @param companyIds   Comma-separated company UUIDs (optional)
     * @return Container with invoices, line items, and credit notes for the fiscal year
     */
    @GET
    @Path("/accumulated-revenue/source-data")
    public RevenueSourceDataDTO getAccumulatedRevenueSourceData(
            @QueryParam("asOfDate") String asOfDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/accumulated-revenue/source-data: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                asOfDateStr, sectors, serviceLines, contractTypes, clientId, companyIds);

        LocalDate asOfDate = (asOfDateStr != null && !asOfDateStr.trim().isEmpty())
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        Set<String> sectorSet       = parseCommaSeparated(sectors);
        Set<String> serviceLineSet  = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet    = parseCommaSeparated(companyIds);

        RevenueSourceDataDTO result = cxoFinanceService.getAccumulatedRevenueSourceData(
                asOfDate, sectorSet, serviceLineSet, contractTypeSet, clientId, companyIdSet);

        log.debugf("Returning source data: %d invoices, %d items, %d credit notes",
                result.getInvoices().size(), result.getInvoiceItems().size(), result.getCreditNotes().size());
        return result;
    }

    /**
     * Returns 12 monthly data points (Jul–Jun) showing expected accumulated EBITDA for the current fiscal year.
     * Past months use actuals; future months use backlog revenue + TTM gross margin estimation.
     *
     * @param asOfDateStr Reference date for fiscal year detection (ISO-8601, defaults to today)
     * @param sectors     Comma-separated sector IDs (optional)
     * @param serviceLines Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId    Client UUID filter (optional)
     * @param companyIds  Comma-separated company UUIDs (optional)
     * @return 12 data points sorted by fiscal month (1=Jul, 12=Jun), each with monthly + accumulated EBITDA
     */
    @GET
    @Path("/expected-accumulated-ebitda")
    public List<MonthlyAccumulatedEbitdaDTO> getExpectedAccumulatedEBITDA(
            @QueryParam("asOfDate") String asOfDateStr,
            @QueryParam("fiscalYear") Integer fiscalYear,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam,
            @QueryParam("basis") String basisParam) {

        log.debugf("GET /finance/cxo/expected-accumulated-ebitda: asOfDate=%s, fiscalYear=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s, costSource=%s, basis=%s",
                asOfDateStr, fiscalYear, sectors, serviceLines, contractTypes, clientId, companyIds, costSourceParam, basisParam);

        LocalDate asOfDate = (asOfDateStr != null && !asOfDateStr.trim().isEmpty())
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        Set<String> sectorSet = parseCommaSeparated(sectors);
        Set<String> serviceLineSet = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        List<MonthlyAccumulatedEbitdaDTO> result = cxoFinanceService.getExpectedAccumulatedEBITDA(
                asOfDate, fiscalYear, sectorSet, serviceLineSet, contractTypeSet, clientId, companyIdSet,
                CostSource.fromQueryParam(costSourceParam), RevenueBasis.fromQueryParam(basisParam));

        log.debugf("Returning %d expected accumulated EBITDA data points", result.size());
        return result;
    }

    /**
     * Returns raw source data records behind the Expected Accumulated EBITDA chart for Excel export.
     * Contains five lists: invoices, creditNotes, directCosts, internalInvoices, and opexEntries,
     * each representing a separate Excel tab in the downloaded workbook.
     *
     * <p>Filter scoping:
     * <ul>
     *   <li>companyIds — applied to ALL data sources.</li>
     *   <li>sectors / serviceLines / contractTypes / clientId — applied to invoices, creditNotes,
     *       and directCosts only; NOT applied to internalInvoices or opexEntries.</li>
     * </ul>
     *
     * @param asOfDateStr   Reference date for fiscal year detection (ISO-8601, defaults to today)
     * @param sectors       Comma-separated sector IDs (optional)
     * @param serviceLines  Comma-separated service line IDs (optional)
     * @param contractTypes Comma-separated contract type IDs (optional)
     * @param clientId      Client UUID filter (optional)
     * @param companyIds    Comma-separated company UUIDs (optional)
     * @return EbitdaSourceDataDTO with five populated lists for the fiscal year
     */
    @GET
    @Path("/expected-accumulated-ebitda/source-data")
    public EbitdaSourceDataDTO getEbitdaSourceData(
            @QueryParam("asOfDate") String asOfDateStr,
            @QueryParam("sectors") String sectors,
            @QueryParam("serviceLines") String serviceLines,
            @QueryParam("contractTypes") String contractTypes,
            @QueryParam("clientId") String clientId,
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam) {

        log.debugf("GET /finance/cxo/expected-accumulated-ebitda/source-data: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s, costSource=%s",
                asOfDateStr, sectors, serviceLines, contractTypes, clientId, companyIds, costSourceParam);

        LocalDate asOfDate = (asOfDateStr != null && !asOfDateStr.trim().isEmpty())
                ? LocalDate.parse(asOfDateStr)
                : LocalDate.now();

        Set<String> sectorSet       = parseCommaSeparated(sectors);
        Set<String> serviceLineSet  = parseCommaSeparated(serviceLines);
        Set<String> contractTypeSet = parseCommaSeparated(contractTypes);
        Set<String> companyIdSet    = parseCommaSeparated(companyIds);

        EbitdaSourceDataDTO result = cxoFinanceService.getEbitdaSourceData(
                asOfDate, sectorSet, serviceLineSet, contractTypeSet, clientId, companyIdSet,
                CostSource.fromQueryParam(costSourceParam));

        log.debugf("Returning EBITDA source data: %d invoices, %d creditNotes, %d directCosts, %d internalInvoices, %d opexEntries",
                result.getInvoices().size(), result.getCreditNotes().size(),
                result.getDirectCosts().size(), result.getInternalInvoices().size(),
                result.getOpexEntries().size());
        return result;
    }

    /**
     * Returns cost economics and rate adequacy metrics for each career level.
     *
     * <p>Data is sourced from {@code fact_minimum_viable_rate}. Each item in the response
     * contains the full cost breakdown (salary, pension, statutory, benefits, overhead
     * allocations), actual utilization, average billing rate, and rate adequacy metrics
     * (break-even rate target, minimum viable rates at 15%/20% margin, and rate buffer).</p>
     *
     * <p>{@code statutoryCosts} = ATP + AM-bidrag, summed in Java from the view columns
     * {@code atp_per_person_dkk} and {@code am_bidrag_per_person_dkk}.</p>
     *
     * @param companyIds Comma-separated company UUIDs (optional; omit for all companies)
     * @return CareerLevelEconomicsDTO with one item per career level, ordered by career_level
     */
    @GET
    @Path("/career-level-economics")
    public CareerLevelEconomicsDTO getCareerLevelEconomics(
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/career-level-economics: companyIds=%s", companyIds);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        CareerLevelEconomicsDTO result = careerLevelEconomicsUseCase.getCareerLevelEconomics(companyIdSet);

        log.debugf("Returning career-level economics for %d career levels", result.getCareerLevels().size());
        return result;
    }

    /**
     * Returns individual consultants at a specific career level.
     *
     * <p>Drill-down endpoint for the Career Level Cost Structure cards.
     * Uses the same join logic as fact_minimum_viable_rate (V178).</p>
     *
     * @param careerLevel the career level key (e.g., "SENIOR", "JUNIOR") — required
     * @param companyIds  optional comma-separated company UUIDs to filter by
     * @return CareerLevelConsultantsDTO with career level metadata and individual consultant rows
     */
    @GET
    @Path("/career-level-consultants")
    public Response getCareerLevelConsultants(
            @QueryParam("careerLevel") String careerLevel,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/career-level-consultants: careerLevel=%s, companyIds=%s", careerLevel, companyIds);

        if (careerLevel == null || careerLevel.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "careerLevel query parameter is required"))
                    .build();
        }

        Set<String> companyIdSet = parseCommaSeparated(companyIds);
        CareerLevelConsultantsDTO result = careerLevelEconomicsUseCase.getConsultantsByCareerLevel(careerLevel, companyIdSet);

        log.debugf("Returning %d consultants for career level %s", result.getConsultants().size(), careerLevel);
        return Response.ok(result).build();
    }

    // ========================================================================
    // Career Level Bonus Endpoints
    // ========================================================================

    /**
     * Returns all career level bonus percentages.
     *
     * @return List of CareerLevelBonusDTO with careerLevel and bonusPct
     */
    @GET
    @Path("/career-level-bonuses")
    public List<CareerLevelBonusDTO> getCareerLevelBonuses() {
        log.debug("GET /finance/cxo/career-level-bonuses");

        return CareerLevelBonus.<CareerLevelBonus>listAll().stream()
                .map(b -> new CareerLevelBonusDTO(b.careerLevel, b.bonusPct.doubleValue()))
                .collect(Collectors.toList());
    }

    /**
     * Updates the bonus percentage for a specific career level.
     * Validates that bonusPct is between 0 and 100 inclusive.
     *
     * @param careerLevel the career level key (path param)
     * @param dto         the DTO containing the new bonusPct
     * @return updated CareerLevelBonusDTO
     */
    @PUT
    @Path("/career-level-bonuses/{careerLevel}")
    @RolesAllowed({"dashboard:write"})
    @Transactional
    public Response updateCareerLevelBonus(
            @PathParam("careerLevel") String careerLevel,
            CareerLevelBonusDTO dto) {

        log.debugf("PUT /finance/cxo/career-level-bonuses/%s: bonusPct=%s", careerLevel, dto.getBonusPct());

        if (dto.getBonusPct() < 0 || dto.getBonusPct() > 100) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "bonusPct must be between 0 and 100"))
                    .build();
        }

        CareerLevelBonus bonus = CareerLevelBonus.findById(careerLevel);
        if (bonus == null) {
            // Auto-create for unknown career levels
            bonus = new CareerLevelBonus();
            bonus.careerLevel = careerLevel;
        }

        bonus.bonusPct = BigDecimal.valueOf(dto.getBonusPct());
        bonus.updatedAt = LocalDateTime.now();
        bonus.updatedBy = requestHeaderHolder.getUserUuid();
        bonus.persist();

        return Response.ok(new CareerLevelBonusDTO(bonus.careerLevel, bonus.bonusPct.doubleValue())).build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Consultant Insights tab endpoints
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns consultants ranked by highest net utilization over trailing 12 months.
     * Only includes consultants with > 100 net available hours.
     *
     * <p>Scoped to the five core practices — see {@code ConsultantInsightsService} for why
     * that population is fixed rather than caller-selectable.</p>
     *
     * @param companyIds Comma-separated company UUIDs (optional)
     * @param order      Sort order: "DESC" for highest first (default), "ASC" for lowest first
     * @param limit      Maximum number of results (default 10)
     * @return List of consultant utilization rankings
     */
    @GET
    @Path("/consultant-utilization-rankings")
    public List<ConsultantUtilizationRankingDTO> getConsultantUtilizationRankings(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("order") @DefaultValue("DESC") String order,
            @QueryParam("limit") @DefaultValue("10") int limit) {

        log.debugf("GET /finance/cxo/consultant-utilization-rankings: companyIds=%s, order=%s, limit=%d",
                companyIds, order, limit);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);
        boolean ascending = "ASC".equalsIgnoreCase(order);

        return consultantInsightsService.getUtilizationRankings(companyIdSet, ascending, limit);
    }

    /**
     * Returns time-to-first-contract data for consultants hired within the last N months.
     * Shows hire date, first contract start date, and days between them.
     *
     * <p>Scoped to the five core practices — see {@code ConsultantInsightsService}.</p>
     *
     * @param companyIds Comma-separated company UUIDs (optional)
     * @param months     How many months back to look for hires (default 24)
     * @return List of hire-to-contract DTOs ordered by hire date ascending
     */
    @GET
    @Path("/time-to-first-contract")
    public List<TimeToFirstContractDTO> getTimeToFirstContract(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("months") @DefaultValue("24") int months) {

        log.debugf("GET /finance/cxo/time-to-first-contract: companyIds=%s, months=%d",
                companyIds, months);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return consultantInsightsService.getTimeToFirstContract(companyIdSet, months);
    }

    /**
     * Returns consultants who have had no active contract for at least the specified number of months.
     * Only includes currently active consultants.
     *
     * <p>Scoped to the five core practices — see {@code ConsultantInsightsService}.</p>
     *
     * @param companyIds Comma-separated company UUIDs (optional)
     * @param minMonths  Minimum months without a contract (default 3)
     * @return List of bench consultant DTOs ordered by days since contract descending
     */
    @GET
    @Path("/consultants-without-contract")
    public List<ConsultantWithoutContractDTO> getConsultantsWithoutContract(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("minMonths") @DefaultValue("3") int minMonths) {

        log.debugf("GET /finance/cxo/consultants-without-contract: companyIds=%s, minMonths=%d",
                companyIds, minMonths);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return consultantInsightsService.getConsultantsWithoutContract(companyIdSet, minMonths);
    }

    /**
     * Returns consultants whose trailing 12-month net profit is negative.
     * Net Profit = Revenue - Salary - (Total OPEX / headcount).
     *
     * <p>Scoped to the five core practices — see {@code ConsultantInsightsService}. The
     * shared-overhead divisor is the headcount of that same population, so the scope
     * affects every consultant's net profit, not just who appears in the list.</p>
     *
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of unprofitable consultant DTOs ordered by net profit ascending (worst first)
     */
    @GET
    @Path("/unprofitable-consultants")
    public List<UnprofitableConsultantDTO> getUnprofitableConsultants(
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/unprofitable-consultants: companyIds=%s", companyIds);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return consultantInsightsService.getUnprofitableConsultants(companyIdSet);
    }

    /**
     * Returns per-consultant gap between budgeted hours and actual billable hours over TTM.
     * Identifies consultants driving the largest budget-actual discrepancy.
     *
     * <p>Scoped to the five core practices — see {@code ConsultantInsightsService}.</p>
     *
     * @param companyIds Comma-separated company UUIDs (optional)
     * @param limit      Maximum number of results (default 15)
     * @return List of budget-actual gap DTOs ordered by gap descending (largest gap first)
     */
    @GET
    @Path("/budget-actual-gap")
    public List<BudgetActualGapDTO> getBudgetActualGap(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("limit") @DefaultValue("15") int limit) {

        log.debugf("GET /finance/cxo/budget-actual-gap: companyIds=%s, limit=%d",
                companyIds, limit);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return consultantInsightsService.getBudgetActualGap(companyIdSet, limit);
    }

    /**
     * Returns monthly rows with budget hours and actual hours for a specific consultant.
     * Used for the per-consultant drill-down chart and the team dashboard fulfillment heatmap.
     *
     * <p>Without {@code fromDate}/{@code toDate} the window is TTM (12 complete months,
     * excluding the current month). With both params the window is the given inclusive
     * date range — the team dashboard passes its fiscal-year months here so the budget
     * series covers the same months as the rest of the FY-scoped dashboard.</p>
     *
     * @param userId the consultant's user UUID
     * @param fromDate optional inclusive window start (ISO date)
     * @param toDate optional inclusive window end (ISO date)
     * @return List of monthly budget-vs-actual DTOs ordered by month ascending
     */
    @GET
    @Path("/budget-actual-gap/{userId}/monthly")
    public List<BudgetActualGapMonthlyDTO> getBudgetActualGapMonthly(
            @PathParam("userId") String userId,
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {

        log.debugf("GET /finance/cxo/budget-actual-gap/%s/monthly?fromDate=%s&toDate=%s",
                userId, fromDate, toDate);

        if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()) {
            LocalDate from;
            LocalDate to;
            try {
                from = LocalDate.parse(fromDate);
                to = LocalDate.parse(toDate);
            } catch (java.time.format.DateTimeParseException e) {
                throw new jakarta.ws.rs.BadRequestException("fromDate/toDate must be ISO dates (yyyy-MM-dd)");
            }
            if (from.isAfter(to) || from.plusMonths(36).isBefore(to)) {
                throw new jakarta.ws.rs.BadRequestException("fromDate..toDate must be a forward range of at most 36 months");
            }
            return consultantInsightsService.getBudgetActualGapMonthly(userId, from, to);
        }
        return consultantInsightsService.getBudgetActualGapMonthly(userId);
    }

    // =========================================================================
    // Endpoints replacing BFF direct SQL queries (Task 8)
    // =========================================================================

    /**
     * Returns budget hours per month from fact_revenue_budget_mat.
     * Replaces BFF direct SQL in /api/executive/utilization-trend.
     *
     * @param fromMonthKey Start month key YYYYMM (inclusive)
     * @param toMonthKey   End month key YYYYMM (exclusive)
     * @param practices    Comma-separated practice/service line codes (optional)
     * @param companyIds   Comma-separated company UUIDs (optional)
     * @return List of monthly budget hour totals
     */
    @GET
    @Path("/budget-hours-by-month")
    public List<BudgetHoursByMonthDTO> getBudgetHoursByMonth(
            @QueryParam("fromMonthKey") String fromMonthKey,
            @QueryParam("toMonthKey") String toMonthKey,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/budget-hours-by-month: fromMonthKey=%s, toMonthKey=%s, practices=%s, companyIds=%s",
                fromMonthKey, toMonthKey, practices, companyIds);

        Set<String> practiceSet = practiceService.normalizePracticeFilter(parseCommaSeparated(practices));
        // practices= accepts registry storage codes or uuids (§4.5); values are
        // validated against the registry and normalized to storage codes.
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return cxoFinanceService.getBudgetHoursByMonth(fromMonthKey, toMonthKey, practiceSet, companyIdSet);
    }

    /**
     * Returns net available hours for future months from fact_user_day.
     * Replaces BFF direct SQL in /api/executive/utilization-trend.
     *
     * @param fromMonthKey Start month key YYYYMM (inclusive)
     * @param toMonthKey   End month key YYYYMM (exclusive)
     * @param practices    Comma-separated practice codes (optional)
     * @param companyIds   Comma-separated company UUIDs (optional)
     * @return List of monthly net available hour totals
     */
    @GET
    @Path("/future-net-available")
    public List<FutureNetAvailableDTO> getFutureNetAvailable(
            @QueryParam("fromMonthKey") String fromMonthKey,
            @QueryParam("toMonthKey") String toMonthKey,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/future-net-available: fromMonthKey=%s, toMonthKey=%s, practices=%s, companyIds=%s",
                fromMonthKey, toMonthKey, practices, companyIds);

        Set<String> practiceSet = practiceService.normalizePracticeFilter(parseCommaSeparated(practices));
        // practices= accepts registry storage codes or uuids (§4.5); values are
        // validated against the registry and normalized to storage codes.
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return cxoFinanceService.getFutureNetAvailable(fromMonthKey, toMonthKey, practiceSet, companyIdSet);
    }

    /**
     * Returns distinct active consultants for a given month in the specified practices.
     * Replaces BFF route /api/executive/utilization-consultants.
     *
     * @param year       Year (defaults to current year)
     * @param month      Month number 1-12 (defaults to current month)
     * @param practices  Comma-separated practice codes (optional)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of consultant DTOs
     */
    @GET
    @Path("/utilization-consultants")
    public List<UtilizationConsultantDTO> getUtilizationConsultants(
            @QueryParam("year") Integer year,
            @QueryParam("month") Integer month,
            @QueryParam("practices") String practices,
            @QueryParam("companyIds") String companyIds) {

        int effectiveYear = (year != null) ? year : LocalDate.now().getYear();
        int effectiveMonth = (month != null) ? month : LocalDate.now().getMonthValue();

        log.debugf("GET /finance/cxo/utilization-consultants: year=%d, month=%d, practices=%s, companyIds=%s",
                effectiveYear, effectiveMonth, practices, companyIds);

        Set<String> practiceSet = practiceService.normalizePracticeFilter(parseCommaSeparated(practices));
        // practices= accepts registry storage codes or uuids (§4.5); values are
        // validated against the registry and normalized to storage codes.
        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return cxoFinanceService.getUtilizationConsultants(effectiveYear, effectiveMonth, practiceSet, companyIdSet);
    }

    /**
     * Returns monthly absence breakdown showing how gross capacity is reduced.
     * Replaces BFF route /api/cxo/delivery/absence-waterfall.
     *
     * @param fromDate   Start date (ISO-8601, optional, defaults to 12 months ago)
     * @param toDate     End date (ISO-8601, optional, defaults to today)
     * @param companyIds Comma-separated company UUIDs (optional)
     * @return List of monthly absence waterfall DTOs
     */
    @GET
    @Path("/absence-waterfall")
    public List<MonthlyAbsenceWaterfallDTO> getAbsenceWaterfall(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("companyIds") String companyIds) {

        log.debugf("GET /finance/cxo/absence-waterfall: fromDate=%s, toDate=%s, companyIds=%s",
                fromDate, toDate, companyIds);

        Set<String> companyIdSet = parseCommaSeparated(companyIds);

        return cxoFinanceService.getAbsenceWaterfall(fromDate, toDate, companyIdSet);
    }

    /**
     * CXO Command Center: trailing 18 months of cost-to-revenue ratio data.
     *
     * Returns one data point per month with revenue, direct delivery cost, OPEX,
     * and the cost-to-revenue ratio in percent (null when revenue is zero).
     *
     * @param companyIds optional comma-separated list of company UUIDs to filter by
     * @return list of data points ordered by month_key ascending
     */
    @GET
    @Path("/cost-to-revenue")
    public List<CostToRevenueDataPointDTO> costToRevenue(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam) {
        log.debugf("GET /finance/cxo/cost-to-revenue: companyIds=%s, costSource=%s", companyIds, costSourceParam);
        return cxoFinanceService.costToRevenue(
                parseCommaSeparated(companyIds),
                CostSource.fromQueryParam(costSourceParam));
    }

    /**
     * CXO Command Center: trailing 18 months of gross margin data.
     *
     * Returns one data point per month with total revenue, total delivery cost,
     * and the gross margin in percent (null when revenue is zero, though the SQL
     * HAVING clause excludes zero-revenue months from the result set).
     *
     * @param companyIds optional comma-separated list of company UUIDs to filter by
     * @return list of data points ordered by month_key ascending
     */
    @GET
    @Path("/gross-margin-trend")
    public List<GrossMarginTrendDataPointDTO> grossMarginTrend(@QueryParam("companyIds") String companyIds) {
        log.debugf("GET /finance/cxo/gross-margin-trend: companyIds=%s", companyIds);
        return cxoFinanceService.grossMarginTrend(parseCommaSeparated(companyIds));
    }

    /**
     * CXO Command Center: monthly invoiced revenue broken down by practice (service line),
     * with cost and margin overlay.
     *
     * <p>Revenue is attributed at the consultant level via {@code u.practice} of the delivering
     * consultant — NOT the project-level service line — to avoid distortion from EXTERNAL
     * consultants, mixed-practice projects, and "UD" (Undefined) buckets. Cost remains
     * project-level (from {@code fact_project_financials_mat}). Months with cost but no
     * revenue still appear in the response with an empty practice revenue map.</p>
     *
     * @param fromDate optional ISO date (YYYY-MM-DD); defaults to first day of (today − 17 months)
     * @param toDate   optional ISO date (YYYY-MM-DD); defaults to today
     * @param companyIds optional comma-separated list of company UUIDs to filter by
     * @return wrapper containing the monthly series and the ordered practices list
     */
    @GET
    @Path("/revenue-by-practice")
    public RevenuePracticeDTO revenueByPractice(
            @QueryParam("fromDate") LocalDate fromDate,
            @QueryParam("toDate") LocalDate toDate,
            @QueryParam("companyIds") String companyIds) {
        log.debugf("GET /finance/cxo/revenue-by-practice: fromDate=%s, toDate=%s, companyIds=%s",
                fromDate, toDate, companyIds);
        return cxoFinanceService.revenueByPractice(fromDate, toDate, parseCommaSeparated(companyIds));
    }

    /**
     * Live salary-GL anomalies — (companyuuid × month) cells where the GL
     * total in {@code finance_details} on {@code cost_type='SALARIES'} accounts
     * is materially below the intended salary total from
     * {@code fact_salary_monthly} over the last few completed months.
     *
     * <p>Drives the executive dashboard's pending-data banner on the EBITDA
     * Forecast chart. Computed on each call (no caching) — the underlying
     * SalaryGLAnomalyCheck runs the same SQL on its nightly cron at 04:00 UTC
     * and on app boot, so by the time a dashboard viewer hits this endpoint
     * the data sources have already settled for the day.
     *
     * @return list of anomaly cells, empty when the chart's salary data is
     *         consistent with the intended payroll.
     */
    @GET
    @Path("/salary-gl-anomalies")
    public List<SalaryGLAnomalyDTO> getSalaryGLAnomalies() {
        log.debugf("GET /finance/cxo/salary-gl-anomalies");
        List<SalaryGLAnomalyCheck.Anomaly> anomalies = salaryGLAnomalyCheck.detect();
        // Resolve company name once per call. Only 3 tenants, so a full listAll
        // is cheaper than per-anomaly Company.findById lookups.
        Map<String, String> companyNameByUuid = Company.<Company>listAll().stream()
                .collect(Collectors.toMap(Company::getUuid, Company::getName));
        List<SalaryGLAnomalyDTO> result = anomalies.stream()
                .map(a -> new SalaryGLAnomalyDTO(
                        a.companyUuid(),
                        companyNameByUuid.getOrDefault(a.companyUuid(), a.companyUuid()),
                        a.year(),
                        a.month(),
                        a.glSalary(),
                        a.intendedSalary(),
                        a.gapDkk(),
                        a.coveragePct()))
                .collect(Collectors.toList());
        log.debugf("Returning %d salary-gl-anomaly cells", result.size());
        return result;
    }

    /**
     * Parses comma-separated string into a Set of trimmed values.
     * Returns null if input is null or empty.
     */
    /**
     * Upper bound on comma-separated filter values, mirroring
     * {@code CostAnalyticsResource.MAX_COMPANY_IDS}. Caps the size of any generated
     * {@code IN(...)} clause so an authenticated caller cannot send an unbounded list.
     * Set well above any legitimate dimension cardinality (companies, sectors, service
     * lines, contract types, practices are all small enumerations).
     */
    private static final int MAX_FILTER_VALUES = 200;

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
        if (result.size() > MAX_FILTER_VALUES) {
            throw new jakarta.ws.rs.BadRequestException("Too many filter values (max " + MAX_FILTER_VALUES + ")");
        }
        return result.isEmpty() ? null : result;
    }
}
