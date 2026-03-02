package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.BacklogCoverageDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BillableUtilizationLast4WeeksDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientRetentionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ForecastUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.GrossMarginTTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyAccumulatedEbitdaDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyAccumulatedRevenueDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyCostCenterMixDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyExpenseMixDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyOverheadPerFTEDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyPayrollHeadcountDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyPipelineBacklogDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexBridgeDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexDetailRowDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.finance.dto.RealizationRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RepeatBusinessShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenuePerBillableFTETTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueYTDDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.Top5ClientsShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TTMRevenueGrowthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.DirectCostRowDTO;
import dk.trustworks.intranet.aggregates.finance.dto.EbitdaSourceDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.InternalInvoiceRowDTO;
import dk.trustworks.intranet.aggregates.finance.dto.InvoiceExportDTO;
import dk.trustworks.intranet.aggregates.finance.dto.InvoiceItemExportDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexRowExportDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueSourceDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.VoluntaryAttritionDTO;
import dk.trustworks.intranet.utils.TwConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for CxO dashboard finance aggregation.
 *
 * <h2>Revenue data source</h2>
 * All revenue KPIs ({@code getRevenueYTDvsBudget}, {@code getTTMRevenueGrowth},
 * {@code getGrossMarginTTM}, {@code getRevenuePerBillableFTETTM},
 * {@code getRevenueMarginTrend}, {@code getAccumulatedRevenue},
 * {@code getExpectedAccumulatedEBITDA}) read from {@code fact_company_revenue_mat}
 * (grain: company × month).  This view implements the canonical revenue algorithm
 * from {@code docs/finalized/invoicing/revenue-calculation-nov-2025.md}:
 * INVOICE + PHANTOM as positive revenue, INTERNAL as positive revenue for the
 * issuing company, CREDIT_NOTE subtracted, and proportional discount allocation.
 *
 * <h2>Filter handling for revenue</h2>
 * Revenue KPIs always show <strong>company-total</strong> figures regardless of
 * dimension filters (sector, service line, contract type, client).  Only the
 * {@code companyIds} filter applies to revenue queries.  Dimension filters
 * (sector, service line, contract type, client) apply exclusively to cost-based
 * metrics that still read from {@code fact_project_financials_mat}.
 *
 * <h2>Cost data source</h2>
 * All direct-delivery cost metrics continue to read from
 * {@code fact_project_financials_mat} with the full set of dimension filters.
 */
@JBossLog
@ApplicationScoped
public class CxoFinanceService {

    @Inject
    EntityManager em;

    @Inject
    DistributionAwareOpexProvider opexProvider;

    /**
     * Retrieves monthly revenue and margin data for the specified period and filters.
     *
     * @param fromDate Start date (inclusive, clamped to first of month)
     * @param toDate End date (inclusive, clamped to last of month)
     * @param sectors Multi-select sector filter (e.g., "PUBLIC", "HEALTH")
     * @param serviceLines Multi-select service line filter (e.g., "PM", "DEV")
     * @param contractTypes Multi-select contract type filter (e.g., "T&M", "FIXED")
     * @param clientId Single-select client filter (optional)
     * @param companyIds Multi-select company filter (UUIDs)
     * @return List of monthly data points sorted chronologically
     */
    public List<MonthlyRevenueMarginDTO> getRevenueMarginTrend(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Normalize dates: clamp to first/last of month
        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1).plusMonths(1).minusDays(1) : LocalDate.now();

        // Convert to YYYYMM month keys for efficient filtering
        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("getRevenueMarginTrend: fromDate=%s (%s), toDate=%s (%s), sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                normalizedFromDate, fromMonthKey, normalizedToDate, toMonthKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Revenue: fact_company_revenue_mat (company-total, companyIds filter only).
        // Revenue KPIs always show company-total — sector/serviceLine/contractType/client filters
        // do not apply to revenue.  Only companyIds scoping is honored.
        java.util.Map<String, Double> revenueByMonth = queryCompanyRevenueByMonth(
                fromMonthKey, toMonthKey, companyIds);

        // Cost: fact_project_financials_mat with deduplication + all dimension filters.
        // V118 migration added companyuuid column, creating multiple rows per project-month.
        // Strategy: GROUP BY project_id + month_key with MAX(cost) before aggregating by calendar month.
        StringBuilder costInner = new StringBuilder(
                "SELECT " +
                "    f.project_id, " +
                "    f.month_key, " +
                "    f.year, " +
                "    f.month_number, " +
                "    MAX(f.direct_delivery_cost_dkk) AS cost " +
                "FROM fact_project_financials_mat f " +
                "WHERE f.month_key >= :fromMonthKey " +
                "  AND f.month_key <= :toMonthKey "
        );
        if (sectors != null && !sectors.isEmpty()) {
            costInner.append("  AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            costInner.append("  AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            costInner.append("  AND f.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.isBlank()) {
            costInner.append("  AND f.client_id = :clientId ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            costInner.append("  AND f.companyuuid IN (:companyIds) ");
        }
        costInner.append("GROUP BY f.project_id, f.month_key, f.year, f.month_number");

        String costSql = "SELECT d.month_key, d.year, d.month_number, SUM(d.cost) AS cost " +
                         "FROM (" + costInner + ") AS d " +
                         "GROUP BY d.year, d.month_number, d.month_key " +
                         "ORDER BY d.year ASC, d.month_number ASC";

        var costQuery = em.createNativeQuery(costSql, Tuple.class);
        costQuery.setParameter("fromMonthKey", fromMonthKey);
        costQuery.setParameter("toMonthKey", toMonthKey);
        if (sectors != null && !sectors.isEmpty()) {
            costQuery.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            costQuery.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            costQuery.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.isBlank()) {
            costQuery.setParameter("clientId", clientId);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            costQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> costRows = costQuery.getResultList();

        log.debugf("Cost query returned %d rows", costRows.size());

        // Merge revenue (company-total) and cost (dimension-filtered) into monthly DTOs.
        // The set of months is driven by the cost query (project activity defines months shown).
        // Months present only in revenue are included by iterating the revenue map for any
        // months that have no cost row.
        java.util.Map<String, double[]> costByMonth = new java.util.LinkedHashMap<>();
        for (Tuple row : costRows) {
            String mk = (String) row.get("month_key");
            int year  = ((Number) row.get("year")).intValue();
            int month = ((Number) row.get("month_number")).intValue();
            double cost = row.get("cost") != null ? ((Number) row.get("cost")).doubleValue() : 0.0;
            costByMonth.put(mk, new double[]{year, month, cost});
        }

        // Union of months from both sources, sorted chronologically
        java.util.Set<String> allMonthKeys = new java.util.TreeSet<>(costByMonth.keySet());
        allMonthKeys.addAll(revenueByMonth.keySet());

        List<MonthlyRevenueMarginDTO> dtos = new ArrayList<>();
        for (String monthKey : allMonthKeys) {
            double[] yearMonth = costByMonth.getOrDefault(monthKey, null);
            int year;
            int monthNumber;
            if (yearMonth != null) {
                year        = (int) yearMonth[0];
                monthNumber = (int) yearMonth[1];
            } else {
                year        = Integer.parseInt(monthKey.substring(0, 4));
                monthNumber = Integer.parseInt(monthKey.substring(4));
            }
            double revenue = revenueByMonth.getOrDefault(monthKey, 0.0);
            double cost    = yearMonth != null ? yearMonth[2] : 0.0;

            Double marginPercent = (revenue > 0)
                    ? ((revenue - cost) / revenue) * 100.0
                    : null;

            String monthLabel = formatMonthLabel(year, monthNumber);
            dtos.add(new MonthlyRevenueMarginDTO(monthKey, year, monthNumber, monthLabel, revenue, cost, marginPercent));

            log.debugf("Month %s: revenue=%.2f, cost=%.2f, margin=%.2f%%",
                    monthKey, revenue, cost, marginPercent != null ? marginPercent : 0.0);
        }

        return dtos;
    }

    /**
     * Formats year and month into a user-friendly label (e.g., "Jan 2025").
     */
    private String formatMonthLabel(int year, int monthNumber) {
        String[] monthNames = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return monthNames[monthNumber - 1] + " " + year;
    }

    /**
     * Retrieves monthly utilization and capacity data for Chart B.
     * Queries fact_user_day (the business-standard data source) aggregated by month.
     * Consistent with /dashboard utilization calculations.
     *
     * @param fromDate Start date (inclusive, clamped to first of month)
     * @param toDate End date (inclusive, clamped to last of month)
     * @param practices Multi-select practice/service line filter (e.g., "PM", "DEV")
     * @param companyIds Multi-select company filter (UUIDs)
     * @return List of monthly utilization data points sorted chronologically
     */
    public List<MonthlyUtilizationDTO> getUtilizationTrend(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // Normalize dates: clamp to first/last of month
        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1).plusMonths(1).minusDays(1) : LocalDate.now();

        log.debugf("getUtilizationTrend: fromDate=%s, toDate=%s, practices=%s, companyIds=%s",
                normalizedFromDate, normalizedToDate, practices, companyIds);

        boolean hasPractices = practices != null && !practices.isEmpty();
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  CONCAT(LPAD(bdd.year, 4, '0'), LPAD(bdd.month, 2, '0')) AS month_key, ");
        sql.append("  bdd.year, ");
        sql.append("  bdd.month AS month_number, ");
        sql.append("  COALESCE(SUM(bdd.registered_billable_hours), 0) AS billable_hours, ");
        sql.append("  COALESCE(SUM(bdd.vacation_hours + bdd.sick_hours + bdd.maternity_leave_hours ");
        sql.append("      + bdd.non_payd_leave_hours + bdd.paid_leave_hours), 0) AS absence_hours, ");
        sql.append("  COALESCE(SUM(bdd.net_available_hours), 0) AS net_available_hours, ");
        sql.append("  COALESCE(SUM(bdd.gross_available_hours), 0) AS gross_available_hours ");
        sql.append("FROM fact_user_day bdd ");

        if (hasPractices) {
            sql.append("JOIN user u ON u.uuid = bdd.useruuid ");
        }

        sql.append("WHERE bdd.document_date >= :fromDate ");
        sql.append("  AND bdd.document_date <= :toDate ");
        sql.append("  AND bdd.consultant_type = 'CONSULTANT' ");
        sql.append("  AND bdd.status_type = 'ACTIVE' ");

        if (hasPractices) {
            sql.append("  AND u.practice IN (:practices) ");
        }
        if (hasCompanies) {
            sql.append("  AND bdd.companyuuid IN (:companyIds) ");
        }

        sql.append("GROUP BY bdd.year, bdd.month ");
        sql.append("ORDER BY bdd.year ASC, bdd.month ASC");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromDate", normalizedFromDate);
        query.setParameter("toDate", normalizedToDate);

        if (hasPractices) {
            query.setParameter("practices", practices);
        }
        if (hasCompanies) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        log.debugf("Utilization query returned %d rows", results.size());

        // Map results to DTOs
        List<MonthlyUtilizationDTO> dtos = new ArrayList<>();
        for (Tuple row : results) {
            String monthKey = (String) row.get("month_key");
            int year = ((Number) row.get("year")).intValue();
            int monthNumber = ((Number) row.get("month_number")).intValue();
            double billableHours = ((Number) row.get("billable_hours")).doubleValue();
            double absenceHours = ((Number) row.get("absence_hours")).doubleValue();
            double netAvailableHours = ((Number) row.get("net_available_hours")).doubleValue();
            double grossAvailableHours = ((Number) row.get("gross_available_hours")).doubleValue();

            // Non-billable = net_available - billable
            // net_available_hours already excludes absence, so no need to subtract it again
            double nonBillableHours = Math.max(0, netAvailableHours - billableHours);

            // Utilization: billable / net_available * 100
            // Consistent with /dashboard: net_available_hours already deducts all absence
            Double utilizationPercent = null;
            if (netAvailableHours > 0) {
                utilizationPercent = (billableHours / netAvailableHours) * 100.0;
            }

            String monthLabel = formatMonthLabel(year, monthNumber);

            MonthlyUtilizationDTO dto = new MonthlyUtilizationDTO(
                    monthKey,
                    year,
                    monthNumber,
                    monthLabel,
                    billableHours,
                    nonBillableHours,
                    absenceHours,
                    netAvailableHours,
                    grossAvailableHours,
                    utilizationPercent
            );

            dtos.add(dto);
            log.debugf("Month %s: billable=%.1f, nonBillable=%.1f, absence=%.1f, utilization=%.1f%%",
                    monthKey, billableHours, nonBillableHours, absenceHours, utilizationPercent != null ? utilizationPercent : 0);
        }

        return dtos;
    }

    /**
     * Retrieves Revenue YTD vs Budget data for the CXO Dashboard KPI.
     * Compares fiscal year-to-date actual revenue against budget, with prior year comparison and sparkline data.
     *
     * @param asOfDate Current date (for YTD calculation, defaults to today)
     * @param sectors Multi-select sector filter (e.g., "PUBLIC", "HEALTH")
     * @param serviceLines Multi-select service line filter (e.g., "PM", "DEV")
     * @param contractTypes Multi-select contract type filter (e.g., "T&M", "FIXED")
     * @param clientId Single-select client filter (optional, applies only to actual revenue)
     * @param companyIds Multi-select company filter (UUIDs)
     * @return Revenue YTD data with actual, budget, attainment %, variance, YoY comparison, and 12-month sparkline
     */
    public RevenueYTDDataDTO getRevenueYTDvsBudget(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate normalizedAsOfDate = (asOfDate != null) ? asOfDate : LocalDate.now();

        // 1. Calculate fiscal year boundaries (Trustworks fiscal year: July 1 - June 30)
        int currentFiscalYear = normalizedAsOfDate.getMonthValue() >= 7
                ? normalizedAsOfDate.getYear()
                : normalizedAsOfDate.getYear() - 1;
        LocalDate fiscalYearStart = LocalDate.of(currentFiscalYear, 7, 1);  // July 1
        LocalDate ytdEnd = normalizedAsOfDate;

        // Prior fiscal year - same duration
        int priorFiscalYear = currentFiscalYear - 1;
        LocalDate priorYearStart = LocalDate.of(priorFiscalYear, 7, 1);
        long monthsBetween = ChronoUnit.MONTHS.between(fiscalYearStart, ytdEnd);
        LocalDate priorYearEnd = priorYearStart.plusMonths(monthsBetween);

        log.debugf("Revenue YTD calculation: Current FY=%d (%s to %s), Prior FY=%d (%s to %s)",
                currentFiscalYear, fiscalYearStart, ytdEnd,
                priorFiscalYear, priorYearStart, priorYearEnd);

        // 2. Query actual revenue YTD (current fiscal year).
        // Revenue KPIs use fact_company_revenue_mat — company-total, no dimension filters.
        double actualYTD = queryCompanyRevenue(fiscalYearStart, ytdEnd, companyIds);

        // 3. Query budget YTD (current fiscal year)
        double budgetYTD = queryBudgetRevenue(
                fiscalYearStart, ytdEnd, sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 4. Query prior year actual YTD (same fiscal period).
        // Revenue KPIs use fact_company_revenue_mat — company-total, no dimension filters.
        double priorYearYTD = queryCompanyRevenue(priorYearStart, priorYearEnd, companyIds);

        // 5. Query sparkline data (last 12 months actual revenue, company-total).
        LocalDate sparklineStart = normalizedAsOfDate.minusMonths(11).withDayOfMonth(1);
        double[] sparklineData = queryCompanyRevenueSparkline(
                sparklineStart, normalizedAsOfDate, companyIds, 12
        );

        // 6. Calculate metrics
        double attainmentPercent = budgetYTD > 0 ? (actualYTD / budgetYTD) * 100.0 : 0.0;
        double varianceDKK = actualYTD - budgetYTD;
        double yoyChangePercent = priorYearYTD > 0
                ? ((actualYTD - priorYearYTD) / priorYearYTD) * 100.0
                : 0.0;

        log.debugf("Revenue YTD results: Actual=%.2f, Budget=%.2f, Attainment=%.2f%%, Prior Year=%.2f, YoY=%.2f%%",
                actualYTD, budgetYTD, attainmentPercent, priorYearYTD, yoyChangePercent);

        return new RevenueYTDDataDTO(
                actualYTD,
                budgetYTD,
                attainmentPercent,
                varianceDKK,
                priorYearYTD,
                yoyChangePercent,
                sparklineData
        );
    }

    /**
     * Calculate TTM Revenue Growth %
     * Compares current trailing twelve months revenue vs prior 12-month period for year-over-year growth.
     *
     * @param fromDate Start of time filter range (not used for TTM calculation, but needed for filter consistency)
     * @param toDate End of time filter range (determines anchor date for TTM calculation)
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param clientId Client filter (nullable)
     * @param companyIds Company filter (nullable)
     * @return TTMRevenueGrowthDTO with current TTM, prior TTM, growth %, and sparkline
     */
    public TTMRevenueGrowthDTO getTTMRevenueGrowth(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate normalizedToDate = (toDate != null) ? toDate : LocalDate.now();

        // 1. Calculate anchor date (end of month containing toDate)
        LocalDate anchorDate = normalizedToDate.withDayOfMonth(normalizedToDate.lengthOfMonth());

        // 2. Current TTM window: 12 months ending on anchor date
        LocalDate currentTTMStart = anchorDate.minusMonths(11).withDayOfMonth(1);
        LocalDate currentTTMEnd = anchorDate;

        // 3. Prior TTM window: 12 months ending one year before anchor date
        LocalDate priorTTMStart = currentTTMStart.minusYears(1);
        LocalDate priorTTMEnd = currentTTMEnd.minusYears(1);

        log.debugf("TTM Revenue Growth calculation: Current TTM (%s to %s), Prior TTM (%s to %s)",
                currentTTMStart, currentTTMEnd, priorTTMStart, priorTTMEnd);

        // 4. Query current TTM revenue.
        // Revenue KPIs use fact_company_revenue_mat — company-total, no dimension filters.
        double currentTTM = queryCompanyRevenue(currentTTMStart, currentTTMEnd, companyIds);

        // 5. Query prior TTM revenue.
        // Revenue KPIs use fact_company_revenue_mat — company-total, no dimension filters.
        double priorTTM = queryCompanyRevenue(priorTTMStart, priorTTMEnd, companyIds);

        // 6. Calculate growth percentage (handle division by zero)
        double growthPercent = (priorTTM > 0)
                ? ((currentTTM - priorTTM) / priorTTM) * 100.0
                : 0.0;

        // 7. Query sparkline data (last 12 calendar months, company-total).
        double[] sparklineData = queryCompanyRevenueSparkline(
                currentTTMStart, currentTTMEnd, companyIds, 12
        );

        log.debugf("TTM Revenue Growth results: Current=%.2f, Prior=%.2f, Growth=%.2f%%",
                currentTTM, priorTTM, growthPercent);

        // 8. Return DTO
        return new TTMRevenueGrowthDTO(
                currentTTM,
                priorTTM,
                growthPercent,
                sparklineData
        );
    }

    /**
     * Calculate Gross Margin % (TTM) KPI
     * Compares current trailing twelve months gross margin vs prior 12-month period.
     * Gross Margin % = ((Revenue - Direct Delivery Costs) / Revenue) × 100
     *
     * @param fromDate Start of time filter range (not used for TTM calculation, but needed for filter consistency)
     * @param toDate End of time filter range (determines anchor date for TTM calculation)
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param clientId Client filter (nullable)
     * @param companyIds Company filter (nullable)
     * @return GrossMarginTTMDTO with current/prior revenue, cost, margin %, change, and 12-month sparkline
     */
    public GrossMarginTTMDTO getGrossMarginTTM(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate normalizedToDate = (toDate != null) ? toDate : LocalDate.now();

        // 1. Calculate anchor date (end of month containing toDate)
        LocalDate anchorDate = normalizedToDate.withDayOfMonth(normalizedToDate.lengthOfMonth());

        // 2. Current TTM window: 12 months ending on anchor date
        LocalDate currentTTMStart = anchorDate.minusMonths(11).withDayOfMonth(1);
        LocalDate currentTTMEnd = anchorDate;

        // 3. Prior TTM window: 12 months ending one year before anchor date
        LocalDate priorTTMStart = currentTTMStart.minusYears(1);
        LocalDate priorTTMEnd = currentTTMEnd.minusYears(1);

        log.debugf("Gross Margin TTM calculation: Current TTM (%s to %s), Prior TTM (%s to %s)",
                currentTTMStart, currentTTMEnd, priorTTMStart, priorTTMEnd);

        String currentFromKey = currentTTMStart.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String currentToKey   = currentTTMEnd.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String priorFromKey   = priorTTMStart.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String priorToKey     = priorTTMEnd.format(DateTimeFormatter.ofPattern("yyyyMM"));

        // 4. Query current TTM revenue (company-total from fact_company_revenue_mat)
        // and costs (from fact_project_financials_mat with dimension filters).
        // Revenue KPIs always show company-total — dimension filters do not apply to revenue.
        double currentRevenue = queryCompanyRevenue(currentTTMStart, currentTTMEnd, companyIds);
        double currentCost = queryActualCosts(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double currentInternalCost = queryTTMInternalInvoiceCost(currentFromKey, currentToKey, companyIds);

        // 5. Query prior TTM revenue (company-total) and costs (dimension-filtered).
        double priorRevenue = queryCompanyRevenue(priorTTMStart, priorTTMEnd, companyIds);
        double priorCost = queryActualCosts(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorInternalCost = queryTTMInternalInvoiceCost(priorFromKey, priorToKey, companyIds);

        // 6. Calculate margin percentages (handle division by zero)
        // Gross margin = (revenue - directCost) / revenue
        // Note: internalInvoiceCost is NOT subtracted because fact_project_financials
        // already attributes revenue and costs to each consultant's company.
        double currentMarginPercent = (currentRevenue > 0)
                ? ((currentRevenue - currentCost) / currentRevenue) * 100.0
                : 0.0;
        double priorMarginPercent = (priorRevenue > 0)
                ? ((priorRevenue - priorCost) / priorRevenue) * 100.0
                : 0.0;

        // 7. Calculate margin change in percentage points (not percentage growth)
        // Example: 35.2% → 38.1% = +2.9 percentage points
        double marginChangePct = currentMarginPercent - priorMarginPercent;

        // 8. Query sparkline data (last 12 months of margin percentages)
        double[] sparklineData = queryMonthlyMarginPercent(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        log.debugf("Gross Margin TTM results: Current Revenue=%.2f, Current Cost=%.2f, Current Margin=%.2f%%, " +
                        "Prior Margin=%.2f%%, Change=%.2f pp",
                currentRevenue, currentCost, currentMarginPercent, priorMarginPercent, marginChangePct);

        // 9. Return DTO
        return new GrossMarginTTMDTO(
                currentRevenue,
                currentCost,
                currentMarginPercent,
                priorRevenue,
                priorCost,
                priorMarginPercent,
                marginChangePct,
                sparklineData
        );
    }

    /**
     * Calculate Backlog Coverage (Months) KPI
     * Measures how many months of revenue are covered by signed backlog.
     * Formula: Coverage (Months) = Total Backlog Revenue / Average Monthly Revenue
     *
     * @param asOfDate Current date for calculation (defaults to today)
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param clientId Client filter (nullable)
     * @param companyIds Company filter (nullable)
     * @return BacklogCoverageDTO with current coverage, prior coverage, and change %
     */
    public BacklogCoverageDTO getBacklogCoverage(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate normalizedAsOfDate = (asOfDate != null) ? asOfDate : LocalDate.now();

        log.debugf("Backlog Coverage calculation: asOfDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                normalizedAsOfDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 1. Calculate TTM window for average monthly revenue (trailing 12 months)
        LocalDate ttmEnd = normalizedAsOfDate.withDayOfMonth(normalizedAsOfDate.lengthOfMonth());
        LocalDate ttmStart = ttmEnd.minusMonths(11).withDayOfMonth(1);

        // Prior period TTM (one year earlier)
        LocalDate priorTTMStart = ttmStart.minusYears(1);
        LocalDate priorTTMEnd = ttmEnd.minusYears(1);

        // 2. Query current total backlog (forward-looking from today)
        double currentBacklog = queryTotalBacklog(
                normalizedAsOfDate, sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 3. Query current TTM revenue for average calculation
        double currentTTMRevenue = queryActualRevenue(
                ttmStart, ttmEnd, sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double currentAvgMonthlyRevenue = currentTTMRevenue / 12.0;

        // 4. Calculate current coverage
        double currentCoverage = (currentAvgMonthlyRevenue > 0)
                ? currentBacklog / currentAvgMonthlyRevenue
                : 0.0;

        // 5. Query prior period data (for YoY comparison)
        double priorBacklog = queryTotalBacklog(
                normalizedAsOfDate.minusYears(1), sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorTTMRevenue = queryActualRevenue(
                priorTTMStart, priorTTMEnd, sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorAvgMonthlyRevenue = priorTTMRevenue / 12.0;
        double priorCoverage = (priorAvgMonthlyRevenue > 0)
                ? priorBacklog / priorAvgMonthlyRevenue
                : 0.0;

        // 6. Calculate change percentage
        double coverageChangePct = (priorCoverage > 0)
                ? ((currentCoverage - priorCoverage) / priorCoverage) * 100.0
                : 0.0;

        log.debugf("Backlog Coverage results: Current Backlog=%.2f, Avg Monthly Revenue=%.2f, Coverage=%.1f months, " +
                        "Prior Coverage=%.1f months, Change=%.1f%%",
                currentBacklog, currentAvgMonthlyRevenue, currentCoverage, priorCoverage, coverageChangePct);

        // 7. Return DTO
        return new BacklogCoverageDTO(
                currentBacklog,
                currentAvgMonthlyRevenue,
                currentCoverage,
                priorCoverage,
                coverageChangePct
        );
    }

    /**
     * Helper method: Query total backlog from fact_backlog for future months.
     * Backlog = sum of signed contract revenue for future delivery (excludes past months).
     * Only includes ACTIVE projects.
     */
    private double queryTotalBacklog(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Query backlog for future months only (delivery_month_key >= current month)
        String currentMonthKey = String.format("%04d%02d", asOfDate.getYear(), asOfDate.getMonthValue());

        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(backlog_revenue_dkk), 0.0) AS total_backlog " +
                        "FROM fact_backlog " +
                        "WHERE delivery_month_key COLLATE utf8mb4_general_ci >= :currentMonthKey " +
                        "AND project_status COLLATE utf8mb4_general_ci = 'ACTIVE' "
        );

        // Add optional filters
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND company_id IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("AND sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("AND contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            sql.append("AND client_id = :clientId ");
        }

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("currentMonthKey", currentMonthKey);

        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            query.setParameter("clientId", clientId);
        }

        return ((Number) query.getSingleResult()).doubleValue();
    }

    /**
     * Get Revenue per Billable FTE (TTM) KPI data.
     * Calculates revenue efficiency by dividing TTM revenue by average billable FTE count.
     * Formula: Revenue per FTE = Total TTM Revenue / Average Billable FTE Count
     *
     * @param fromDate Start date (not used for TTM but needed for consistency)
     * @param toDate End date (determines anchor date for TTM calculation, defaults to today)
     * @param sectors Multi-select sector filter (e.g., "PUBLIC", "HEALTH")
     * @param serviceLines Multi-select service line filter (e.g., "PM", "DEV")
     * @param contractTypes Multi-select contract type filter (e.g., "T&M", "FIXED")
     * @param clientId Single-select client filter
     * @param companyIds Multi-select company filter (UUIDs)
     * @return RevenuePerBillableFTETTMDTO with current/prior revenue per FTE, change %, and 12-month sparkline
     */
    public RevenuePerBillableFTETTMDTO getRevenuePerBillableFTETTM(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate normalizedToDate = (toDate != null) ? toDate : LocalDate.now();

        log.debugf("Revenue per Billable FTE (TTM) calculation: toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                normalizedToDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 1. Calculate TTM windows (current and prior)
        LocalDate anchorDate = normalizedToDate.withDayOfMonth(normalizedToDate.lengthOfMonth());
        LocalDate currentTTMStart = anchorDate.minusMonths(11).withDayOfMonth(1);
        LocalDate currentTTMEnd = anchorDate;
        LocalDate priorTTMStart = currentTTMStart.minusYears(1);
        LocalDate priorTTMEnd = currentTTMEnd.minusYears(1);

        log.debugf("TTM windows: Current [%s → %s], Prior [%s → %s]",
                currentTTMStart, currentTTMEnd, priorTTMStart, priorTTMEnd);

        // 2. Query revenue for current and prior TTM periods.
        // Revenue KPIs use fact_company_revenue_mat — company-total, no dimension filters.
        double currentTTMRevenue = queryCompanyRevenue(currentTTMStart, currentTTMEnd, companyIds);
        double priorTTMRevenue   = queryCompanyRevenue(priorTTMStart, priorTTMEnd, companyIds);

        // 3. Query average billable FTE for current and prior TTM periods
        double currentAvgBillableFTE = queryAvgBillableFTE(
                currentTTMStart, currentTTMEnd,
                serviceLines, companyIds
        );
        double priorAvgBillableFTE = queryAvgBillableFTE(
                priorTTMStart, priorTTMEnd,
                serviceLines, companyIds
        );

        log.debugf("Data queried: Current Revenue=%.2f, Current FTE=%.2f, Prior Revenue=%.2f, Prior FTE=%.2f",
                currentTTMRevenue, currentAvgBillableFTE, priorTTMRevenue, priorAvgBillableFTE);

        // 4. Calculate revenue per FTE
        double currentRevenuePerFTE = (currentAvgBillableFTE > 0)
                ? currentTTMRevenue / currentAvgBillableFTE
                : 0.0;
        double priorRevenuePerFTE = (priorAvgBillableFTE > 0)
                ? priorTTMRevenue / priorAvgBillableFTE
                : 0.0;

        // 5. Calculate percentage change
        double revenuePerFTEChangePct = (priorRevenuePerFTE > 0)
                ? ((currentRevenuePerFTE - priorRevenuePerFTE) / priorRevenuePerFTE) * 100.0
                : 0.0;

        log.debugf("Calculated: Current Rev/FTE=%.2f, Prior Rev/FTE=%.2f, Change=%.2f%%",
                currentRevenuePerFTE, priorRevenuePerFTE, revenuePerFTEChangePct);

        // 6. Get 12-month sparkline (monthly revenue per FTE values)
        double[] sparklineData = queryMonthlyRevenuePerFTE(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 7. Return DTO
        return new RevenuePerBillableFTETTMDTO(
                currentTTMRevenue,
                currentAvgBillableFTE,
                currentRevenuePerFTE,
                priorTTMRevenue,
                priorAvgBillableFTE,
                priorRevenuePerFTE,
                revenuePerFTEChangePct,
                sparklineData
        );
    }

    /**
     * Calculate Realization Rate % (Firm-wide) for trailing 12 months.
     * Measures how much of the expected billable value (at contracted rates) is actually invoiced.
     *
     * Formula: Realization Rate = (Actual Billed Revenue / Expected Revenue at Contract Rate) × 100
     *
     * Expected Revenue = Σ(workduration × rate) from work_full entries
     * Actual Revenue = Σ(recognized_revenue_dkk) from fact_project_financials
     *
     * A rate below 100% indicates value leakage through discounts, write-offs, or unbilled time.
     *
     * @param fromDate Start date (not used for TTM but needed for consistency)
     * @param toDate End date (determines anchor date for TTM calculation, defaults to today)
     * @param sectors Multi-select sector filter (e.g., "PUBLIC", "HEALTH")
     * @param serviceLines Multi-select service line filter (e.g., "PM", "DEV")
     * @param contractTypes Multi-select contract type filter (e.g., "T&M", "FIXED")
     * @param clientId Single-select client filter
     * @param companyIds Multi-select company filter (UUIDs)
     * @return RealizationRateDTO with current/prior realization %, change, and 12-month sparkline
     */
    public RealizationRateDTO getRealizationRate(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate normalizedToDate = (toDate != null) ? toDate : LocalDate.now();

        log.debugf("Realization Rate (TTM) calculation: toDate=%s, sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                normalizedToDate, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 1. Calculate TTM windows (current and prior)
        LocalDate anchorDate = normalizedToDate.withDayOfMonth(normalizedToDate.lengthOfMonth());
        LocalDate currentTTMStart = anchorDate.minusMonths(11).withDayOfMonth(1);
        LocalDate currentTTMEnd = anchorDate;
        LocalDate priorTTMStart = currentTTMStart.minusYears(1);
        LocalDate priorTTMEnd = currentTTMEnd.minusYears(1);

        log.debugf("TTM windows: Current [%s → %s], Prior [%s → %s]",
                currentTTMStart, currentTTMEnd, priorTTMStart, priorTTMEnd);

        // 2. Query expected revenue at contract rates from work_full
        double currentExpectedRevenue = queryExpectedRevenueAtContractRate(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorExpectedRevenue = queryExpectedRevenueAtContractRate(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 3. Query actual billed revenue from fact_project_financials
        double currentBilledRevenue = queryActualRevenue(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorBilledRevenue = queryActualRevenue(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        log.debugf("Data queried: Current Expected=%.2f, Current Billed=%.2f, Prior Expected=%.2f, Prior Billed=%.2f",
                currentExpectedRevenue, currentBilledRevenue, priorExpectedRevenue, priorBilledRevenue);

        // 4. Calculate realization rates
        double currentRealizationPercent = (currentExpectedRevenue > 0)
                ? (currentBilledRevenue / currentExpectedRevenue) * 100.0
                : 0.0;
        double priorRealizationPercent = (priorExpectedRevenue > 0)
                ? (priorBilledRevenue / priorExpectedRevenue) * 100.0
                : 0.0;

        // 5. Calculate percentage point change
        double realizationChangePct = currentRealizationPercent - priorRealizationPercent;

        log.debugf("Calculated: Current Realization=%.1f%%, Prior Realization=%.1f%%, Change=%.1f pp",
                currentRealizationPercent, priorRealizationPercent, realizationChangePct);

        // 6. Get 12-month sparkline (monthly realization % values)
        double[] sparklineData = queryMonthlyRealizationRate(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 7. Return DTO
        return new RealizationRateDTO(
                currentBilledRevenue,
                currentExpectedRevenue,
                currentRealizationPercent,
                priorRealizationPercent,
                realizationChangePct,
                sparklineData
        );
    }

    /**
     * Calculate Billable Utilization for the last 4 weeks (rolling window).
     * Measures operational efficiency by comparing billable hours to total available hours.
     *
     * Formula: Utilization % = (Billable Hours / Total Available Hours) × 100
     *
     * Note: This is a user-centric metric, so only practice (service line) and company
     * filters apply. Sector, contract type, and client filters are NOT applicable.
     *
     * @param asOfDate Anchor date for calculation (typically today)
     * @param serviceLines Multi-select practice filter (e.g., "PM", "DEV") - optional
     * @param companyIds Multi-select company filter (UUIDs) - optional
     * @return DTO with current and prior 4-week utilization data
     */
    public BillableUtilizationLast4WeeksDTO getBillableUtilizationLast4Weeks(
            LocalDate asOfDate,
            Set<String> serviceLines,
            Set<String> companyIds) {

        log.debugf("Calculating billable utilization (last 4 weeks): asOfDate=%s, serviceLines=%s, companies=%s",
                asOfDate, serviceLines, companyIds);

        // 1. Calculate date ranges
        // Current period available hours: Last 4 weeks (28 days) ending on asOfDate
        LocalDate currentAvailableEnd = asOfDate;
        LocalDate currentAvailableStart = asOfDate.minusDays(27);  // 28 days total (inclusive)

        // Current period billable hours: 35 days (includes 7-day grace period for late registration)
        // Grace period accommodates consultants who register time late
        LocalDate currentBillableStart = asOfDate.minusDays(34);  // 35 days total (7-day grace period)
        LocalDate currentBillableEnd = asOfDate;

        // Prior period available hours: 4 weeks before current period (days -55 to -28)
        LocalDate priorAvailableEnd = currentAvailableStart.minusDays(1);
        LocalDate priorAvailableStart = priorAvailableEnd.minusDays(27);  // 28 days total

        // Prior period billable hours: 35 days ending at priorAvailableEnd (with grace period)
        LocalDate priorBillableStart = priorAvailableEnd.minusDays(34);  // 35 days total
        LocalDate priorBillableEnd = priorAvailableEnd;

        log.debugf("Current period: billable=%s to %s (35d w/ grace), available=%s to %s (28d)",
                currentBillableStart, currentBillableEnd, currentAvailableStart, currentAvailableEnd);
        log.debugf("Prior period: billable=%s to %s (35d w/ grace), available=%s to %s (28d)",
                priorBillableStart, priorBillableEnd, priorAvailableStart, priorAvailableEnd);

        // 2. Query current period utilization (with grace period for billable hours)
        double[] currentPeriodData = queryUtilizationFor4Weeks(
                currentBillableStart, currentBillableEnd,  // Billable: 35 days
                currentAvailableStart, currentAvailableEnd,  // Available: 28 days
                serviceLines, companyIds
        );
        double currentBillableHours = currentPeriodData[0];
        double currentAvailableHours = currentPeriodData[1];

        // 3. Query prior period utilization (with grace period for billable hours)
        double[] priorPeriodData = queryUtilizationFor4Weeks(
                priorBillableStart, priorBillableEnd,  // Billable: 35 days
                priorAvailableStart, priorAvailableEnd,  // Available: 28 days
                serviceLines, companyIds
        );
        double priorBillableHours = priorPeriodData[0];
        double priorAvailableHours = priorPeriodData[1];

        // 4. Calculate utilization percentages
        double currentUtilizationPercent = (currentAvailableHours > 0)
                ? (currentBillableHours / currentAvailableHours) * 100.0
                : 0.0;

        double priorUtilizationPercent = (priorAvailableHours > 0)
                ? (priorBillableHours / priorAvailableHours) * 100.0
                : 0.0;

        // 5. Calculate percentage point change (not percentage change!)
        // Example: 82% → 85% = +3.0 percentage points
        double utilizationChangePct = currentUtilizationPercent - priorUtilizationPercent;

        log.infof("Billable utilization: current=%.1f%% (%.0f / %.0f hours), prior=%.1f%% (%.0f / %.0f hours), change=%+.1fpp",
                currentUtilizationPercent, currentBillableHours, currentAvailableHours,
                priorUtilizationPercent, priorBillableHours, priorAvailableHours,
                utilizationChangePct);

        // 6. Return DTO
        return new BillableUtilizationLast4WeeksDTO(
                currentBillableHours,
                currentAvailableHours,
                currentUtilizationPercent,
                priorBillableHours,
                priorAvailableHours,
                priorUtilizationPercent,
                utilizationChangePct
        );
    }

    /**
     * Helper method: Query billable and available hours for utilization calculation.
     * Uses fact_user_day as the single data source, consistent with /dashboard.
     *
     * IMPORTANT: Billable hours include a 7-day grace period to account for late time registration.
     * This prevents artificially low utilization when consultants register time after the fact.
     *
     * Example: For "last 4 weeks" ending Nov 27:
     * - Billable hours: Oct 24 - Nov 27 (35 days, includes 7-day grace period)
     * - Available hours: Oct 31 - Nov 27 (28 days, actual work period)
     *
     * @param billableFromDate Start date for billable hours query (inclusive, with grace period)
     * @param billableToDate End date for billable hours query (inclusive)
     * @param availableFromDate Start date for available hours query (inclusive, actual period)
     * @param availableToDate End date for available hours query (inclusive)
     * @param serviceLines Optional service line filter
     * @param companyIds Optional company filter
     * @return Array [billableHours, availableHours]
     */
    private double[] queryUtilizationFor4Weeks(
            LocalDate billableFromDate, LocalDate billableToDate,
            LocalDate availableFromDate, LocalDate availableToDate,
            Set<String> serviceLines, Set<String> companyIds) {

        boolean hasServiceLines = serviceLines != null && !serviceLines.isEmpty();
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();

        // Billable hours from fact_user_day (35-day window with grace period)
        StringBuilder billableSql = new StringBuilder();
        billableSql.append("SELECT COALESCE(SUM(bdd.registered_billable_hours), 0.0) AS total_billable_hours ");
        billableSql.append("FROM fact_user_day bdd ");
        if (hasServiceLines) {
            billableSql.append("JOIN user u ON u.uuid = bdd.useruuid ");
        }
        billableSql.append("WHERE bdd.document_date >= :billableFromDate ");
        billableSql.append("  AND bdd.document_date <= :billableToDate ");
        billableSql.append("  AND bdd.consultant_type = 'CONSULTANT' ");
        billableSql.append("  AND bdd.status_type = 'ACTIVE' ");
        if (hasServiceLines) {
            billableSql.append("  AND u.practice IN (:serviceLines) ");
        }
        if (hasCompanies) {
            billableSql.append("  AND bdd.companyuuid IN (:companyIds) ");
        }

        Query billableQuery = em.createNativeQuery(billableSql.toString());
        billableQuery.setParameter("billableFromDate", billableFromDate);
        billableQuery.setParameter("billableToDate", billableToDate);
        if (hasServiceLines) billableQuery.setParameter("serviceLines", serviceLines);
        if (hasCompanies) billableQuery.setParameter("companyIds", companyIds);

        double billableHours = ((Number) billableQuery.getSingleResult()).doubleValue();

        // Available hours from fact_user_day (28-day window)
        // Uses net_available_hours which already deducts all absence
        StringBuilder availableSql = new StringBuilder();
        availableSql.append("SELECT COALESCE(SUM(bdd.net_available_hours), 0.0) AS total_available_hours ");
        availableSql.append("FROM fact_user_day bdd ");
        if (hasServiceLines) {
            availableSql.append("JOIN user u ON u.uuid = bdd.useruuid ");
        }
        availableSql.append("WHERE bdd.document_date >= :availableFromDate ");
        availableSql.append("  AND bdd.document_date <= :availableToDate ");
        availableSql.append("  AND bdd.consultant_type = 'CONSULTANT' ");
        availableSql.append("  AND bdd.status_type = 'ACTIVE' ");
        if (hasServiceLines) {
            availableSql.append("  AND u.practice IN (:serviceLines) ");
        }
        if (hasCompanies) {
            availableSql.append("  AND bdd.companyuuid IN (:companyIds) ");
        }

        Query availableQuery = em.createNativeQuery(availableSql.toString());
        availableQuery.setParameter("availableFromDate", availableFromDate);
        availableQuery.setParameter("availableToDate", availableToDate);
        if (hasServiceLines) availableQuery.setParameter("serviceLines", serviceLines);
        if (hasCompanies) availableQuery.setParameter("companyIds", companyIds);

        double availableHours = ((Number) availableQuery.getSingleResult()).doubleValue();

        log.debugf("Utilization query: billable=%.0f (%s to %s), available=%.0f (%s to %s)",
                billableHours, billableFromDate, billableToDate, availableHours, availableFromDate, availableToDate);

        return new double[]{billableHours, availableHours};
    }


    /**
     * Helper method: Query actual revenue from fact_project_financials for a date range.
     * Supports optional filters for sectors, service lines, contract types, client, and companies.
     */
    private double queryActualRevenue(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        // Build dynamic SQL with deduplication to fix V118 double-counting issue
        // NOTE: V118 migration added companyuuid column, creating multiple rows per project-month
        // NOTE: Added COLLATE to fix collation mismatch between month_key column and DATE_FORMAT() function
        // Strategy: GROUP BY project_id + month_key, then MAX(revenue) to get single revenue value per project-month
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(max_revenue), 0.0) AS total_revenue " +
                        "FROM ( " +
                        "    SELECT " +
                        "        f.project_id, " +
                        "        f.month_key, " +
                        "        MAX(f.recognized_revenue_dkk) AS max_revenue " +
                        "    FROM fact_project_financials_mat f" +
                        "    WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters (following Chart A pattern exactly)
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            sql.append("    AND f.client_id = :clientId ");
        }

        sql.append("    GROUP BY f.project_id, f.month_key " +
                        ") AS deduplicated");

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("toKey", toDate.format(DateTimeFormatter.ofPattern("yyyyMM")));

        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            query.setParameter("clientId", clientId);
        }

        return ((Number) query.getSingleResult()).doubleValue();
    }

    /**
     * Helper method: Query total direct costs from GL ({@code finance_details}) for a date range.
     *
     * <p>Source changed from {@code fact_project_financials_mat} (work × salary estimation) to
     * actual GL entries classified as {@code cost_type = 'DIRECT_COSTS'} in
     * {@code accounting_accounts}. This gives the true booked amount rather than an estimate.
     *
     * <p><strong>Dimension filter note:</strong> GL-based direct costs have no project, sector,
     * service-line, or client attribution. Only the {@code companyIds} filter applies.
     * Dimension filters (sectors, serviceLines, contractTypes, clientId) are accepted for
     * signature compatibility but are intentionally ignored — GL costs are company-level only.
     *
     * @param fromDate   Start of range (inclusive, first-of-month semantics)
     * @param toDate     End of range (inclusive, last-of-month semantics)
     * @param sectors    Accepted for compatibility; not applied to GL-based query
     * @param serviceLines Accepted for compatibility; not applied to GL-based query
     * @param contractTypes Accepted for compatibility; not applied to GL-based query
     * @param clientId   Accepted for compatibility; not applied to GL-based query
     * @param companyIds Optional company UUID filter; null or empty = all companies
     * @return Sum of {@code ABS(amount)} for all DIRECT_COSTS GL entries in the period
     */
    private double queryActualCosts(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        String fromKey = fromDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String toKey   = toDate.format(DateTimeFormatter.ofPattern("yyyyMM"));

        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(ABS(fd.amount)), 0.0) " +
                "FROM finance_details fd " +
                "INNER JOIN accounting_accounts aa " +
                "    ON fd.accountnumber = aa.account_code " +
                "    AND fd.companyuuid  = aa.companyuuid " +
                "WHERE aa.cost_type = 'DIRECT_COSTS' " +
                "  AND DATE_FORMAT(fd.expensedate, '%Y%m') BETWEEN :fromKey AND :toKey " +
                "  AND fd.amount != 0 "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND fd.companyuuid IN (:companyIds) ");
        }

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        return ((Number) query.getSingleResult()).doubleValue();
    }

    // ============================================================================
    // Company Revenue helpers — fact_company_revenue_mat
    // ============================================================================

    /**
     * Query total company revenue from {@code fact_company_revenue_mat} for a date range.
     *
     * <p><strong>Design decision:</strong> Revenue KPIs always show company-total figures
     * regardless of dimension filters (sector, service line, contract type, client).
     * Only the {@code companyIds} filter applies here.  Dimension filters are intentionally
     * excluded because {@code fact_company_revenue_mat} has no project/sector/client
     * dimension — it aggregates at company × month grain.
     *
     * @param fromDate   Start of range (inclusive, first-of-month semantics)
     * @param toDate     End of range (inclusive, last-of-month semantics)
     * @param companyIds Optional company UUID filter; null or empty = all companies
     * @return Sum of {@code net_revenue_dkk} over the range
     */
    private double queryCompanyRevenue(
            LocalDate fromDate, LocalDate toDate,
            Set<String> companyIds) {

        String fromKey = fromDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String toKey   = toDate.format(DateTimeFormatter.ofPattern("yyyyMM"));

        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(r.net_revenue_dkk), 0.0) " +
                "FROM fact_company_revenue_mat r " +
                "WHERE r.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND r.company_id IN (:companyIds) ");
        }

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        return ((Number) query.getSingleResult()).doubleValue();
    }

    /**
     * Query monthly revenue from {@code fact_company_revenue_mat} for sparkline generation.
     *
     * <p>Returns an array of up to {@code expectedMonths} doubles (oldest → newest).
     * Months with no data remain zero.
     *
     * @param fromDate       Start of range (inclusive)
     * @param toDate         End of range (inclusive)
     * @param companyIds     Optional company UUID filter
     * @param expectedMonths Array size (typically 12)
     * @return Monthly revenue values
     */
    private double[] queryCompanyRevenueSparkline(
            LocalDate fromDate, LocalDate toDate,
            Set<String> companyIds, int expectedMonths) {

        String fromKey = fromDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String toKey   = toDate.format(DateTimeFormatter.ofPattern("yyyyMM"));

        StringBuilder sql = new StringBuilder(
                "SELECT r.month_key, COALESCE(SUM(r.net_revenue_dkk), 0.0) AS monthly_revenue " +
                "FROM fact_company_revenue_mat r " +
                "WHERE r.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND r.company_id IN (:companyIds) ");
        }
        sql.append("GROUP BY r.month_key ORDER BY r.month_key ASC");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        double[] sparkline = new double[expectedMonths];
        for (int i = 0; i < results.size() && i < expectedMonths; i++) {
            sparkline[i] = ((Number) results.get(i).get("monthly_revenue")).doubleValue();
        }
        return sparkline;
    }

    /**
     * Query monthly revenue from {@code fact_company_revenue_mat} as a Map.
     * Key: monthKey (YYYYMM), Value: net_revenue_dkk for that month.
     *
     * @param fromKey    Start month key inclusive (YYYYMM)
     * @param toKey      End month key inclusive (YYYYMM)
     * @param companyIds Optional company UUID filter
     * @return Map of monthKey → net_revenue_dkk
     */
    private java.util.Map<String, Double> queryCompanyRevenueByMonth(
            String fromKey, String toKey,
            Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT r.month_key, COALESCE(SUM(r.net_revenue_dkk), 0.0) AS monthly_revenue " +
                "FROM fact_company_revenue_mat r " +
                "WHERE r.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND r.company_id IN (:companyIds) ");
        }
        sql.append("GROUP BY r.month_key ORDER BY r.month_key ASC");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        java.util.Map<String, Double> result = new java.util.HashMap<>();
        for (Tuple row : rows) {
            result.put((String) row.get("month_key"), ((Number) row.get("monthly_revenue")).doubleValue());
        }
        return result;
    }

    /**
     * Calculate Client Retention Rate for 12-month periods.
     * Compares current 12-month window (dashboard-driven from toDate) to prior 12-month window.
     *
     * Formula: Retention Rate % = (Clients in Both Windows / Clients in Current Window) × 100
     *
     * @param fromDate Start of time filter range (for dashboard-driven context)
     * @param toDate End of time filter range (determines current 12m window anchor)
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param clientId Client filter (nullable - if provided, method returns empty DTO as this is portfolio-level metric)
     * @param companyIds Company filter (nullable)
     * @return ClientRetentionDTO with retention percentages, client counts, and trend comparison
     */
    public ClientRetentionDTO getClientRetention(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Portfolio-level metric: return empty DTO if specific client is selected
        if (clientId != null && !clientId.trim().isEmpty()) {
            return new ClientRetentionDTO(0, 0, 0.0, 0, 0, 0.0, 0.0);
        }

        // Calculate current 12-month window (dashboard-driven, ending at toDate)
        LocalDate currentWindowEnd = toDate.withDayOfMonth(1); // Normalize to month start
        LocalDate currentWindowStart = currentWindowEnd.minusMonths(11); // 12-month window

        // Calculate prior 12-month window (12 months before current window)
        LocalDate priorWindowEnd = currentWindowStart.minusDays(1).withDayOfMonth(1); // Month before current start
        LocalDate priorWindowStart = priorWindowEnd.minusMonths(11); // 12-month window

        // Query distinct clients in current window
        Set<String> currentWindowClients = queryDistinctClientsInWindow(
                currentWindowStart, currentWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        // Query distinct clients in prior window
        Set<String> priorWindowClients = queryDistinctClientsInWindow(
                priorWindowStart, priorWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        // Calculate retained clients (clients in BOTH windows - intersection)
        Set<String> retainedClients = new HashSet<>(currentWindowClients);
        retainedClients.retainAll(priorWindowClients); // INTERSECT operation

        // Calculate current retention percentage
        int currentClientCount = currentWindowClients.size();
        int retainedCount = retainedClients.size();
        double currentRetentionPercent = currentClientCount > 0
                ? (retainedCount * 100.0 / currentClientCount)
                : 0.0;

        // Calculate prior-to-prior window for trend comparison
        LocalDate priorPriorWindowEnd = priorWindowStart.minusDays(1).withDayOfMonth(1);
        LocalDate priorPriorWindowStart = priorPriorWindowEnd.minusMonths(11);

        Set<String> priorPriorWindowClients = queryDistinctClientsInWindow(
                priorPriorWindowStart, priorPriorWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        // Calculate retained clients for prior period (prior ∩ prior-to-prior)
        Set<String> priorRetainedClients = new HashSet<>(priorWindowClients);
        priorRetainedClients.retainAll(priorPriorWindowClients);

        int priorClientCount = priorWindowClients.size();
        int priorRetainedCount = priorRetainedClients.size();
        double priorRetentionPercent = priorClientCount > 0
                ? (priorRetainedCount * 100.0 / priorClientCount)
                : 0.0;

        // Calculate percentage point change
        double retentionChangePct = currentRetentionPercent - priorRetentionPercent;

        return new ClientRetentionDTO(
                currentClientCount,
                retainedCount,
                Math.round(currentRetentionPercent * 100.0) / 100.0, // Round to 2 decimals
                priorClientCount,
                priorRetainedCount,
                Math.round(priorRetentionPercent * 100.0) / 100.0,
                Math.round(retentionChangePct * 100.0) / 100.0
        );
    }

    /**
     * Calculate Repeat Business Share for fixed 24-month rolling window.
     * Measures percentage of revenue from clients with ≥2 distinct projects in 24-month window.
     *
     * Formula: Repeat Business Share % = (Repeat Client Revenue / Total Revenue) × 100
     *
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param clientId Client filter (nullable - if provided, method returns empty DTO as portfolio-level metric)
     * @param companyIds Company filter (nullable)
     * @return RepeatBusinessShareDTO with share percentages, revenue breakdowns, and 12-month sparkline
     */
    public RepeatBusinessShareDTO getRepeatBusinessShare(
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Portfolio-level metric: return empty DTO if specific client is selected
        if (clientId != null && !clientId.trim().isEmpty()) {
            return new RepeatBusinessShareDTO(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, new double[12]);
        }

        // Fixed 24-month window from today (not dashboard-driven)
        LocalDate today = LocalDate.now();
        LocalDate currentWindowStart = today.minusMonths(23).withDayOfMonth(1);  // 24 months including current
        LocalDate currentWindowEnd = today.withDayOfMonth(1);

        // Calculate current period repeat business metrics
        Map<String, Object> currentMetrics = queryRepeatBusinessMetrics(
                currentWindowStart, currentWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        double currentTotalRevenue = (double) currentMetrics.get("totalRevenue");
        double currentRepeatRevenue = (double) currentMetrics.get("repeatRevenue");
        double currentSharePercent = currentTotalRevenue > 0
                ? (currentRepeatRevenue / currentTotalRevenue * 100.0)
                : 0.0;

        // Calculate prior 24-month window for trend comparison
        LocalDate priorWindowStart = currentWindowStart.minusMonths(24);
        LocalDate priorWindowEnd = currentWindowStart.minusDays(1).withDayOfMonth(1);

        Map<String, Object> priorMetrics = queryRepeatBusinessMetrics(
                priorWindowStart, priorWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        double priorTotalRevenue = (double) priorMetrics.get("totalRevenue");
        double priorRepeatRevenue = (double) priorMetrics.get("repeatRevenue");
        double priorSharePercent = priorTotalRevenue > 0
                ? (priorRepeatRevenue / priorTotalRevenue * 100.0)
                : 0.0;

        // Calculate percentage point change
        double shareChangePct = currentSharePercent - priorSharePercent;

        // Generate 12-month sparkline (monthly repeat business share percentages)
        double[] sparklineData = queryRepeatBusinessSparkline(
                sectors, serviceLines, contractTypes, companyIds);

        return new RepeatBusinessShareDTO(
                Math.round(currentTotalRevenue * 100.0) / 100.0,
                Math.round(currentRepeatRevenue * 100.0) / 100.0,
                Math.round(currentSharePercent * 100.0) / 100.0,
                Math.round(priorTotalRevenue * 100.0) / 100.0,
                Math.round(priorRepeatRevenue * 100.0) / 100.0,
                Math.round(priorSharePercent * 100.0) / 100.0,
                Math.round(shareChangePct * 100.0) / 100.0,
                sparklineData
        );
    }

    /**
     * Helper method: Query repeat business metrics for a 24-month window.
     * Returns map with totalRevenue and repeatRevenue (from clients with ≥2 projects).
     *
     * Logic:
     * 1. Group by client_id, count distinct project_id, sum revenue
     * 2. Filter for clients with ≥2 projects (repeat clients)
     * 3. Sum their revenue = repeat client revenue
     *
     * @return Map with keys "totalRevenue" and "repeatRevenue"
     */
    private Map<String, Object> queryRepeatBusinessMetrics(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Build SQL to calculate total revenue and repeat client revenue in one query
        // NOTE: Added COLLATE to fix collation mismatch between month_key column and DATE_FORMAT() function
        StringBuilder sql = new StringBuilder(
                "WITH client_project_revenue AS ( " +
                        "    SELECT " +
                        "        f.client_id, " +
                        "        COUNT(DISTINCT f.project_id) AS project_count, " +
                        "        SUM(f.recognized_revenue_dkk) AS client_revenue " +
                        "    FROM fact_project_financials_mat f" +
                        "    WHERE f.month_key BETWEEN :fromKey AND :toKey " +
                        "      AND f.client_id IS NOT NULL " +
                        "      AND f.client_id NOT IN (:excludedClientIds) " +
                        "      AND f.recognized_revenue_dkk > 0 "
        );

        // Add optional filters to CTE
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND f.companyuuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("AND f.contract_type_id IN (:contractTypes) ");
        }

        sql.append(
                "    GROUP BY f.client_id " +
                        ") " +
                        "SELECT " +
                        "    COALESCE(SUM(client_revenue), 0.0) AS total_revenue, " +
                        "    COALESCE(SUM(CASE WHEN project_count >= 2 THEN client_revenue ELSE 0 END), 0.0) AS repeat_revenue " +
                        "FROM client_project_revenue"
        );

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("toKey", toDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }

        Object[] result = (Object[]) query.getSingleResult();
        double totalRevenue = ((Number) result[0]).doubleValue();
        double repeatRevenue = ((Number) result[1]).doubleValue();

        return Map.of("totalRevenue", totalRevenue, "repeatRevenue", repeatRevenue);
    }

    /**
     * Helper method: Generate 12-month sparkline for repeat business share percentage.
     * Returns array of 12 monthly repeat business share % values (oldest to newest).
     *
     * Each month uses a trailing 24-month window for consistency with the main metric.
     */
    private double[] queryRepeatBusinessSparkline(
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        double[] sparkline = new double[12];
        LocalDate today = LocalDate.now();

        // Calculate repeat business share for each of the last 12 months
        for (int i = 0; i < 12; i++) {
            // Each month uses trailing 24-month window ending at that month
            LocalDate monthEnd = today.minusMonths(11 - i).withDayOfMonth(1);  // Month i (0=oldest, 11=newest)
            LocalDate monthStart = monthEnd.minusMonths(23).withDayOfMonth(1);  // 24 months back

            Map<String, Object> metrics = queryRepeatBusinessMetrics(
                    monthStart, monthEnd,
                    sectors, serviceLines, contractTypes, companyIds);

            double totalRevenue = (double) metrics.get("totalRevenue");
            double repeatRevenue = (double) metrics.get("repeatRevenue");

            sparkline[i] = totalRevenue > 0
                    ? Math.round(repeatRevenue / totalRevenue * 100.0 * 100.0) / 100.0  // Round to 2 decimals
                    : 0.0;
        }

        return sparkline;
    }

    /**
     * Calculate Repeat Business Share by ACTIVE CONSULTANTS for fixed 24-month rolling window.
     * Measures percentage of revenue from clients with ≥2 distinct active consultants in 24-month window.
     *
     * Formula: Repeat Business Share % = (Repeat Client Revenue / Total Revenue) × 100
     *
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param clientId Client filter (nullable - if provided, method returns empty DTO as portfolio-level metric)
     * @param companyIds Company filter (nullable)
     * @return RepeatBusinessShareDTO with share percentages, revenue breakdowns, and 12-month sparkline
     */
    public RepeatBusinessShareDTO getRepeatBusinessShareByConsultants(
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Portfolio-level metric: return empty DTO if specific client is selected
        if (clientId != null && !clientId.trim().isEmpty()) {
            return new RepeatBusinessShareDTO(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, new double[12]);
        }

        // Fixed 24-month window from today (not dashboard-driven)
        LocalDate today = LocalDate.now();
        LocalDate currentWindowStart = today.minusMonths(23).withDayOfMonth(1);  // 24 months including current
        LocalDate currentWindowEnd = today.withDayOfMonth(1);

        // Calculate current period repeat business metrics
        Map<String, Object> currentMetrics = queryRepeatBusinessMetricsByConsultants(
                currentWindowStart, currentWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        double currentTotalRevenue = (double) currentMetrics.get("totalRevenue");
        double currentRepeatRevenue = (double) currentMetrics.get("repeatRevenue");
        double currentSharePercent = currentTotalRevenue > 0
                ? (currentRepeatRevenue / currentTotalRevenue * 100.0)
                : 0.0;

        // Calculate prior 24-month window for trend comparison
        LocalDate priorWindowStart = currentWindowStart.minusMonths(24);
        LocalDate priorWindowEnd = currentWindowStart.minusDays(1).withDayOfMonth(1);

        Map<String, Object> priorMetrics = queryRepeatBusinessMetricsByConsultants(
                priorWindowStart, priorWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);

        double priorTotalRevenue = (double) priorMetrics.get("totalRevenue");
        double priorRepeatRevenue = (double) priorMetrics.get("repeatRevenue");
        double priorSharePercent = priorTotalRevenue > 0
                ? (priorRepeatRevenue / priorTotalRevenue * 100.0)
                : 0.0;

        // Calculate percentage point change
        double shareChangePct = currentSharePercent - priorSharePercent;

        // Generate 12-month sparkline (monthly repeat business share percentages)
        double[] sparklineData = queryRepeatBusinessSparklineByConsultants(
                sectors, serviceLines, contractTypes, companyIds);

        return new RepeatBusinessShareDTO(
                Math.round(currentTotalRevenue * 100.0) / 100.0,
                Math.round(currentRepeatRevenue * 100.0) / 100.0,
                Math.round(currentSharePercent * 100.0) / 100.0,
                Math.round(priorTotalRevenue * 100.0) / 100.0,
                Math.round(priorRepeatRevenue * 100.0) / 100.0,
                Math.round(priorSharePercent * 100.0) / 100.0,
                Math.round(shareChangePct * 100.0) / 100.0,
                sparklineData
        );
    }

    /**
     * Helper method: Query repeat business metrics counting distinct ACTIVE CONSULTANTs per client.
     * Returns map with totalRevenue and repeatRevenue (from clients with ≥2 consultants).
     *
     * Logic:
     * 1. Join work → task → project → user → userstatus (temporal)
     * 2. Apply point-in-time status: latest status ≤ work.registered
     * 3. Filter: ConsultantType = CONSULTANT AND StatusType = ACTIVE
     * 4. Window function: COUNT(DISTINCT useruuid) OVER (PARTITION BY clientuuid)
     * 5. Count clients with ≥2 consultants as repeat clients
     * 6. Apply filters: sectors, serviceLines, contractTypes, companyIds
     *
     * @return Map with keys "totalRevenue" and "repeatRevenue"
     */
    private Map<String, Object> queryRepeatBusinessMetricsByConsultants(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "WITH client_consultants AS ( " +
                        "  SELECT " +
                        "    p.clientuuid AS client_id, " +
                        "    COUNT(DISTINCT w.useruuid) AS consultant_count " +   // <-- no window function
                        "  FROM work w " +
                        "  LEFT JOIN task t ON w.taskuuid = t.uuid " +
                        "  LEFT JOIN project p ON COALESCE(w.projectuuid, t.projectuuid) = p.uuid " +
                        "  WHERE p.clientuuid IS NOT NULL " +
                        "    AND w.registered BETWEEN :fromDate AND :toDate " +
                        "    AND w.workduration > 0 " +
                        "    AND p.clientuuid NOT IN (:excludedClientIds) " +
                        "    AND EXISTS ( " +
                        "      SELECT 1 " +
                        "      FROM userstatus us " +
                        "      WHERE us.useruuid = w.useruuid " +
                        "        AND us.statusdate = ( " +
                        "          SELECT MAX(us2.statusdate) " +
                        "          FROM userstatus us2 " +
                        "          WHERE us2.useruuid = w.useruuid " +
                        "            AND us2.statusdate <= w.registered " +
                        "        ) " +
                        "        AND us.type = 'CONSULTANT' " +
                        "        AND us.status = 'ACTIVE' " +
                        "    ) "
        );

        // Optional: restrict the "consultant participation" side to projects matching the same filters
        if ((sectors != null && !sectors.isEmpty()) ||
                (serviceLines != null && !serviceLines.isEmpty()) ||
                (contractTypes != null && !contractTypes.isEmpty()) ||
                (companyIds != null && !companyIds.isEmpty())) {

            sql.append(
                    "    AND p.uuid IN ( " +
                            "      SELECT DISTINCT f.project_id " +
                            "      FROM fact_project_financials_mat f" +
                            "      WHERE 1=1 "
            );

            if (companyIds != null && !companyIds.isEmpty()) {
                sql.append(" AND f.companyuuid IN (:companyIds) ");
            }
            if (sectors != null && !sectors.isEmpty()) {
                sql.append(" AND f.sector_id IN (:sectors) ");
            }
            if (serviceLines != null && !serviceLines.isEmpty()) {
                sql.append(" AND f.service_line_id IN (:serviceLines) ");
            }
            if (contractTypes != null && !contractTypes.isEmpty()) {
                sql.append(" AND f.contract_type_id IN (:contractTypes) ");
            }

            sql.append("    ) ");
        }

        sql.append(
                "  GROUP BY p.clientuuid " +
                        "), " +
                        "client_revenue AS ( " +
                        "  SELECT " +
                        "    f.client_id, " +
                        "    SUM(f.recognized_revenue_dkk) AS client_revenue " +
                        "  FROM fact_project_financials_mat f" +
                        "  WHERE f.month_key " +
                        "        BETWEEN :fromKey AND :toKey " +
                        "    AND f.client_id IS NOT NULL " +
                        "    AND f.client_id NOT IN (:excludedClientIds) " +
                        "    AND f.recognized_revenue_dkk > 0 "
        );

        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append(" AND f.companyuuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append(" AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append(" AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append(" AND f.contract_type_id IN (:contractTypes) ");
        }

        sql.append(
                "  GROUP BY f.client_id " +
                        ") " +
                        "SELECT " +
                        "  COALESCE(SUM(cr.client_revenue), 0.0) AS total_revenue, " +
                        "  COALESCE(SUM(CASE WHEN COALESCE(cc.consultant_count, 0) >= 2 THEN cr.client_revenue ELSE 0 END), 0.0) AS repeat_revenue " +
                        "FROM client_revenue cr " +
                        "LEFT JOIN client_consultants cc ON cr.client_id = cc.client_id"
        );

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);
        query.setParameter("fromKey", fromDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("toKey", toDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        if (companyIds != null && !companyIds.isEmpty()) query.setParameter("companyIds", companyIds);
        if (sectors != null && !sectors.isEmpty()) query.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty()) query.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) query.setParameter("contractTypes", contractTypes);

        Object[] result = (Object[]) query.getSingleResult();
        double totalRevenue = ((Number) result[0]).doubleValue();
        double repeatRevenue = ((Number) result[1]).doubleValue();

        return Map.of("totalRevenue", totalRevenue, "repeatRevenue", repeatRevenue);
    }


    /**
     * Helper method: Generate 12-month sparkline for repeat business share by consultants.
     * Returns array of 12 monthly repeat business share % values (oldest to newest).
     *
     * Each month uses a trailing 24-month window for consistency with the main metric.
     */
    private double[] queryRepeatBusinessSparklineByConsultants(
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        double[] sparkline = new double[12];
        LocalDate today = LocalDate.now();

        // Calculate repeat business share for each of the last 12 months
        for (int i = 0; i < 12; i++) {
            // Each month uses trailing 24-month window ending at that month
            LocalDate monthEnd = today.minusMonths(11 - i).withDayOfMonth(1);  // Month i (0=oldest, 11=newest)
            LocalDate monthStart = monthEnd.minusMonths(23).withDayOfMonth(1);  // 24 months back

            Map<String, Object> metrics = queryRepeatBusinessMetricsByConsultants(
                    monthStart, monthEnd,
                    sectors, serviceLines, contractTypes, companyIds);

            double totalRevenue = (double) metrics.get("totalRevenue");
            double repeatRevenue = (double) metrics.get("repeatRevenue");

            sparkline[i] = totalRevenue > 0
                    ? Math.round(repeatRevenue / totalRevenue * 100.0 * 100.0) / 100.0  // Round to 2 decimals
                    : 0.0;
        }

        return sparkline;
    }

    /**
     * Helper method: Query distinct clients with revenue in a time window.
     * Returns Set of client_id (UUID strings) who had recognized revenue > 0 in the window.
     *
     * @param fromDate Start of time window
     * @param toDate End of time window
     * @param sectors Sector filter (nullable)
     * @param serviceLines Service line filter (nullable)
     * @param contractTypes Contract type filter (nullable)
     * @param companyIds Company filter (nullable)
     * @return Set of distinct client UUIDs
     */
    private Set<String> queryDistinctClientsInWindow(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Build dynamic SQL for distinct client_id query
        // NOTE: Added COLLATE to fix collation mismatch between month_key column and DATE_FORMAT() function
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT f.client_id " +
                        "FROM fact_project_financials_mat f " +
                        "WHERE f.month_key BETWEEN :fromKey AND :toKey " +
                        "AND f.client_id IS NOT NULL " +
                        "AND f.recognized_revenue_dkk > 0 "
        );

        // Add optional filters (following established pattern)
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND f.companyuuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("AND f.contract_type_id IN (:contractTypes) ");
        }

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("toKey", toDate.format(DateTimeFormatter.ofPattern("yyyyMM")));

        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }

        // Convert result list to Set<String>
        @SuppressWarnings("unchecked")
        List<String> resultList = query.getResultList();
        return new HashSet<>(resultList);
    }

    /**
     * Helper method: Query monthly margin percentages for sparkline (last 12 months).
     * Returns array of 12 monthly margin % values, padded with zeros if months are missing.
     * Formula: ((revenue - cost) / revenue) × 100
     */
    private double[] queryMonthlyMarginPercent(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        String fromKey = fromDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String toKey   = toDate.format(DateTimeFormatter.ofPattern("yyyyMM"));

        // Revenue: fact_company_revenue_mat (company-total, companyIds filter only).
        // Revenue KPIs always show company-total — dimension filters do not apply.
        java.util.Map<String, Double> revenueByMonth = queryCompanyRevenueByMonth(fromKey, toKey, companyIds);

        // Cost: fact_project_financials_mat with all dimension filters (deduplication via MAX per project-month).
        StringBuilder costSql = new StringBuilder(
                "SELECT f.month_key, " +
                "       COALESCE(SUM(f.direct_delivery_cost_dkk), 0.0) AS monthly_cost " +
                "FROM ( " +
                "    SELECT f2.project_id, f2.month_key, MAX(f2.direct_delivery_cost_dkk) AS direct_delivery_cost_dkk " +
                "    FROM fact_project_financials_mat f2 " +
                "    WHERE f2.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            costSql.append("    AND f2.companyuuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            costSql.append("    AND f2.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            costSql.append("    AND f2.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            costSql.append("    AND f2.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            costSql.append("    AND f2.client_id = :clientId ");
        }
        costSql.append(
                "    GROUP BY f2.project_id, f2.month_key " +
                ") AS f " +
                "GROUP BY f.month_key ORDER BY f.month_key ASC"
        );

        Query costQuery = em.createNativeQuery(costSql.toString(), Tuple.class);
        costQuery.setParameter("fromKey", fromKey);
        costQuery.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            costQuery.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            costQuery.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            costQuery.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            costQuery.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            costQuery.setParameter("clientId", clientId);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> costResults = costQuery.getResultList();

        java.util.Map<String, Double> costByMonth = new java.util.HashMap<>();
        for (Tuple row : costResults) {
            costByMonth.put((String) row.get("month_key"), ((Number) row.get("monthly_cost")).doubleValue());
        }

        // Build 12-element sparkline ordered by month_key.
        // Collect all months present in either map, sort, then fill array.
        java.util.Set<String> allMonths = new java.util.TreeSet<>(revenueByMonth.keySet());
        allMonths.addAll(costByMonth.keySet());
        List<String> sortedMonths = new java.util.ArrayList<>(allMonths);

        double[] sparklineData = new double[12];
        for (int i = 0; i < sortedMonths.size() && i < 12; i++) {
            String mk = sortedMonths.get(i);
            double monthlyRevenue = revenueByMonth.getOrDefault(mk, 0.0);
            double monthlyCost    = costByMonth.getOrDefault(mk, 0.0);
            sparklineData[i] = (monthlyRevenue > 0)
                    ? ((monthlyRevenue - monthlyCost) / monthlyRevenue) * 100.0
                    : 0.0;
        }

        return sparklineData;
    }

    /**
     * Helper method: Query budget revenue from fact_revenue_budget for a date range.
     * Uses fiscal year filtering for efficient querying.
     * Note: Client filter does NOT apply to budget (budget is set at company/practice/sector/contract level).
     */
    private double queryBudgetRevenue(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        // Calculate fiscal year from fromDate
        int fiscalYear = fromDate.getMonthValue() >= 7 ? fromDate.getYear() : fromDate.getYear() - 1;

        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(b.budget_revenue_dkk), 0.0) AS total_budget " +
                        "FROM fact_revenue_budget_mat b " +
                        "WHERE b.fiscal_year = :fiscalYear " +
                        "AND b.month_key BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters (same pattern as actual revenue)
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND b.company_id IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("AND b.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND b.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("AND b.contract_type_id IN (:contractTypes) ");
        }
        // Note: fact_revenue_budget doesn't have client_id dimension (budget is by company/practice/sector/contract)
        // Client filtering only applies to actual revenue

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fiscalYear", fiscalYear);
        query.setParameter("fromKey", fromDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("toKey", toDate.format(DateTimeFormatter.ofPattern("yyyyMM")));

        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }

        return ((Number) query.getSingleResult()).doubleValue();
    }

    /**
     * Helper method: Query monthly actual revenue for sparkline (last 12 months).
     * Returns array of 12 monthly revenue values, padded with zeros if months are missing.
     */
    private double[] querySparklineRevenue(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        // Query monthly actual revenue for last 12 months
        StringBuilder sql = new StringBuilder(
                "SELECT f.month_key, COALESCE(SUM(f.recognized_revenue_dkk), 0.0) AS monthly_revenue " +
                        "FROM fact_project_financials_mat f " +
                        "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters (same as actual revenue query)
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND f.companyuuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("AND f.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            sql.append("AND f.client_id = :clientId ");
        }

        sql.append("GROUP BY f.month_key ORDER BY f.month_key ASC");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromDate.format(DateTimeFormatter.ofPattern("yyyyMM")));
        query.setParameter("toKey", toDate.format(DateTimeFormatter.ofPattern("yyyyMM")));

        // Set parameters (same pattern as actual revenue)
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            query.setParameter("clientId", clientId);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        // Convert to double array (12 elements, pad with zeros if missing months)
        double[] sparklineData = new double[12];
        for (int i = 0; i < results.size() && i < 12; i++) {
            Tuple tuple = results.get(i);
            sparklineData[i] = ((Number) tuple.get("monthly_revenue")).doubleValue();
        }

        return sparklineData;
    }

    /**
     * Retrieves monthly pipeline, backlog, and target trend data for Chart C.
     * Queries three views and merges results by month_key:
     * - fact_pipeline: weighted pipeline (excludes WON, LOST stages)
     * - fact_backlog: signed backlog from active contracts
     * - fact_revenue_budget: revenue targets (no client filter supported)
     *
     * @param fromDate Start date (inclusive, first of month for horizon)
     * @param toDate End date (inclusive, typically 6 months forward)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param clientId Single-select client filter (budget excluded when set)
     * @param companyIds Multi-select company filter (UUIDs)
     * @return List of monthly data points sorted chronologically
     */
    public List<MonthlyPipelineBacklogDTO> getPipelineBacklogTrend(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Normalize dates to month boundaries
        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1).plusMonths(1).minusDays(1)
                : LocalDate.now().plusMonths(6).withDayOfMonth(1).minusDays(1);

        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("getPipelineBacklogTrend: from=%s (%s), to=%s (%s), sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                normalizedFromDate, fromMonthKey, normalizedToDate, toMonthKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 1. Query pipeline data (weighted_pipeline_dkk from fact_pipeline)
        java.util.Map<String, Double> pipelineByMonth = queryPipelineData(
                fromMonthKey, toMonthKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 2. Query backlog data (backlog_revenue_dkk from fact_backlog)
        java.util.Map<String, Double> backlogByMonth = queryBacklogData(
                fromMonthKey, toMonthKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 3. Query budget/target data (no client filter - budget has no client granularity)
        // Only query if no client filter is active
        java.util.Map<String, Double> targetByMonth;
        if (clientId == null || clientId.isBlank()) {
            targetByMonth = queryTargetData(fromMonthKey, toMonthKey, sectors, serviceLines, contractTypes, companyIds);
        } else {
            targetByMonth = new java.util.HashMap<>(); // Empty - hide target when client filter active
        }

        // 4. Merge results by month_key
        java.util.Set<String> allMonthKeys = new java.util.TreeSet<>();
        allMonthKeys.addAll(pipelineByMonth.keySet());
        allMonthKeys.addAll(backlogByMonth.keySet());
        allMonthKeys.addAll(targetByMonth.keySet());

        List<MonthlyPipelineBacklogDTO> results = new ArrayList<>();
        for (String monthKey : allMonthKeys) {
            int year = Integer.parseInt(monthKey.substring(0, 4));
            int monthNumber = Integer.parseInt(monthKey.substring(4, 6));
            String monthLabel = formatMonthLabel(year, monthNumber);

            double pipeline = pipelineByMonth.getOrDefault(monthKey, 0.0);
            double backlog = backlogByMonth.getOrDefault(monthKey, 0.0);
            double target = targetByMonth.getOrDefault(monthKey, 0.0);

            // Calculate coverage percentages (null if target is zero)
            Double bookedCoveragePct = null;
            Double totalCoveragePct = null;
            if (target > 0) {
                bookedCoveragePct = (backlog / target) * 100.0;
                totalCoveragePct = ((backlog + pipeline) / target) * 100.0;
            }

            MonthlyPipelineBacklogDTO dto = new MonthlyPipelineBacklogDTO(
                    monthKey,
                    year,
                    monthNumber,
                    monthLabel,
                    pipeline,
                    backlog,
                    target,
                    bookedCoveragePct,
                    totalCoveragePct
            );
            results.add(dto);

            log.debugf("Month %s: pipeline=%.2f, backlog=%.2f, target=%.2f, bookedCov=%.1f%%, totalCov=%.1f%%",
                    monthKey, pipeline, backlog, target,
                    bookedCoveragePct != null ? bookedCoveragePct : 0,
                    totalCoveragePct != null ? totalCoveragePct : 0);
        }

        return results;
    }

    /**
     * Query weighted pipeline from fact_pipeline view.
     * Excludes WON and LOST stages (uses NOT IN to avoid collation issues).
     */
    private java.util.Map<String, Double> queryPipelineData(
            String fromMonthKey, String toMonthKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT expected_revenue_month_key AS month_key, " +
                        "       SUM(weighted_pipeline_dkk) AS pipeline " +
                        "FROM fact_pipeline " +
                        "WHERE expected_revenue_month_key COLLATE utf8mb4_general_ci BETWEEN :fromMonthKey AND :toMonthKey " +
                        "  AND stage_category COLLATE utf8mb4_general_ci NOT IN ('WON', 'LOST') "
        );

        // Add optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("  AND sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("  AND service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("  AND contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.isBlank()) {
            sql.append("  AND client_id = :clientId ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND company_id IN (:companyIds) ");
        }

        sql.append("GROUP BY expected_revenue_month_key ORDER BY expected_revenue_month_key");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.isBlank()) {
            query.setParameter("clientId", clientId);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        java.util.Map<String, Double> dataByMonth = new java.util.HashMap<>();
        for (Tuple row : results) {
            String monthKey = (String) row.get("month_key");
            double pipeline = ((Number) row.get("pipeline")).doubleValue();
            dataByMonth.put(monthKey, pipeline);
        }

        log.debugf("Pipeline query returned %d months", dataByMonth.size());
        return dataByMonth;
    }

    /**
     * Query backlog from fact_backlog view.
     * Only includes ACTIVE project status.
     */
    private java.util.Map<String, Double> queryBacklogData(
            String fromMonthKey, String toMonthKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT delivery_month_key AS month_key, " +
                        "       SUM(backlog_revenue_dkk) AS backlog " +
                        "FROM fact_backlog " +
                        "WHERE delivery_month_key COLLATE utf8mb4_general_ci BETWEEN :fromMonthKey AND :toMonthKey " +
                        "  AND project_status COLLATE utf8mb4_general_ci = 'ACTIVE' "
        );

        // Add optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("  AND sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("  AND service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("  AND contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.isBlank()) {
            sql.append("  AND client_id = :clientId ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND company_id IN (:companyIds) ");
        }

        sql.append("GROUP BY delivery_month_key ORDER BY delivery_month_key");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.isBlank()) {
            query.setParameter("clientId", clientId);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        java.util.Map<String, Double> dataByMonth = new java.util.HashMap<>();
        for (Tuple row : results) {
            String monthKey = (String) row.get("month_key");
            double backlog = ((Number) row.get("backlog")).doubleValue();
            dataByMonth.put(monthKey, backlog);
        }

        log.debugf("Backlog query returned %d months", dataByMonth.size());
        return dataByMonth;
    }

    /**
     * Query revenue target from fact_revenue_budget view.
     * Note: Budget has no client-level granularity.
     */
    private java.util.Map<String, Double> queryTargetData(
            String fromMonthKey, String toMonthKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT month_key, " +
                        "       SUM(budget_revenue_dkk) AS target " +
                        "FROM fact_revenue_budget_mat " +
                        "WHERE month_key BETWEEN :fromMonthKey AND :toMonthKey "
        );

        // Add optional filters (no client filter - budget has no client dimension)
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("  AND sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("  AND service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("  AND contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND company_id IN (:companyIds) ");
        }

        sql.append("GROUP BY month_key ORDER BY month_key");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        java.util.Map<String, Double> dataByMonth = new java.util.HashMap<>();
        for (Tuple row : results) {
            String monthKey = (String) row.get("month_key");
            double target = ((Number) row.get("target")).doubleValue();
            dataByMonth.put(monthKey, target);
        }

        log.debugf("Target/budget query returned %d months", dataByMonth.size());
        return dataByMonth;
    }

    /**
     * Helper method: Query average billable FTE from fact_user_utilization for a date range.
     * FTE = Full-Time Equivalent (1 FTE = 160.33 hours/month)
     * Calculates average of monthly billable FTE counts across the specified period.
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @param serviceLines Multi-select service line filter (optional)
     * @param companyIds Multi-select company filter (optional)
     * @return Average billable FTE count
     */
    private double queryAvgBillableFTE(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> serviceLines,
            Set<String> companyIds) {

        // Query work_full to calculate monthly billable FTE, then average across months
        // FTE = billable_hours / 160.33 (standard monthly hours)
        StringBuilder sql = new StringBuilder(
                "SELECT AVG(monthly_fte) AS avg_billable_fte FROM ( " +
                "  SELECT " +
                "    YEAR(w.registered) AS year_val, " +
                "    MONTH(w.registered) AS month_val, " +
                "    SUM(CASE WHEN w.rate > 0 AND w.workduration > 0 THEN w.workduration ELSE 0 END) / 160.33 AS monthly_fte " +
                "  FROM work_full w " +
                "  WHERE w.registered >= :fromDate " +
                "    AND w.registered <= :toDate " +
                "    AND w.type = 'CONSULTANT' " +
                "    AND w.rate > 0 AND w.workduration > 0 "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.practice IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND w.consultant_company_uuid IN (:companyIds) ");
        }

        sql.append("  GROUP BY YEAR(w.registered), MONTH(w.registered) " +
                   ") AS monthly_data");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Number result = (Number) query.getSingleResult();
        return (result != null) ? result.doubleValue() : 0.0;
    }

    /**
     * Helper method: Query monthly revenue per FTE for sparkline visualization.
     * Returns array of 12 monthly values showing revenue per FTE trend.
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @param sectors Multi-select sector filter (optional)
     * @param serviceLines Multi-select service line filter (optional)
     * @param contractTypes Multi-select contract type filter (optional)
     * @param clientId Single-select client filter (optional)
     * @param companyIds Multi-select company filter (optional)
     * @return Array of 12 monthly revenue per FTE values
     */
    private double[] queryMonthlyRevenuePerFTE(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        String fromKey = String.format("%04d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toKey = String.format("%04d%02d", toDate.getYear(), toDate.getMonthValue());

        // Query 1: Monthly revenue from fact_company_revenue_mat (company-total).
        // Revenue KPIs always show company-total — dimension filters do not apply.
        java.util.Map<String, Double> revenueByMonth = queryCompanyRevenueByMonth(fromKey, toKey, companyIds);

        // Query 2: Monthly billable FTE from work_full (accurate source for billable work)
        // FTE = billable_hours / 160.33 per month
        StringBuilder fteSql = new StringBuilder(
                "SELECT " +
                "  CONCAT(YEAR(w.registered), LPAD(MONTH(w.registered), 2, '0')) AS month_key, " +
                "  SUM(CASE WHEN w.rate > 0 AND w.workduration > 0 THEN w.workduration ELSE 0 END) / 160.33 AS monthly_fte " +
                "FROM work_full w " +
                "WHERE w.registered >= :fromDate " +
                "  AND w.registered <= :toDate " +
                "  AND w.type = 'CONSULTANT' " +
                "  AND w.rate > 0 AND w.workduration > 0 "
        );

        if (serviceLines != null && !serviceLines.isEmpty()) {
            fteSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.practice IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            fteSql.append("AND w.consultant_company_uuid IN (:companyIds) ");
        }

        fteSql.append("GROUP BY YEAR(w.registered), MONTH(w.registered) ORDER BY month_key");

        Query fteQuery = em.createNativeQuery(fteSql.toString(), Tuple.class);
        fteQuery.setParameter("fromDate", fromDate);
        fteQuery.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            fteQuery.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            fteQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> fteResults = fteQuery.getResultList();

        // Build FTE map for easy lookup
        java.util.Map<String, Double> fteByMonth = new java.util.HashMap<>();
        for (Tuple row : fteResults) {
            String monthKey = (String) row.get(0);
            double fte = ((Number) row.get(1)).doubleValue();
            fteByMonth.put(monthKey, fte);
        }

        // Calculate revenue per FTE for each month
        double[] sparklineData = new double[12];
        YearMonth currentMonth = YearMonth.from(fromDate);

        for (int i = 0; i < 12; i++) {
            String monthKey = currentMonth.format(DateTimeFormatter.ofPattern("yyyyMM"));
            double monthRevenue = revenueByMonth.getOrDefault(monthKey, 0.0);
            double monthFTE = fteByMonth.getOrDefault(monthKey, 0.0);

            sparklineData[i] = (monthFTE > 0) ? monthRevenue / monthFTE : 0.0;
            currentMonth = currentMonth.plusMonths(1);
        }

        return sparklineData;
    }

    /**
     * Helper method: Query expected revenue at contract rates from work_full.
     * Expected Revenue = Σ(workduration × rate) for billable work entries.
     *
     * This measures the full contracted value of work performed, before any
     * discounts, write-offs, or unbilled time adjustments.
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @param sectors Multi-select sector filter (optional)
     * @param serviceLines Multi-select service line filter (optional)
     * @param contractTypes Multi-select contract type filter (optional)
     * @param clientId Single-select client filter (optional)
     * @param companyIds Multi-select company filter (optional)
     * @return Total expected revenue at contract rates for the period
     */
    private double queryExpectedRevenueAtContractRate(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        // Build dynamic SQL querying work_full for expected revenue
        // Expected revenue = Σ(workduration × rate) where rate > 0 and workduration > 0
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(CASE WHEN w.rate > 0 AND w.workduration > 0 THEN w.workduration * w.rate ELSE 0 END), 0.0) AS expected_revenue " +
                "FROM work_full w " +
                "WHERE w.registered >= :fromDate " +
                "  AND w.registered <= :toDate " +
                "  AND w.type = 'CONSULTANT' "
        );

        // Add optional filters
        // Note: work_full doesn't have sector_id or contract_type_id directly,
        // so we filter by service line (consultant's practice) and company
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.practice IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND w.consultant_company_uuid IN (:companyIds) ");
        }
        // For project-level filters (sectors, contractTypes, clientId), we need to join with projects
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("AND EXISTS (SELECT 1 FROM projects p JOIN clients c ON p.clientuuid = c.uuid WHERE p.uuid = w.projectuuid AND c.segment IN (:sectors)) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("AND EXISTS (SELECT 1 FROM projects p JOIN contracts con ON p.uuid = con.projectuuid WHERE p.uuid = w.projectuuid AND con.contract_type IN (:contractTypes)) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            sql.append("AND EXISTS (SELECT 1 FROM projects p WHERE p.uuid = w.projectuuid AND p.clientuuid = :clientId) ");
        }

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            query.setParameter("clientId", clientId);
        }

        return ((Number) query.getSingleResult()).doubleValue();
    }

    /**
     * Helper method: Query monthly realization rate for sparkline visualization.
     * Returns array of 12 monthly realization percentages.
     *
     * Realization Rate = (Actual Billed Revenue / Expected Revenue at Contract Rate) × 100
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @param sectors Multi-select sector filter (optional)
     * @param serviceLines Multi-select service line filter (optional)
     * @param contractTypes Multi-select contract type filter (optional)
     * @param clientId Single-select client filter (optional)
     * @param companyIds Multi-select company filter (optional)
     * @return Array of 12 monthly realization rate percentages
     */
    private double[] queryMonthlyRealizationRate(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        String fromKey = String.format("%04d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toKey = String.format("%04d%02d", toDate.getYear(), toDate.getMonthValue());

        // Query 1: Monthly expected revenue from work_full
        StringBuilder expectedSql = new StringBuilder(
                "SELECT " +
                "  CONCAT(YEAR(w.registered), LPAD(MONTH(w.registered), 2, '0')) AS month_key, " +
                "  COALESCE(SUM(CASE WHEN w.rate > 0 AND w.workduration > 0 THEN w.workduration * w.rate ELSE 0 END), 0.0) AS expected_revenue " +
                "FROM work_full w " +
                "WHERE w.registered >= :fromDate " +
                "  AND w.registered <= :toDate " +
                "  AND w.type = 'CONSULTANT' "
        );

        if (serviceLines != null && !serviceLines.isEmpty()) {
            expectedSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.practice IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            expectedSql.append("AND w.consultant_company_uuid IN (:companyIds) ");
        }
        if (sectors != null && !sectors.isEmpty()) {
            expectedSql.append("AND EXISTS (SELECT 1 FROM projects p JOIN clients c ON p.clientuuid = c.uuid WHERE p.uuid = w.projectuuid AND c.segment IN (:sectors)) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            expectedSql.append("AND EXISTS (SELECT 1 FROM projects p JOIN contracts con ON p.uuid = con.projectuuid WHERE p.uuid = w.projectuuid AND con.contract_type IN (:contractTypes)) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            expectedSql.append("AND EXISTS (SELECT 1 FROM projects p WHERE p.uuid = w.projectuuid AND p.clientuuid = :clientId) ");
        }

        expectedSql.append("GROUP BY YEAR(w.registered), MONTH(w.registered) ORDER BY month_key");

        Query expectedQuery = em.createNativeQuery(expectedSql.toString(), Tuple.class);
        expectedQuery.setParameter("fromDate", fromDate);
        expectedQuery.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            expectedQuery.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            expectedQuery.setParameter("companyIds", companyIds);
        }
        if (sectors != null && !sectors.isEmpty()) {
            expectedQuery.setParameter("sectors", sectors);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            expectedQuery.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            expectedQuery.setParameter("clientId", clientId);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> expectedResults = expectedQuery.getResultList();

        // Query 2: Monthly actual revenue from fact_project_financials
        StringBuilder actualSql = new StringBuilder(
                "SELECT f.month_key, SUM(f.recognized_revenue_dkk) AS actual_revenue " +
                "FROM fact_project_financials_mat f " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );

        if (sectors != null && !sectors.isEmpty()) {
            actualSql.append("AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            actualSql.append("AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            actualSql.append("AND f.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            actualSql.append("AND f.client_id = :clientId ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            actualSql.append("AND f.companyuuid IN (:companyIds) ");
        }

        actualSql.append("GROUP BY f.month_key ORDER BY f.month_key");

        Query actualQuery = em.createNativeQuery(actualSql.toString(), Tuple.class);
        actualQuery.setParameter("fromKey", fromKey);
        actualQuery.setParameter("toKey", toKey);

        if (sectors != null && !sectors.isEmpty()) {
            actualQuery.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            actualQuery.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            actualQuery.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            actualQuery.setParameter("clientId", clientId);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            actualQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> actualResults = actualQuery.getResultList();

        // Build maps for easy lookup
        java.util.Map<String, Double> expectedByMonth = new java.util.HashMap<>();
        for (Tuple row : expectedResults) {
            String monthKey = (String) row.get(0);
            double expected = ((Number) row.get(1)).doubleValue();
            expectedByMonth.put(monthKey, expected);
        }

        java.util.Map<String, Double> actualByMonth = new java.util.HashMap<>();
        for (Tuple row : actualResults) {
            String monthKey = (String) row.get(0);
            double actual = ((Number) row.get(1)).doubleValue();
            actualByMonth.put(monthKey, actual);
        }

        // Calculate realization rate for each month
        double[] sparklineData = new double[12];
        YearMonth currentMonth = YearMonth.from(fromDate);

        for (int i = 0; i < 12; i++) {
            String monthKey = currentMonth.format(DateTimeFormatter.ofPattern("yyyyMM"));
            double monthExpected = expectedByMonth.getOrDefault(monthKey, 0.0);
            double monthActual = actualByMonth.getOrDefault(monthKey, 0.0);

            // Realization Rate = (Actual / Expected) × 100
            sparklineData[i] = (monthExpected > 0) ? (monthActual / monthExpected) * 100.0 : 0.0;
            currentMonth = currentMonth.plusMonths(1);
        }

        return sparklineData;
    }

    /**
     * Get Top 5 Clients' Revenue Share KPI data.
     * Measures the percentage of TTM revenue from the 5 largest clients,
     * indicating revenue concentration risk and client diversification health.
     *
     * Formula: Top 5 Share % = (Top 5 Clients Revenue / Total Revenue) × 100
     * Status Logic: INVERTED - Lower concentration is better (less risk)
     *
     * Portfolio-level metric: Returns empty DTO if specific client is selected.
     *
     * @param fromDate Start date of TTM window (inclusive)
     * @param toDate End date of TTM window (inclusive)
     * @param sectors Set of sector IDs to filter by (optional)
     * @param serviceLines Set of service line IDs to filter by (optional)
     * @param contractTypes Set of contract type IDs to filter by (optional)
     * @param clientId Client UUID to filter by (if provided, returns empty DTO - portfolio override)
     * @param companyIds Set of company UUIDs to filter by (optional)
     * @return Top5ClientsShareDTO with current/prior percentages and revenues
     */
    public Top5ClientsShareDTO getTop5ClientsShare(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId,
            Set<String> companyIds) {

        // Portfolio-level metric: Return empty DTO if specific client selected
        if (clientId != null && !clientId.trim().isEmpty()) {
            return new Top5ClientsShareDTO(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        // 1. Calculate current TTM window (dashboard-driven)
        LocalDate currentWindowEnd = toDate.withDayOfMonth(1);
        LocalDate currentWindowStart = currentWindowEnd.minusMonths(11);

        // 2. Calculate prior TTM window (12 months before current window)
        LocalDate priorWindowEnd = currentWindowStart.minusDays(1).withDayOfMonth(1);
        LocalDate priorWindowStart = priorWindowEnd.minusMonths(11);

        // 3. Query current TTM metrics (top 5 clients revenue)
        Map<String, Object> currentMetrics = queryTop5ClientsMetrics(
                currentWindowStart, currentWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);
        double currentTotalRevenue = (double) currentMetrics.get("totalRevenue");
        double currentTop5Revenue = (double) currentMetrics.get("top5Revenue");

        // 4. Query prior TTM metrics (for trend comparison)
        Map<String, Object> priorMetrics = queryTop5ClientsMetrics(
                priorWindowStart, priorWindowEnd,
                sectors, serviceLines, contractTypes, companyIds);
        double priorTotalRevenue = (double) priorMetrics.get("totalRevenue");
        double priorTop5Revenue = (double) priorMetrics.get("top5Revenue");

        // 5. Calculate percentages
        double currentTop5SharePercent = currentTotalRevenue > 0
                ? Math.round(currentTop5Revenue / currentTotalRevenue * 100.0 * 100.0) / 100.0
                : 0.0;
        double priorTop5SharePercent = priorTotalRevenue > 0
                ? Math.round(priorTop5Revenue / priorTotalRevenue * 100.0 * 100.0) / 100.0
                : 0.0;

        // 6. Calculate change (percentage points)
        double top5ShareChangePct = Math.round((currentTop5SharePercent - priorTop5SharePercent) * 100.0) / 100.0;

        return new Top5ClientsShareDTO(
                currentTotalRevenue,
                currentTop5Revenue,
                currentTop5SharePercent,
                priorTotalRevenue,
                priorTop5Revenue,
                priorTop5SharePercent,
                top5ShareChangePct
        );
    }

    /**
     * Helper: Query top 5 clients revenue metrics for a given time window.
     * Calculates total revenue and revenue from top 5 clients by revenue.
     *
     * @return Map with keys: "totalRevenue" (double), "top5Revenue" (double)
     */
    private Map<String, Object> queryTop5ClientsMetrics(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, Set<String> companyIds) {

        String fromKey = fromDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String toKey = toDate.format(DateTimeFormatter.ofPattern("yyyyMM"));

        // Build SQL query with CTE to rank clients by revenue
        StringBuilder sql = new StringBuilder(
                "WITH client_revenue AS ( " +
                        "    SELECT " +
                        "        f.client_id, " +
                        "        SUM(f.recognized_revenue_dkk) AS client_revenue " +
                        "    FROM fact_project_financials_mat f" +
                        "    WHERE f.month_key BETWEEN :fromKey AND :toKey " +
                        "      AND f.client_id IS NOT NULL " +
                        "      AND f.client_id NOT IN (:excludedClientIds) " +
                        "      AND f.recognized_revenue_dkk > 0 "
        );

        // Add filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append(" AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append(" AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append(" AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append(" AND f.companyuuid IN (:companyIds) ");
        }

        sql.append(
                "    GROUP BY f.client_id " +
                        "), " +
                        "top_5_clients AS ( " +
                        "    SELECT " +
                        "        client_id, " +
                        "        client_revenue " +
                        "    FROM client_revenue " +
                        "    ORDER BY client_revenue DESC " +
                        "    LIMIT 5 " +
                        ") " +
                        "SELECT " +
                        "    COALESCE(SUM(cr.client_revenue), 0.0) AS total_revenue, " +
                        "    COALESCE(SUM(t5.client_revenue), 0.0) AS top5_revenue " +
                        "FROM client_revenue cr " +
                        "LEFT JOIN top_5_clients t5 ON cr.client_id = t5.client_id"
        );

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Object[] result = (Object[]) query.getSingleResult();
        double totalRevenue = ((Number) result[0]).doubleValue();
        double top5Revenue = ((Number) result[1]).doubleValue();

        log.debugf("Top 5 clients metrics: fromKey=%s, toKey=%s, totalRevenue=%.2f, top5Revenue=%.2f (%.1f%%)",
                fromKey, toKey, totalRevenue, top5Revenue,
                totalRevenue > 0 ? (top5Revenue / totalRevenue * 100.0) : 0.0);

        return Map.of("totalRevenue", totalRevenue, "top5Revenue", top5Revenue);
    }

    // ============================================================================
    // Forecast Utilization - Next 8 Weeks
    // ============================================================================

    /**
     * Gets Forecast Utilization KPI data for the next 8 weeks.
     * Measures forward-looking utilization based on scheduled work in the backlog.
     *
     * Formula: Forecast Utilization % = (Forecast Billable Hours / Total Capacity Hours) × 100
     *
     * Note: This is a user-centric metric, so only practice (service line) and company
     * filters apply. Sector, contract type, and client filters are NOT applicable.
     *
     * @param asOfDate Anchor date for calculation (typically today, defaults to now if null)
     * @param serviceLines Practice/service line filter (optional)
     * @param companyIds Company UUID filter (optional)
     * @return ForecastUtilizationDTO with current utilization, prior period comparison, and weekly sparkline
     */
    public ForecastUtilizationDTO getForecastUtilization(
            LocalDate asOfDate,
            Set<String> serviceLines,
            Set<String> companyIds) {

        // Default asOfDate to today if not provided
        LocalDate effectiveDate = asOfDate != null ? asOfDate : LocalDate.now();

        log.debugf("Calculating forecast utilization (next 8 weeks): asOfDate=%s, serviceLines=%s, companies=%s",
                effectiveDate, serviceLines, companyIds);

        // 1. Calculate date ranges for current and prior periods
        // Current: next 8 weeks from asOfDate
        LocalDate currentPeriodStart = effectiveDate;
        LocalDate currentPeriodEnd = effectiveDate.plusWeeks(8).minusDays(1);

        // Prior: 8 weeks before current period (for comparison - historical actuals from fact_user_utilization)
        LocalDate priorPeriodEnd = effectiveDate.minusDays(1);
        LocalDate priorPeriodStart = priorPeriodEnd.minusWeeks(8).plusDays(1);

        log.debugf("Current period: %s to %s (8 weeks forecast)", currentPeriodStart, currentPeriodEnd);
        log.debugf("Prior period: %s to %s (8 weeks actual)", priorPeriodStart, priorPeriodEnd);

        // 2. Query forecast data for current period (next 8 weeks)
        // Use fact_staffing_forecast_week view
        // IMPORTANT: Aggregate at user-week level first to avoid capacity double-counting
        Map<String, Double> currentPeriodData = queryForecastUtilizationData(
                currentPeriodStart, currentPeriodEnd, serviceLines, companyIds);

        double currentBillable = currentPeriodData.getOrDefault("billableHours", 0.0);
        double currentCapacity = currentPeriodData.getOrDefault("capacityHours", 0.0);
        double currentUtilization = currentCapacity > 0
                ? (currentBillable / currentCapacity) * 100.0
                : 0.0;

        // 3. Query prior period data (8 weeks historical actuals from fact_user_utilization)
        Map<String, Double> priorPeriodData = queryPriorUtilizationData(
                priorPeriodStart, priorPeriodEnd, serviceLines, companyIds);

        double priorBillable = priorPeriodData.getOrDefault("billableHours", 0.0);
        double priorCapacity = priorPeriodData.getOrDefault("capacityHours", 0.0);
        double priorUtilization = priorCapacity > 0
                ? (priorBillable / priorCapacity) * 100.0
                : 0.0;

        // 4. Calculate percentage point change
        double utilizationChange = currentUtilization - priorUtilization;

        // 5. Build weekly sparkline (8 data points for next 8 weeks)
        double[] weeklySparkline = buildForecastWeeklySparkline(
                currentPeriodStart, serviceLines, companyIds);

        log.infof("Forecast utilization result: current=%.1f%% (%.0f/%.0f hrs), prior=%.1f%%, change=%+.1f pp",
                currentUtilization, currentBillable, currentCapacity, priorUtilization, utilizationChange);

        return new ForecastUtilizationDTO(
                currentBillable,
                currentCapacity,
                currentUtilization,
                priorUtilization,
                utilizationChange,
                weeklySparkline
        );
    }

    /**
     * Query forecast utilization data from fact_staffing_forecast_week.
     * Aggregates at user-company-week level first to prevent capacity double-counting.
     */
    private Map<String, Double> queryForecastUtilizationData(
            LocalDate fromDate, LocalDate toDate,
            Set<String> serviceLines, Set<String> companyIds) {

        // Build SQL with subquery to aggregate at user-company-week level first
        StringBuilder sql = new StringBuilder();
        sql.append(
                "SELECT " +
                "    SUM(user_week_billable) AS total_billable, " +
                "    SUM(user_week_capacity) AS total_capacity " +
                "FROM ( " +
                "    SELECT " +
                "        user_id, " +
                "        company_id, " +
                "        week_key, " +
                "        SUM(forecast_billable_hours) AS user_week_billable, " +
                "        MAX(capacity_hours) AS user_week_capacity " +  // MAX to avoid project-level duplication
                "    FROM fact_staffing_forecast_week " +
                "    WHERE week_start_date >= :fromDate " +
                "      AND week_start_date <= :toDate "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append(" AND practice_id IN (:serviceLines) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append(" AND company_id IN (:companyIds) ");
        }

        sql.append(
                "    GROUP BY user_id, company_id, week_key " +
                ") AS user_week_aggregation"
        );

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Object[] result = (Object[]) query.getSingleResult();
        double billableHours = result[0] != null ? ((Number) result[0]).doubleValue() : 0.0;
        double capacityHours = result[1] != null ? ((Number) result[1]).doubleValue() : 0.0;

        log.debugf("Forecast data: fromDate=%s, toDate=%s, billable=%.0f, capacity=%.0f",
                fromDate, toDate, billableHours, capacityHours);

        return Map.of("billableHours", billableHours, "capacityHours", capacityHours);
    }

    /**
     * Query prior period utilization data from source tables (historical actuals).
     * Uses work_full for billable hours and bi_data_per_day for available hours.
     */
    private Map<String, Double> queryPriorUtilizationData(
            LocalDate fromDate, LocalDate toDate,
            Set<String> serviceLines, Set<String> companyIds) {

        StringBuilder billableSql = new StringBuilder(
                "SELECT COALESCE(SUM(w.workduration), 0.0) AS total_billable_hours " +
                        "FROM work w " +
                        "WHERE w.registered >= :fromDate " +
                        "  AND w.registered <= :toDate " +
                        "  AND w.workduration > 0 " +
                        "  AND w.rate > 0 " +
                        "  AND EXISTS ( " +
                        "    SELECT 1 " +
                        "    FROM userstatus us " +
                        "    WHERE us.useruuid = w.useruuid " +
                        "      AND us.statusdate = ( " +
                        "        SELECT MAX(us2.statusdate) " +
                        "        FROM userstatus us2 " +
                        "        WHERE us2.useruuid = w.useruuid " +
                        "          AND us2.statusdate <= w.registered " +
                        "      ) " +
                        "      AND us.type = 'CONSULTANT' " +
                        "      AND us.status = 'ACTIVE' "
        );

        if (companyIds != null && !companyIds.isEmpty()) {
            billableSql.append("      AND us.companyuuid IN (:companyIds) ");
        }

        billableSql.append("  ) ");

        if (serviceLines != null && !serviceLines.isEmpty()) {
            billableSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.practice IN (:serviceLines)) ");
        }

        Query billableQuery = em.createNativeQuery(billableSql.toString());
        billableQuery.setParameter("fromDate", fromDate);
        billableQuery.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) billableQuery.setParameter("serviceLines", serviceLines);
        if (companyIds != null && !companyIds.isEmpty()) billableQuery.setParameter("companyIds", companyIds);

        double billableHours = ((Number) billableQuery.getSingleResult()).doubleValue();

        // Available hours unchanged
        StringBuilder availableSql = new StringBuilder(
                "SELECT COALESCE(SUM(b.gross_available_hours - COALESCE(b.unavailable_hours, 0)), 0.0) AS total_available_hours " +
                        "FROM bi_data_per_day b " +
                        "WHERE b.document_date >= :fromDate " +
                        "  AND b.document_date <= :toDate " +
                        "  AND b.consultant_type = 'CONSULTANT' " +
                        "  AND b.status_type = 'ACTIVE' "
        );

        if (serviceLines != null && !serviceLines.isEmpty()) {
            availableSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = b.useruuid AND u.practice IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            availableSql.append("AND b.companyuuid IN (:companyIds) ");
        }

        Query availableQuery = em.createNativeQuery(availableSql.toString());
        availableQuery.setParameter("fromDate", fromDate);
        availableQuery.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) availableQuery.setParameter("serviceLines", serviceLines);
        if (companyIds != null && !companyIds.isEmpty()) availableQuery.setParameter("companyIds", companyIds);

        double capacityHours = ((Number) availableQuery.getSingleResult()).doubleValue();

        return Map.of("billableHours", billableHours, "capacityHours", capacityHours);
    }


    /**
     * Build weekly sparkline data (8 data points) for forecast utilization.
     */
    private double[] buildForecastWeeklySparkline(
            LocalDate startDate, Set<String> serviceLines, Set<String> companyIds) {

        double[] sparkline = new double[8];

        StringBuilder sql = new StringBuilder();
        sql.append(
                "SELECT " +
                "    week_key, " +
                "    SUM(user_week_billable) AS week_billable, " +
                "    SUM(user_week_capacity) AS week_capacity " +
                "FROM ( " +
                "    SELECT " +
                "        user_id, " +
                "        company_id, " +
                "        week_key, " +
                "        SUM(forecast_billable_hours) AS user_week_billable, " +
                "        MAX(capacity_hours) AS user_week_capacity " +
                "    FROM fact_staffing_forecast_week " +
                "    WHERE week_start_date >= :fromDate " +
                "      AND week_start_date < :toDate "
        );

        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append(" AND practice_id IN (:serviceLines) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append(" AND company_id IN (:companyIds) ");
        }

        sql.append(
                "    GROUP BY user_id, company_id, week_key " +
                ") AS user_week_aggregation " +
                "GROUP BY week_key " +
                "ORDER BY week_key "
        );

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", startDate);
        query.setParameter("toDate", startDate.plusWeeks(8));

        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // Map results to sparkline array (index by week position)
        for (int i = 0; i < results.size() && i < 8; i++) {
            Object[] row = results.get(i);
            double billable = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            double capacity = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            sparkline[i] = capacity > 0 ? (billable / capacity) * 100.0 : 0.0;
        }

        return sparkline;
    }

    // ============================================================================
    // Voluntary Attrition - 12-Month Rolling
    // ============================================================================

    /**
     * Gets Voluntary Attrition KPI data for the last 12 months.
     * Measures the 12-month rolling rate of employees who voluntarily left the organization.
     *
     * Formula: Voluntary Attrition % = (Voluntary Leavers / Average Headcount) × 100
     *
     * Note: This is a user-centric metric, so only practice (service line) and company
     * filters apply. Sector, contract type, and client filters are NOT applicable.
     *
     * Known limitation: Currently all leavers are counted as "voluntary" since
     * the userstatus table lacks a termination_reason field.
     *
     * @param asOfDate Anchor date for calculation (typically today, defaults to now if null)
     * @param serviceLines Practice/service line filter (optional)
     * @param companyIds Company UUID filter (optional)
     * @return VoluntaryAttritionDTO with current attrition, prior period comparison, and monthly sparkline
     */
    public VoluntaryAttritionDTO getVoluntaryAttrition(
            LocalDate asOfDate,
            Set<String> serviceLines,
            Set<String> companyIds) {

        // Default asOfDate to today if not provided
        LocalDate effectiveDate = asOfDate != null ? asOfDate : LocalDate.now();

        log.debugf("Calculating voluntary attrition (12-month rolling): asOfDate=%s, serviceLines=%s, companies=%s",
                effectiveDate, serviceLines, companyIds);

        // 1. Calculate date ranges for current and prior 12-month periods
        // Current: last 12 months ending in current month
        String currentToMonthKey = effectiveDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String currentFromMonthKey = effectiveDate.minusMonths(11).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

        // Prior: 12 months before current period (months -24 to -13)
        String priorToMonthKey = effectiveDate.minusMonths(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String priorFromMonthKey = effectiveDate.minusMonths(23).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

        log.debugf("Current period: %s to %s (12 months)", currentFromMonthKey, currentToMonthKey);
        log.debugf("Prior period: %s to %s (12 months)", priorFromMonthKey, priorToMonthKey);

        // 2. Query current 12-month attrition data
        Map<String, Object> currentData = queryAttritionData(
                currentFromMonthKey, currentToMonthKey, serviceLines, companyIds);

        int currentLeavers = ((Number) currentData.get("totalLeavers")).intValue();
        double currentAvgHeadcount = ((Number) currentData.get("avgHeadcount")).doubleValue();
        double currentAttrition = currentAvgHeadcount > 0
                ? (currentLeavers / currentAvgHeadcount) * 100.0
                : 0.0;

        // 3. Query prior 12-month attrition data
        Map<String, Object> priorData = queryAttritionData(
                priorFromMonthKey, priorToMonthKey, serviceLines, companyIds);

        int priorLeavers = ((Number) priorData.get("totalLeavers")).intValue();
        double priorAvgHeadcount = ((Number) priorData.get("avgHeadcount")).doubleValue();
        double priorAttrition = priorAvgHeadcount > 0
                ? (priorLeavers / priorAvgHeadcount) * 100.0
                : 0.0;

        // 4. Calculate percentage point change
        double attritionChange = currentAttrition - priorAttrition;

        // 5. Build monthly sparkline (12 data points showing rolling 12m attrition as of each month)
        double[] monthlySparkline = buildAttritionMonthlySparkline(
                effectiveDate, serviceLines, companyIds);

        log.infof("Voluntary attrition result: current=%.1f%% (%d leavers / %.1f avg HC), prior=%.1f%%, change=%+.1f pp",
                currentAttrition, currentLeavers, currentAvgHeadcount, priorAttrition, attritionChange);

        return new VoluntaryAttritionDTO(
                currentLeavers,
                currentAvgHeadcount,
                currentAttrition,
                priorAttrition,
                attritionChange,
                monthlySparkline
        );
    }

    /**
     * Gets OPEX Bridge comparing current fiscal year vs prior fiscal year.
     * Shows YoY changes broken down by expense category.
     *
     * @param asOfDate Anchor date determining current fiscal year (defaults to today)
     * @param costCenters Cost center filter (nullable)
     * @param companyIds Company UUID filter (nullable)
     * @return OpexBridgeDTO with FY totals and category-level changes
     */
    public OpexBridgeDTO getOpexBridge(
            LocalDate asOfDate,
            Set<String> costCenters,
            Set<String> companyIds) {

        LocalDate normalizedAsOfDate = (asOfDate != null) ? asOfDate : LocalDate.now();

        // Calculate fiscal year boundaries (July 1 - June 30)
        int currentFiscalYear = normalizedAsOfDate.getMonthValue() >= 7
                ? normalizedAsOfDate.getYear()
                : normalizedAsOfDate.getYear() - 1;
        LocalDate currentFYStart = LocalDate.of(currentFiscalYear, 7, 1);
        LocalDate currentFYEnd = LocalDate.of(currentFiscalYear + 1, 6, 30);

        int priorFiscalYear = currentFiscalYear - 1;
        LocalDate priorFYStart = LocalDate.of(priorFiscalYear, 7, 1);
        LocalDate priorFYEnd = LocalDate.of(priorFiscalYear + 1, 6, 30);

        String currentFromMonthKey = String.format("%04d%02d", currentFYStart.getYear(), currentFYStart.getMonthValue());
        String currentToMonthKey = String.format("%04d%02d", currentFYEnd.getYear(), currentFYEnd.getMonthValue());
        String priorFromMonthKey = String.format("%04d%02d", priorFYStart.getYear(), priorFYStart.getMonthValue());
        String priorToMonthKey = String.format("%04d%02d", priorFYEnd.getYear(), priorFYEnd.getMonthValue());

        log.debugf("OPEX Bridge: Current FY%d/%d (%s to %s), Prior FY%d/%d (%s to %s)",
                currentFiscalYear, currentFiscalYear + 1, currentFromMonthKey, currentToMonthKey,
                priorFiscalYear, priorFiscalYear + 1, priorFromMonthKey, priorToMonthKey);

        // Query both fiscal years via the distribution-aware provider.
        // Current FY uses distribution algorithm; prior FY uses raw GL (provider decides per month).
        List<OpexRow> currentFYRows = opexProvider.getDistributionAwareOpex(
                currentFromMonthKey, currentToMonthKey, companyIds, costCenters, null);
        List<OpexRow> priorFYRows = opexProvider.getDistributionAwareOpex(
                priorFromMonthKey, priorToMonthKey, companyIds, costCenters, null);

        // Aggregate by expense_category_id
        double priorFYOpex = 0.0;
        double currentFYOpex = 0.0;

        java.util.Map<String, Double> priorByCategory = new java.util.HashMap<>();
        java.util.Map<String, Double> currentByCategory = new java.util.HashMap<>();

        for (OpexRow row : priorFYRows) {
            priorFYOpex += row.opexAmountDkk();
            priorByCategory.merge(row.expenseCategoryId(), row.opexAmountDkk(), Double::sum);
        }
        for (OpexRow row : currentFYRows) {
            currentFYOpex += row.opexAmountDkk();
            currentByCategory.merge(row.expenseCategoryId(), row.opexAmountDkk(), Double::sum);
        }

        double peopleNonBillableChange;
        double toolsSoftwareChange;
        double officeFacilitiesChange;
        double salesMarketingChange;
        double otherOpexChange;

        // Calculate category-level changes
        peopleNonBillableChange = currentByCategory.getOrDefault("PEOPLE_NON_BILLABLE", 0.0)
                - priorByCategory.getOrDefault("PEOPLE_NON_BILLABLE", 0.0);
        toolsSoftwareChange = currentByCategory.getOrDefault("TOOLS_SOFTWARE", 0.0)
                - priorByCategory.getOrDefault("TOOLS_SOFTWARE", 0.0);
        officeFacilitiesChange = currentByCategory.getOrDefault("OFFICE_FACILITIES", 0.0)
                - priorByCategory.getOrDefault("OFFICE_FACILITIES", 0.0);
        salesMarketingChange = currentByCategory.getOrDefault("SALES_MARKETING", 0.0)
                - priorByCategory.getOrDefault("SALES_MARKETING", 0.0);
        otherOpexChange = currentByCategory.getOrDefault("OTHER_OPEX", 0.0)
                - priorByCategory.getOrDefault("OTHER_OPEX", 0.0);

        double yoyChangePercent = priorFYOpex > 0
                ? ((currentFYOpex - priorFYOpex) / priorFYOpex) * 100.0
                : 0.0;

        log.infof("OPEX Bridge results: Prior=%.2f, Current=%.2f, YoY=%+.2f%%",
                priorFYOpex, currentFYOpex, yoyChangePercent);

        return new OpexBridgeDTO(
                priorFYOpex,
                peopleNonBillableChange,
                toolsSoftwareChange,
                officeFacilitiesChange,
                salesMarketingChange,
                otherOpexChange,
                currentFYOpex,
                priorFiscalYear,
                currentFiscalYear,
                yoyChangePercent
        );
    }

    /**
     * Gets monthly expense mix by category for stacked column chart.
     */
    public List<MonthlyExpenseMixDTO> getExpenseMixByCategory(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> costCenters,
            Set<String> expenseCategories,
            Set<String> companyIds) {

        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1) : LocalDate.now();

        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("Expense Mix by Category: from=%s, to=%s, costCenters=%s, categories=%s, companies=%s",
                fromMonthKey, toMonthKey, costCenters, expenseCategories, companyIds);

        // Delegate to DistributionAwareOpexProvider: uses distribution for current-FY months,
        // raw GL (fact_opex_mat) for previous-FY months.
        List<OpexRow> opexRows = opexProvider.getDistributionAwareOpex(
                fromMonthKey, toMonthKey, companyIds, costCenters, expenseCategories);

        // Group by month and calculate percentages
        java.util.Map<String, java.util.Map<String, Double>> monthlyData = new java.util.LinkedHashMap<>();
        for (OpexRow row : opexRows) {
            monthlyData.putIfAbsent(row.monthKey(), new java.util.HashMap<>());
            monthlyData.get(row.monthKey()).merge(row.expenseCategoryId(), row.opexAmountDkk(), Double::sum);
        }

        List<MonthlyExpenseMixDTO> resultList = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.Map<String, Double>> entry : monthlyData.entrySet()) {
            String monthKey = entry.getKey();
            java.util.Map<String, Double> categories = entry.getValue();

            double peopleNonBillable = categories.getOrDefault("PEOPLE_NON_BILLABLE", 0.0);
            double toolsSoftware = categories.getOrDefault("TOOLS_SOFTWARE", 0.0);
            double officeFacilities = categories.getOrDefault("OFFICE_FACILITIES", 0.0);
            double salesMarketing = categories.getOrDefault("SALES_MARKETING", 0.0);
            double otherOpex = categories.getOrDefault("OTHER_OPEX", 0.0);
            double totalOpex = peopleNonBillable + toolsSoftware + officeFacilities + salesMarketing + otherOpex;

            double peopleNonBillablePercent = totalOpex > 0 ? (peopleNonBillable / totalOpex) * 100.0 : 0.0;
            double toolsSoftwarePercent = totalOpex > 0 ? (toolsSoftware / totalOpex) * 100.0 : 0.0;
            double officeFacilitiesPercent = totalOpex > 0 ? (officeFacilities / totalOpex) * 100.0 : 0.0;
            double salesMarketingPercent = totalOpex > 0 ? (salesMarketing / totalOpex) * 100.0 : 0.0;
            double otherOpexPercent = totalOpex > 0 ? (otherOpex / totalOpex) * 100.0 : 0.0;

            String monthLabel = formatMonthLabel(Integer.parseInt(monthKey.substring(0, 4)), Integer.parseInt(monthKey.substring(4)));

            resultList.add(new MonthlyExpenseMixDTO(
                    monthLabel, monthKey,
                    peopleNonBillable, toolsSoftware, officeFacilities, salesMarketing, otherOpex, totalOpex,
                    peopleNonBillablePercent, toolsSoftwarePercent, officeFacilitiesPercent, salesMarketingPercent, otherOpexPercent
            ));
        }

        return resultList;
    }

    /**
     * Gets monthly expense mix by cost center for stacked column chart.
     */
    public List<MonthlyCostCenterMixDTO> getExpenseMixByCostCenter(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> costCenters,
            Set<String> expenseCategories,
            Set<String> companyIds) {

        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1) : LocalDate.now();

        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("Expense Mix by Cost Center: from=%s, to=%s, costCenters=%s, categories=%s, companies=%s",
                fromMonthKey, toMonthKey, costCenters, expenseCategories, companyIds);

        // Delegate to DistributionAwareOpexProvider: uses distribution for current-FY months,
        // raw GL (fact_opex_mat) for previous-FY months.
        List<OpexRow> opexRows = opexProvider.getDistributionAwareOpex(
                fromMonthKey, toMonthKey, companyIds, costCenters, expenseCategories);

        // Group by month
        java.util.Map<String, java.util.Map<String, Double>> monthlyData = new java.util.LinkedHashMap<>();
        for (OpexRow row : opexRows) {
            monthlyData.putIfAbsent(row.monthKey(), new java.util.HashMap<>());
            monthlyData.get(row.monthKey()).merge(row.costCenterId(), row.opexAmountDkk(), Double::sum);
        }

        List<MonthlyCostCenterMixDTO> resultList = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, java.util.Map<String, Double>> entry : monthlyData.entrySet()) {
            String monthKey = entry.getKey();
            java.util.Map<String, Double> centers = entry.getValue();

            double hrAdmin = centers.getOrDefault("HR_ADMIN", 0.0);
            double sales = centers.getOrDefault("SALES", 0.0);
            double internalIT = centers.getOrDefault("INTERNAL_IT", 0.0);
            double facilities = centers.getOrDefault("FACILITIES", 0.0);
            double admin = centers.getOrDefault("ADMIN", 0.0);
            double general = centers.getOrDefault("GENERAL", 0.0);
            double totalOpex = hrAdmin + sales + internalIT + facilities + admin + general;

            double hrAdminPercent = totalOpex > 0 ? (hrAdmin / totalOpex) * 100.0 : 0.0;
            double salesPercent = totalOpex > 0 ? (sales / totalOpex) * 100.0 : 0.0;
            double internalITPercent = totalOpex > 0 ? (internalIT / totalOpex) * 100.0 : 0.0;
            double facilitiesPercent = totalOpex > 0 ? (facilities / totalOpex) * 100.0 : 0.0;
            double adminPercent = totalOpex > 0 ? (admin / totalOpex) * 100.0 : 0.0;
            double generalPercent = totalOpex > 0 ? (general / totalOpex) * 100.0 : 0.0;

            String monthLabel = formatMonthLabel(Integer.parseInt(monthKey.substring(0, 4)), Integer.parseInt(monthKey.substring(4)));

            resultList.add(new MonthlyCostCenterMixDTO(
                    monthLabel, monthKey,
                    hrAdmin, sales, internalIT, facilities, admin, general, totalOpex,
                    hrAdminPercent, salesPercent, internalITPercent, facilitiesPercent, adminPercent, generalPercent
            ));
        }

        return resultList;
    }

    /**
     * Gets monthly payroll and headcount structure (combo chart).
     * Joins fact_opex (payroll), fact_employee_monthly (FTE), and revenue data.
     * Also computes Staff and Junior Consultant FTE + payroll breakdowns per month.
     *
     * <p>Staff: employees with userstatus.type = 'STAFF' (non-consulting support roles).
     * Junior Consultant: employees with user_career_level.career_level = 'JUNIOR_CONSULTANT'
     * (entry-level consultants, typically on student contracts).
     *
     * <p>Staff/Junior payroll is the sum of latest salary.salary for active users as of the
     * last day of each month. This is gross salary only, not total employment cost.
     */
    public List<MonthlyPayrollHeadcountDTO> getPayrollHeadcountStructure(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1) : LocalDate.now();

        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("Payroll Headcount Structure: from=%s, to=%s, practices=%s, companies=%s",
                fromMonthKey, toMonthKey, practices, companyIds);

        // Payroll OPEX from provider (distribution-aware, filtered to isPayrollFlag = true)
        List<OpexRow> payrollOpexRows = opexProvider.getDistributionAwareOpex(
                fromMonthKey, toMonthKey, companyIds, null, null);
        java.util.Map<String, Double> payrollByMonth = new java.util.HashMap<>();
        for (OpexRow row : payrollOpexRows) {
            if (row.isPayrollFlag()) {
                payrollByMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
            }
        }

        // FTE and revenue from fact_employee_monthly_mat / fact_project_financials_mat
        StringBuilder sql = new StringBuilder(
                "SELECT e.month_key, " +
                "  SUM(e.fte_billable) AS billable_fte, " +
                "  SUM(e.fte_non_billable) AS non_billable_fte, " +
                "  COALESCE((SELECT SUM(f.recognized_revenue_dkk) FROM fact_project_financials_mat f" +
                "    WHERE f.month_key = e.month_key), 0) AS total_revenue " +
                "FROM fact_employee_monthly_mat e " +
                "WHERE 1=1 "
        );

        sql.append("  AND e.month_key >= :fromMonthKey ")
                .append("  AND e.month_key <= :toMonthKey ");

        if (practices != null && !practices.isEmpty()) {
            sql.append("  AND e.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND e.company_id IN (:companyIds) ");
        }

        sql.append("GROUP BY e.month_key ORDER BY e.month_key ASC");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // Build Staff + Junior breakdown per month using salary + userstatus point-in-time lookups.
        // We run one query over the full month range and build a map keyed by month_key.
        java.util.Map<String, double[]> staffJuniorByMonth = queryStaffJuniorBreakdown(
                fromMonthKey, toMonthKey, companyIds);

        List<MonthlyPayrollHeadcountDTO> resultList = new java.util.ArrayList<>();
        for (Object[] row : results) {
            String monthKey = (String) row[0];
            double billableFTE = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            double nonBillableFTE = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            // Merge distribution-aware payroll for this month (0.0 if no payroll data found)
            double totalPayroll = payrollByMonth.getOrDefault(monthKey, 0.0);
            double totalRevenue = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;

            double payrollAsPercentOfRevenue = totalRevenue > 0 ? (totalPayroll / totalRevenue) * 100.0 : 0.0;
            double totalFTE = billableFTE + nonBillableFTE;

            String monthLabel = formatMonthLabel(Integer.parseInt(monthKey.substring(0, 4)), Integer.parseInt(monthKey.substring(4)));

            // Staff/Junior breakdown: [staffFTE, staffPayroll, juniorFTE, juniorPayroll]
            double[] sjData = staffJuniorByMonth.getOrDefault(monthKey, new double[]{0.0, 0.0, 0.0, 0.0});

            resultList.add(new MonthlyPayrollHeadcountDTO(
                    monthLabel, monthKey,
                    billableFTE, nonBillableFTE,
                    totalPayroll, totalRevenue, payrollAsPercentOfRevenue, totalFTE,
                    sjData[0], sjData[2], sjData[1], sjData[3]
            ));
        }

        log.debugf("Returning %d payroll/headcount data points", resultList.size());
        return resultList;
    }

    /**
     * Queries monthly Staff and Junior Consultant headcount + gross payroll for a month range.
     *
     * <p>Staff: userstatus.type = 'STAFF', status = 'ACTIVE', point-in-time as of last day of month.
     * Junior Consultant: user_career_level.career_level = 'JUNIOR_CONSULTANT', same point-in-time.
     * Payroll: latest salary.salary as of the last day of the month (gross salary only).
     *
     * @param fromMonthKey Start month key (YYYYMM, inclusive)
     * @param toMonthKey   End month key (YYYYMM, inclusive)
     * @param companyIds   Optional company filter; null or empty means all companies
     * @return Map from month_key to double[4]: [staffFTE, staffPayroll, juniorFTE, juniorPayroll]
     */
    private java.util.Map<String, double[]> queryStaffJuniorBreakdown(
            String fromMonthKey,
            String toMonthKey,
            Set<String> companyIds) {

        // Derive the month range from fact_employee_monthly_mat to avoid generating month series in SQL.
        // This piggybacks on existing month coverage and avoids complex recursive CTEs in MariaDB 10.
        StringBuilder sql = new StringBuilder("""
                SELECT
                  m.month_key,
                  COUNT(CASE WHEN us.type = 'STAFF' THEN 1 END)                                    AS staff_fte,
                  SUM(CASE WHEN us.type = 'STAFF' THEN COALESCE(ls.salary, 0) ELSE 0 END)          AS staff_payroll,
                  COUNT(CASE WHEN ucl.career_level = 'JUNIOR_CONSULTANT' THEN 1 END)               AS junior_fte,
                  SUM(CASE WHEN ucl.career_level = 'JUNIOR_CONSULTANT' THEN COALESCE(ls.salary, 0) ELSE 0 END) AS junior_payroll
                FROM (
                  SELECT DISTINCT month_key FROM fact_employee_monthly_mat
                  WHERE month_key >= :fromMonthKey AND month_key <= :toMonthKey
                ) m
                JOIN userstatus us
                  ON us.status = 'ACTIVE'
                  AND us.type IN ('STAFF', 'CONSULTANT', 'STUDENT', 'EXTERNAL')
                  AND us.statusdate = (
                      SELECT MAX(us2.statusdate) FROM userstatus us2
                      WHERE us2.useruuid = us.useruuid
                        AND us2.statusdate <= LAST_DAY(STR_TO_DATE(CONCAT(m.month_key, '01'), '%Y%m%d'))
                  )
                JOIN user u ON u.uuid = us.useruuid AND u.type = 'USER'
                """);

        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND us.companyuuid IN (:companyIds) \n");
        }

        sql.append("""
                LEFT JOIN user_career_level ucl
                  ON ucl.useruuid = u.uuid
                  AND ucl.active_from = (
                      SELECT MAX(ucl2.active_from) FROM user_career_level ucl2
                      WHERE ucl2.useruuid = u.uuid
                        AND ucl2.active_from <= LAST_DAY(STR_TO_DATE(CONCAT(m.month_key, '01'), '%Y%m%d'))
                  )
                LEFT JOIN salary ls
                  ON ls.useruuid = us.useruuid
                  AND ls.activefrom = (
                      SELECT MAX(s2.activefrom) FROM salary s2
                      WHERE s2.useruuid = us.useruuid
                        AND s2.activefrom <= LAST_DAY(STR_TO_DATE(CONCAT(m.month_key, '01'), '%Y%m%d'))
                  )
                GROUP BY m.month_key
                ORDER BY m.month_key ASC
                """);

        Query q = em.createNativeQuery(sql.toString());
        q.setParameter("fromMonthKey", fromMonthKey);
        q.setParameter("toMonthKey", toMonthKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            q.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();

        java.util.Map<String, double[]> result = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            String monthKey = (String) row[0];
            double staffFTE     = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            double staffPayroll = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            double juniorFTE    = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
            double juniorPayroll = row[4] != null ? ((Number) row[4]).doubleValue() : 0.0;
            result.put(monthKey, new double[]{staffFTE, staffPayroll, juniorFTE, juniorPayroll});
        }

        log.debugf("Staff/Junior breakdown: %d months queried", result.size());
        return result;
    }

    /**
     * Gets monthly overhead per FTE with TTM calculations.
     */
    public List<MonthlyOverheadPerFTEDTO> getOverheadPerFTE(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> companyIds) {

        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1) : LocalDate.now();

        log.debugf("Overhead per FTE (TTM): from=%s, to=%s, companies=%s",
                normalizedFromDate, normalizedToDate, companyIds);

        List<MonthlyOverheadPerFTEDTO> resultList = new java.util.ArrayList<>();

        YearMonth start = YearMonth.from(normalizedFromDate);
        YearMonth end = YearMonth.from(normalizedToDate);

        if (start.isAfter(end)) {
            return resultList;
        }

        // Optimized: determine the full OPEX date range needed across all TTM windows,
        // then call the provider once (cache hits apply per month inside provider).
        LocalDate overallTtmStart = start.atEndOfMonth().minusMonths(11).withDayOfMonth(1);
        LocalDate overallTtmEnd   = end.atEndOfMonth();
        String overallFromKey = String.format("%04d%02d", overallTtmStart.getYear(), overallTtmStart.getMonthValue());
        String overallToKey   = String.format("%04d%02d", overallTtmEnd.getYear(), overallTtmEnd.getMonthValue());

        // Load all non-payroll OPEX for the full range in one provider call
        List<OpexRow> allOpexRows = opexProvider.getDistributionAwareOpex(
                overallFromKey, overallToKey, companyIds, null, null);

        // Build a lookup: monthKey → non-payroll opex sum
        java.util.Map<String, Double> nonPayrollByMonth = new java.util.HashMap<>();
        for (OpexRow row : allOpexRows) {
            if (!row.isPayrollFlag()) {
                nonPayrollByMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
            }
        }

        // For each output month in the requested range, aggregate the TTM window from the in-memory map
        YearMonth current = start;
        while (!current.isAfter(end)) {
            LocalDate monthEnd = current.atEndOfMonth();
            LocalDate ttmStart = monthEnd.minusMonths(11).withDayOfMonth(1);

            String ttmStartKey = String.format("%04d%02d", ttmStart.getYear(), ttmStart.getMonthValue());
            String ttmEndKey   = String.format("%04d%02d", monthEnd.getYear(), monthEnd.getMonthValue());

            // Sum non-payroll OPEX over the TTM window from the in-memory map
            double ttmNonPayrollOpex = nonPayrollByMonth.entrySet().stream()
                    .filter(e -> e.getKey().compareTo(ttmStartKey) >= 0 && e.getKey().compareTo(ttmEndKey) <= 0)
                    .mapToDouble(java.util.Map.Entry::getValue)
                    .sum();

            // FTE from fact_employee_monthly_mat — unchanged (no distribution needed for headcount)
            StringBuilder fteSql = new StringBuilder(
                    "SELECT AVG(fte_billable + fte_non_billable) AS avg_total_fte, " +
                    "  AVG(fte_billable) AS avg_billable_fte " +
                    "FROM fact_employee_monthly_mat " +
                    "WHERE month_key >= :fromKey AND month_key <= :toKey "
            );
            if (companyIds != null && !companyIds.isEmpty()) {
                fteSql.append("  AND company_id IN (:companyIds) ");
            }

            Query fteQuery = em.createNativeQuery(fteSql.toString());
            fteQuery.setParameter("fromKey", ttmStartKey);
            fteQuery.setParameter("toKey", ttmEndKey);
            if (companyIds != null && !companyIds.isEmpty()) {
                fteQuery.setParameter("companyIds", companyIds);
            }

            Object[] fteResult = (Object[]) fteQuery.getSingleResult();
            double ttmAvgTotalFTE = fteResult[0] != null ? ((Number) fteResult[0]).doubleValue() : 0.0;
            double ttmAvgBillableFTE = fteResult[1] != null ? ((Number) fteResult[1]).doubleValue() : 0.0;

            double overheadPerFTE = ttmAvgTotalFTE > 0 ? ttmNonPayrollOpex / ttmAvgTotalFTE : 0.0;
            double overheadPerBillableFTE = ttmAvgBillableFTE > 0 ? ttmNonPayrollOpex / ttmAvgBillableFTE : 0.0;

            String monthKey = String.format("%04d%02d", current.getYear(), current.getMonthValue());
            String monthLabel = formatMonthLabel(current.getYear(), current.getMonthValue());

            resultList.add(new MonthlyOverheadPerFTEDTO(
                    monthLabel, monthKey,
                    ttmNonPayrollOpex, ttmAvgTotalFTE, ttmAvgBillableFTE,
                    overheadPerFTE, overheadPerBillableFTE
            ));

            current = current.plusMonths(1);
        }

        log.debugf("Returning %d overhead per FTE data points", resultList.size());
        return resultList;
    }

    /**
     * Gets detailed expense drill-down with budget variance.
     */
    public List<OpexDetailRowDTO> getExpenseDetail(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> costCenters,
            Set<String> expenseCategories,
            Set<String> companyIds) {

        LocalDate normalizedFromDate = (fromDate != null) ? fromDate.withDayOfMonth(1) : LocalDate.now().minusMonths(11).withDayOfMonth(1);
        LocalDate normalizedToDate = (toDate != null) ? toDate.withDayOfMonth(1) : LocalDate.now();

        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("Expense Detail: from=%s, to=%s, costCenters=%s, categories=%s, companies=%s",
                fromMonthKey, toMonthKey, costCenters, expenseCategories, companyIds);

        // Step 1: Get distribution-aware OPEX rows from the provider
        List<OpexRow> opexRows = opexProvider.getDistributionAwareOpex(
                fromMonthKey, toMonthKey, companyIds, costCenters, expenseCategories);

        // Step 2: Build a lookup of companyId → companyName from the companies table
        java.util.Set<String> opexCompanyIds = opexRows.stream()
                .map(OpexRow::companyId)
                .collect(java.util.stream.Collectors.toSet());

        java.util.Map<String, String> companyNameById = new java.util.HashMap<>();
        if (!opexCompanyIds.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> companyRows = em.createNativeQuery(
                    "SELECT uuid, name FROM companies WHERE uuid IN (:uuids)")
                    .setParameter("uuids", opexCompanyIds)
                    .getResultList();
            for (Object[] row : companyRows) {
                companyNameById.put((String) row[0], (String) row[1]);
            }
        }

        // Step 3: Budget amounts from fact_opex_budget (stub — always 0 per GAP-6, kept for future use)
        // Key: companyId + "|" + monthKey + "|" + costCenterId + "|" + expenseCategoryId → budgetAmount
        java.util.Map<String, Double> budgetByKey = new java.util.HashMap<>();
        if (!opexRows.isEmpty()) {
            StringBuilder budgetSql = new StringBuilder(
                    "SELECT company_id, month_key, cost_center_id, expense_category_id, " +
                    "  COALESCE(SUM(budget_opex_dkk), 0) AS budget_amount " +
                    "FROM fact_opex_budget " +
                    "WHERE month_key >= :fromMonthKey AND month_key <= :toMonthKey "
            );
            if (companyIds != null && !companyIds.isEmpty()) {
                budgetSql.append("AND company_id IN (:companyIds) ");
            }
            budgetSql.append("GROUP BY company_id, month_key, cost_center_id, expense_category_id");

            Query budgetQuery = em.createNativeQuery(budgetSql.toString());
            budgetQuery.setParameter("fromMonthKey", fromMonthKey);
            budgetQuery.setParameter("toMonthKey", toMonthKey);
            if (companyIds != null && !companyIds.isEmpty()) {
                budgetQuery.setParameter("companyIds", companyIds);
            }

            @SuppressWarnings("unchecked")
            List<Object[]> budgetRows = budgetQuery.getResultList();
            for (Object[] row : budgetRows) {
                String key = row[0] + "|" + row[1] + "|" + row[2] + "|" + row[3];
                budgetByKey.put(key, row[4] != null ? ((Number) row[4]).doubleValue() : 0.0);
            }
        }

        // Step 4: Aggregate OPEX rows by (companyId, monthKey, costCenterId, expenseCategoryId)
        // and merge with budget data
        record DetailKey(String companyId, String monthKey, String costCenterId, String expenseCategoryId) {}
        java.util.Map<DetailKey, double[]> aggregated = new java.util.LinkedHashMap<>();
        // double[]{opexSum, invoiceCountSum, isPayrollMax}
        for (OpexRow row : opexRows) {
            DetailKey key = new DetailKey(row.companyId(), row.monthKey(), row.costCenterId(), row.expenseCategoryId());
            aggregated.merge(key,
                    new double[]{row.opexAmountDkk(), row.invoiceCount(), row.isPayrollFlag() ? 1.0 : 0.0},
                    (existing, incoming) -> new double[]{
                            existing[0] + incoming[0],
                            existing[1] + incoming[1],
                            Math.max(existing[2], incoming[2])
                    });
        }

        // Sort: descending by monthKey, then companyName, then costCenter, then expenseCategory
        List<DetailKey> sortedKeys = new java.util.ArrayList<>(aggregated.keySet());
        sortedKeys.sort(java.util.Comparator
                .comparing((DetailKey k) -> k.monthKey()).reversed()
                .thenComparing(k -> companyNameById.getOrDefault(k.companyId(), k.companyId()))
                .thenComparing(DetailKey::costCenterId)
                .thenComparing(DetailKey::expenseCategoryId));

        List<OpexDetailRowDTO> resultList = new java.util.ArrayList<>();
        for (DetailKey key : sortedKeys) {
            double[] vals = aggregated.get(key);
            double opexAmount = vals[0];
            int invoiceCount = (int) vals[1];
            boolean isPayroll = vals[2] > 0.0;

            String budgetLookupKey = key.companyId() + "|" + key.monthKey() + "|" + key.costCenterId() + "|" + key.expenseCategoryId();
            double budgetAmount = budgetByKey.getOrDefault(budgetLookupKey, 0.0);

            double varianceAmount = opexAmount - budgetAmount;
            Double variancePercent = budgetAmount > 0 ? (varianceAmount / budgetAmount) * 100.0 : null;

            int year = Integer.parseInt(key.monthKey().substring(0, 4));
            int month = Integer.parseInt(key.monthKey().substring(4));
            int fiscalYear = month >= 7 ? year : year - 1;
            int fiscalMonthNumber = month >= 7 ? month - 6 : month + 6;

            String companyName = companyNameById.getOrDefault(key.companyId(), key.companyId());
            String monthLabel = formatMonthLabel(year, month);

            resultList.add(new OpexDetailRowDTO(
                    companyName, key.costCenterId(), key.expenseCategoryId(), "", "",
                    key.monthKey(), monthLabel,
                    opexAmount, budgetAmount, varianceAmount, variancePercent,
                    invoiceCount, isPayroll,
                    fiscalYear, fiscalMonthNumber
            ));
        }

        log.debugf("Returning %d expense detail rows", resultList.size());
        return resultList;
    }

    // ========== PRIVATE HELPER METHODS ==========

    /**
     * Query attrition data from fact_employee_monthly for a 12-month period.
     */
    private Map<String, Object> queryAttritionData(
            String fromMonthKey, String toMonthKey,
            Set<String> serviceLines, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder();
        sql.append(
                "SELECT " +
                "    SUM(voluntary_leavers_count) AS total_leavers, " +
                "    AVG(average_headcount) AS avg_headcount " +
                "FROM fact_employee_monthly_mat " +
                "WHERE month_key >= :fromMonthKey " +
                "  AND month_key <= :toMonthKey "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append(" AND practice_id IN (:serviceLines) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append(" AND company_id IN (:companyIds) ");
        }

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Object[] result = (Object[]) query.getSingleResult();
        int totalLeavers = result[0] != null ? ((Number) result[0]).intValue() : 0;
        double avgHeadcount = result[1] != null ? ((Number) result[1]).doubleValue() : 0.0;

        log.debugf("Attrition data: fromKey=%s, toKey=%s, leavers=%d, avgHC=%.1f",
                fromMonthKey, toMonthKey, totalLeavers, avgHeadcount);

        return Map.of("totalLeavers", totalLeavers, "avgHeadcount", avgHeadcount);
    }

    /**
     * Build monthly sparkline data (12 data points) showing rolling 12-month attrition as of each month end.
     */
    private double[] buildAttritionMonthlySparkline(
            LocalDate endDate, Set<String> serviceLines, Set<String> companyIds) {

        double[] sparkline = new double[12];

        // For each of the last 12 months, calculate the rolling 12-month attrition as of that month
        for (int i = 0; i < 12; i++) {
            LocalDate monthEnd = endDate.minusMonths(11 - i);
            String toMonthKey = monthEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            String fromMonthKey = monthEnd.minusMonths(11).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

            Map<String, Object> data = queryAttritionData(fromMonthKey, toMonthKey, serviceLines, companyIds);
            int leavers = ((Number) data.get("totalLeavers")).intValue();
            double avgHeadcount = ((Number) data.get("avgHeadcount")).doubleValue();

            sparkline[i] = avgHeadcount > 0 ? (leavers / avgHeadcount) * 100.0 : 0.0;
        }

        return sparkline;
    }

    // ============================================================================
    // Accumulated Revenue (FY) — Chart D
    // ============================================================================

    /**
     * Returns 12 data points (one per fiscal month, Jul–Jun) showing monthly and accumulated
     * revenue for the fiscal year that contains {@code asOfDate}.
     *
     * <p>Past months use actuals from {@code fact_project_financials_mat} with the standard
     * deduplication pattern (GROUP BY project_id + month_key, MAX revenue). Future months
     * within the current fiscal year are returned with zero revenue and {@code isActual=false}.
     *
     * @param asOfDate     Reference date for fiscal year detection (defaults to today)
     * @param sectors      Sector filter (nullable / empty = all)
     * @param serviceLines Service line filter (nullable / empty = all)
     * @param contractTypes Contract type filter (nullable / empty = all)
     * @param clientId     Single client UUID filter (nullable / blank = all)
     * @param companyIds   Company UUID filter (nullable / empty = all)
     * @return Exactly 12 DTOs sorted by fiscal month number (1=Jul … 12=Jun)
     */
    public List<MonthlyAccumulatedRevenueDTO> getAccumulatedRevenue(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate today = (asOfDate != null) ? asOfDate : LocalDate.now();

        // Fiscal year: July 1 → June 30
        int fiscalYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);   // Jul 1
        LocalDate fyEnd   = LocalDate.of(fiscalYear + 1, 6, 30); // Jun 30

        String fromKey = String.format("%04d%02d", fyStart.getYear(), fyStart.getMonthValue());
        String toKey   = String.format("%04d%02d", fyEnd.getYear(), fyEnd.getMonthValue());

        log.debugf("getAccumulatedRevenue: FY=%d (%s → %s), sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fiscalYear, fromKey, toKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Revenue from fact_company_revenue_mat (company-total, companyIds filter only).
        // Revenue KPIs always show company-total — dimension filters do not apply to revenue.
        java.util.Map<String, Double> revenueByMonth = queryCompanyRevenueByMonth(fromKey, toKey, companyIds);

        // Build 12 data points from Jul to Jun, accumulating running sum
        String currentMonthKey = String.format("%04d%02d", today.getYear(), today.getMonthValue());
        List<MonthlyAccumulatedRevenueDTO> result = new ArrayList<>(12);
        double accumulated = 0.0;

        for (int fiscalMonth = 1; fiscalMonth <= 12; fiscalMonth++) {
            // Map fiscal month (1=Jul…12=Jun) to calendar year/month
            int calMonth = (fiscalMonth <= 6) ? fiscalMonth + 6 : fiscalMonth - 6;
            int calYear  = (fiscalMonth <= 6) ? fiscalYear : fiscalYear + 1;
            String monthKey = String.format("%04d%02d", calYear, calMonth);

            boolean isActual = monthKey.compareTo(currentMonthKey) <= 0
                    && revenueByMonth.containsKey(monthKey);

            double monthly = revenueByMonth.getOrDefault(monthKey, 0.0);
            accumulated += monthly;

            result.add(new MonthlyAccumulatedRevenueDTO(
                    monthKey,
                    calYear,
                    calMonth,
                    formatMonthLabel(calYear, calMonth),
                    fiscalMonth,
                    monthly,
                    accumulated,
                    isActual
            ));
        }

        log.debugf("getAccumulatedRevenue: returning %d data points, total accumulated=%.2f DKK", result.size(), (Double) accumulated);
        return result;
    }

    // ============================================================================
    // Accumulated Revenue Source Data Export
    // ============================================================================

    /**
     * Returns raw invoice and invoice-item records that make up the accumulated revenue
     * for the fiscal year containing {@code asOfDate}.
     *
     * <p>Only invoices with {@code status = 'CREATED'} and types {@code INVOICE},
     * {@code PHANTOM}, or {@code CREDIT_NOTE} are included.  The project universe is
     * restricted to projects that appear in {@code fact_project_financials_mat} for the
     * fiscal year, applying the same CXO dimension filters used by
     * {@link #getAccumulatedRevenue}.
     *
     * @param asOfDate      Reference date for fiscal year detection (nullable → today)
     * @param sectors       Sector filter (nullable / empty = all)
     * @param serviceLines  Service line filter (nullable / empty = all)
     * @param contractTypes Contract type filter (nullable / empty = all)
     * @param clientId      Single client UUID filter (nullable / blank = all)
     * @param companyIds    Company UUID filter (nullable / empty = all)
     * @return Container with invoices, line items, and credit notes
     */
    public RevenueSourceDataDTO getAccumulatedRevenueSourceData(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate today = (asOfDate != null) ? asOfDate : LocalDate.now();

        // Fiscal year: July 1 → June 30
        int fiscalYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd   = LocalDate.of(fiscalYear + 1, 6, 30);

        String fromKey = String.format("%04d%02d", fyStart.getYear(), fyStart.getMonthValue());
        String toKey   = String.format("%04d%02d", fyEnd.getYear(), fyEnd.getMonthValue());

        log.debugf("getAccumulatedRevenueSourceData: FY=%d (%s → %s), sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fiscalYear, fromKey, toKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Step 1: Resolve matching project IDs from the fact materialised view,
        // applying the same dimension filters as getAccumulatedRevenue.
        StringBuilder projectSql = new StringBuilder(
                "SELECT DISTINCT f.project_id FROM fact_project_financials_mat f " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) projectSql.append("AND f.companyuuid IN (:companyIds) ");
        if (sectors != null && !sectors.isEmpty())         projectSql.append("AND f.sector_id IN (:sectors) ");
        if (serviceLines != null && !serviceLines.isEmpty()) projectSql.append("AND f.service_line_id IN (:serviceLines) ");
        if (contractTypes != null && !contractTypes.isEmpty()) projectSql.append("AND f.contract_type_id IN (:contractTypes) ");
        if (clientId != null && !clientId.isBlank())       projectSql.append("AND f.client_id = :clientId ");

        Query projectQuery = em.createNativeQuery(projectSql.toString());
        projectQuery.setParameter("fromKey", fromKey);
        projectQuery.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty())      projectQuery.setParameter("companyIds", companyIds);
        if (sectors != null && !sectors.isEmpty())             projectQuery.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty())   projectQuery.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) projectQuery.setParameter("contractTypes", contractTypes);
        if (clientId != null && !clientId.isBlank())           projectQuery.setParameter("clientId", clientId);

        @SuppressWarnings("unchecked")
        List<String> matchedProjects = projectQuery.getResultList();

        if (matchedProjects.isEmpty()) {
            log.debugf("getAccumulatedRevenueSourceData: no matching projects — returning empty result");
            return new RevenueSourceDataDTO(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        log.debugf("getAccumulatedRevenueSourceData: %d matched projects", matchedProjects.size());

        // Step 2: Query raw invoice + line-item data for the matched projects.
        String itemSql =
                "SELECT " +
                "    i.uuid           AS invoice_uuid, " +
                "    i.invoicenumber  AS invoice_number, " +
                "    DATE_FORMAT(i.invoicedate, '%Y-%m-%d') AS invoice_date, " +
                "    i.clientname     AS client_name, " +
                "    p.clientuuid     AS client_uuid, " +
                "    i.projectname    AS project_name, " +
                "    i.projectuuid    AS project_uuid, " +
                "    i.type           AS invoice_type, " +
                "    i.status         AS invoice_status, " +
                "    i.currency       AS currency, " +
                "    COALESCE(i.discount, 0)  AS discount, " +
                "    COALESCE(i.companyuuid, '') AS company_uuid, " +
                "    ii.uuid          AS item_uuid, " +
                "    COALESCE(ii.consultantuuid, '') AS consultant_uuid, " +
                "    COALESCE(CONCAT(u.firstname, ' ', u.lastname), ii.itemname) AS consultant_name, " +
                "    ii.itemname      AS item_name, " +
                "    COALESCE(ii.description, '') AS item_description, " +
                "    ii.rate          AS rate, " +
                "    ii.hours         AS hours, " +
                "    ii.rate * ii.hours AS item_amount, " +
                "    ii.rate * ii.hours * CASE WHEN i.currency = 'DKK' THEN 1 " +
                "                             ELSE COALESCE(cur.conversion, 1) END AS item_amount_dkk, " +
                "    COALESCE(ii.origin, 'BASE') AS item_origin " +
                "FROM invoices i " +
                "    INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid " +
                "    INNER JOIN project p ON p.uuid = i.projectuuid " +
                "    LEFT JOIN user u ON u.uuid = ii.consultantuuid " +
                "    LEFT JOIN currences cur " +
                "        ON cur.currency = i.currency " +
                "        AND cur.month = DATE_FORMAT(i.invoicedate, '%Y%m') " +
                "WHERE i.projectuuid IN (:matchedProjects) " +
                "    AND i.invoicedate BETWEEN :fyStart AND :fyEnd " +
                "    AND i.status = 'CREATED' " +
                "    AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE') " +
                "    AND ii.rate IS NOT NULL " +
                "    AND ii.hours IS NOT NULL " +
                "    AND (i.currency = 'DKK' OR cur.uuid IS NOT NULL) " +
                (companyIds != null && !companyIds.isEmpty() ? "    AND i.companyuuid IN (:companyIds) " : "") +
                "ORDER BY i.invoicedate, i.invoicenumber";

        Query itemQuery = em.createNativeQuery(itemSql, Tuple.class);
        itemQuery.setParameter("matchedProjects", matchedProjects);
        itemQuery.setParameter("fyStart", fyStart);
        itemQuery.setParameter("fyEnd", fyEnd);
        if (companyIds != null && !companyIds.isEmpty()) itemQuery.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = itemQuery.getResultList();

        // Step 3: Map result rows to DTOs.
        // Aggregate per-invoice totals using a LinkedHashMap to preserve invoice order.
        Map<String, InvoiceExportDTO> invoiceMap     = new LinkedHashMap<>();
        Map<String, InvoiceExportDTO> creditNoteMap  = new LinkedHashMap<>();
        List<InvoiceItemExportDTO>    invoiceItems   = new ArrayList<>();

        for (Tuple row : rows) {
            String invoiceUuid   = (String) row.get("invoice_uuid");
            int    invoiceNumber = ((Number) row.get("invoice_number")).intValue();
            String invoiceDate   = (String) row.get("invoice_date");
            String clientName    = (String) row.get("client_name");
            String clientUuid    = (String) row.get("client_uuid");
            String projectName   = (String) row.get("project_name");
            String projectUuid   = (String) row.get("project_uuid");
            String invoiceType   = (String) row.get("invoice_type");
            String invoiceStatus = (String) row.get("invoice_status");
            String currency      = (String) row.get("currency");
            double discount      = ((Number) row.get("discount")).doubleValue();
            String companyUuid   = (String) row.get("company_uuid");
            double itemAmount    = ((Number) row.get("item_amount")).doubleValue();
            double itemAmountDkk = ((Number) row.get("item_amount_dkk")).doubleValue();

            // Accumulate invoice-level totals
            boolean isCreditNote = "CREDIT_NOTE".equals(invoiceType);
            Map<String, InvoiceExportDTO> targetMap = isCreditNote ? creditNoteMap : invoiceMap;

            targetMap.compute(invoiceUuid, (key, existing) -> {
                if (existing == null) {
                    return new InvoiceExportDTO(
                            invoiceUuid, invoiceNumber, invoiceDate,
                            clientName, clientUuid, projectName, projectUuid,
                            invoiceType, invoiceStatus, currency,
                            itemAmount, itemAmountDkk, discount, companyUuid);
                }
                existing.setOriginalAmount(existing.getOriginalAmount() + itemAmount);
                existing.setAmountDkk(existing.getAmountDkk() + itemAmountDkk);
                return existing;
            });

            // One InvoiceItemExportDTO per line item
            invoiceItems.add(new InvoiceItemExportDTO(
                    (String) row.get("item_uuid"),
                    invoiceUuid,
                    invoiceNumber,
                    invoiceDate,
                    (String) row.get("consultant_name"),
                    (String) row.get("consultant_uuid"),
                    (String) row.get("item_name"),
                    (String) row.get("item_description"),
                    ((Number) row.get("rate")).doubleValue(),
                    ((Number) row.get("hours")).doubleValue(),
                    itemAmount,
                    itemAmountDkk,
                    (String) row.get("item_origin"),
                    clientName,
                    projectName
            ));
        }

        List<InvoiceExportDTO> invoices    = new ArrayList<>(invoiceMap.values());
        List<InvoiceExportDTO> creditNotes = new ArrayList<>(creditNoteMap.values());

        log.debugf("getAccumulatedRevenueSourceData: %d invoices, %d items, %d credit notes",
                invoices.size(), invoiceItems.size(), creditNotes.size());

        return new RevenueSourceDataDTO(invoices, invoiceItems, creditNotes);
    }

    // ============================================================================
    // Expected Accumulated EBITDA (FY) — Chart E
    // ============================================================================

    /**
     * Returns 12 data points (one per fiscal month, Jul–Jun) showing monthly and accumulated
     * EBITDA for the fiscal year that contains {@code asOfDate}.
     *
     * <p><strong>Past months</strong> ({@code isActual=true}):
     * actual revenue and direct cost from {@code fact_project_financials_mat},
     * actual OPEX from {@code fact_opex}.
     *
     * <p><strong>Future months</strong> ({@code isActual=false}):
     * backlog revenue from {@code fact_backlog},
     * estimated cost = backlog × (1 - TTM gross margin %),
     * estimated OPEX = average monthly OPEX over trailing 12 months.
     *
     * @param asOfDate     Reference date (defaults to today)
     * @param sectors      Sector filter (nullable / empty = all)
     * @param serviceLines Service line filter (nullable / empty = all)
     * @param contractTypes Contract type filter (nullable / empty = all)
     * @param clientId     Single client UUID filter (nullable / blank = all)
     * @param companyIds   Company UUID filter (nullable / empty = all)
     * @return Exactly 12 DTOs sorted by fiscal month number
     */
    public List<MonthlyAccumulatedEbitdaDTO> getExpectedAccumulatedEBITDA(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate today = (asOfDate != null) ? asOfDate : LocalDate.now();
        String currentMonthKey = String.format("%04d%02d", today.getYear(), today.getMonthValue());

        // Fiscal year: July 1 → June 30
        int fiscalYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd   = LocalDate.of(fiscalYear + 1, 6, 30);

        String fyFromKey = String.format("%04d%02d", fyStart.getYear(), fyStart.getMonthValue());
        String fyToKey   = String.format("%04d%02d", fyEnd.getYear(), fyEnd.getMonthValue());

        // TTM window (trailing 12 months ending at end of previous month)
        LocalDate ttmEnd   = today.withDayOfMonth(1).minusDays(1); // last day of previous month
        LocalDate ttmStart = ttmEnd.minusMonths(11).withDayOfMonth(1);
        String ttmFromKey  = String.format("%04d%02d", ttmStart.getYear(), ttmStart.getMonthValue());
        String ttmToKey    = String.format("%04d%02d", ttmEnd.getYear(), ttmEnd.getMonthValue());

        log.debugf("getExpectedAccumulatedEBITDA: FY=%d (%s→%s), TTM (%s→%s), sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fiscalYear, fyFromKey, fyToKey, ttmFromKey, ttmToKey,
                sectors, serviceLines, contractTypes, clientId, companyIds);

        // 1. TTM gross margin: revenue from fact_company_revenue_mat (company-total),
        //    cost from GL (cost_type=DIRECT_COSTS), company-level only.
        //    Revenue KPIs always show company-total — dimension filters do not apply to revenue.
        double ttmRevenue = queryCompanyRevenue(ttmStart, ttmEnd, companyIds);
        double ttmCost    = queryActualCosts(ttmStart, ttmEnd, sectors, serviceLines, contractTypes, clientId, companyIds);
        double ttmGrossMarginPct = (ttmRevenue > 0) ? ((ttmRevenue - ttmCost) / ttmRevenue) * 100.0 : 0.0;

        // 2. Average monthly OPEX (OPEX-only, excluding salaries) and average monthly salaries
        //    from fact_opex over TTM period. Both are used for projected future months.
        double avgMonthlyOpex     = queryAvgMonthlyOpex(ttmFromKey, ttmToKey, companyIds);
        double avgMonthlySalaries = queryAvgMonthlySalaries(ttmFromKey, ttmToKey, companyIds);

        log.debugf("TTM gross margin=%.2f%%, avg monthly OPEX=%.2f DKK, avg monthly salaries=%.2f DKK",
                ttmGrossMarginPct, avgMonthlyOpex, avgMonthlySalaries);

        // 3. Actual FY revenue from fact_company_revenue_mat (company-total, companyIds only)
        //    and cost from GL (cost_type=DIRECT_COSTS) — merged by month.
        java.util.Map<String, Double> fyRevenueByMonth = queryCompanyRevenueByMonth(fyFromKey, fyToKey, companyIds);
        java.util.Map<String, double[]> fyCostByMonth  = queryMonthlyDirectCostByMonth(fyFromKey, fyToKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // Combine into the actualByMonth map: [revenue, cost] per month.
        // A month is "actual" if it has either revenue or cost data.
        java.util.Set<String> fyActualMonthKeys = new java.util.HashSet<>(fyRevenueByMonth.keySet());
        fyActualMonthKeys.addAll(fyCostByMonth.keySet());
        java.util.Map<String, double[]> actualByMonth = new java.util.HashMap<>();
        for (String mk : fyActualMonthKeys) {
            double rev  = fyRevenueByMonth.getOrDefault(mk, 0.0);
            double cost = fyCostByMonth.containsKey(mk) ? fyCostByMonth.get(mk)[0] : 0.0;
            actualByMonth.put(mk, new double[]{rev, cost});
        }

        // 4. Actual OPEX (OPEX-only) and salaries by month — separate fields per spec.
        java.util.Map<String, Double> opexByMonth     = queryMonthlyOpex(fyFromKey, fyToKey, companyIds);
        java.util.Map<String, Double> salariesByMonth = queryMonthlySalaries(fyFromKey, fyToKey, companyIds);

        // 4b. Actual internal invoice cost by month (intercompany INTERNAL invoices)
        java.util.Map<String, Double> internalCostByMonth = queryMonthlyInternalInvoiceCost(fyFromKey, fyToKey, companyIds);

        // 5. Backlog by month for future months
        java.util.Map<String, Double> backlogByMonth = queryBacklogByMonth(currentMonthKey, fyToKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 6. Build 12 data points
        List<MonthlyAccumulatedEbitdaDTO> result = new ArrayList<>(12);
        double accumulated = 0.0;

        for (int fiscalMonth = 1; fiscalMonth <= 12; fiscalMonth++) {
            int calMonth = (fiscalMonth <= 6) ? fiscalMonth + 6 : fiscalMonth - 6;
            int calYear  = (fiscalMonth <= 6) ? fiscalYear : fiscalYear + 1;
            String monthKey = String.format("%04d%02d", calYear, calMonth);

            boolean isActual = monthKey.compareTo(currentMonthKey) < 0
                    && actualByMonth.containsKey(monthKey);

            double monthRevenue;
            double monthDirectCost;
            double monthInternalCost;
            double monthSalaries;
            double monthOpex;

            if (isActual) {
                double[] revCost = actualByMonth.getOrDefault(monthKey, new double[]{0.0, 0.0});
                monthRevenue       = revCost[0];
                monthDirectCost    = revCost[1];
                monthInternalCost  = internalCostByMonth.getOrDefault(monthKey, 0.0);
                monthSalaries      = salariesByMonth.getOrDefault(monthKey, 0.0);
                monthOpex          = opexByMonth.getOrDefault(monthKey, 0.0);
            } else {
                // Future month: use backlog revenue with TTM margin estimation.
                // Internal invoice costs are not forecast — set to 0.
                monthRevenue      = backlogByMonth.getOrDefault(monthKey, 0.0);
                monthDirectCost   = monthRevenue * (1.0 - ttmGrossMarginPct / 100.0);
                monthInternalCost = 0.0;
                monthSalaries     = avgMonthlySalaries;
                monthOpex         = avgMonthlyOpex;
            }

            // Note: monthInternalCost is NOT subtracted here because GL-based direct costs
            // are already company-level only and do not overlap with intercompany invoices.
            double monthEbitda = monthRevenue - monthDirectCost - monthSalaries - monthOpex;
            accumulated += monthEbitda;

            result.add(new MonthlyAccumulatedEbitdaDTO(
                    monthKey,
                    calYear,
                    calMonth,
                    formatMonthLabel(calYear, calMonth),
                    fiscalMonth,
                    monthRevenue,
                    monthDirectCost,
                    monthInternalCost,
                    monthSalaries,
                    monthOpex,
                    monthEbitda,
                    accumulated,
                    isActual
            ));
        }

        log.debugf("getExpectedAccumulatedEBITDA: returning %d data points, accumulated EBITDA=%.2f DKK", result.size(), (Double) accumulated);
        return result;
    }

    // ============================================================================
    // EBITDA Source Data Export
    // ============================================================================

    /**
     * Returns all raw source records that feed the Expected Accumulated EBITDA chart,
     * grouped into five lists for Excel export (one per worksheet tab).
     *
     * <p>Data sources and filter scoping:
     * <ul>
     *   <li>Invoices + credit notes: same query as
     *       {@link #getAccumulatedRevenueSourceData} — all CXO dimension filters applied.</li>
     *   <li>Direct costs: {@code fact_project_financials_mat} at project×month grain —
     *       all CXO dimension filters applied.</li>
     *   <li>Internal invoices: QUEUED rows from {@code invoices} table + CREATED_GL rows from
     *       {@code finance_details} accounts 3050/3055/3070/3075/1350 — companyIds only.</li>
     *   <li>OPEX: raw {@code finance_details} GL entries excluding 'Varesalg',
     *       'Direkte omkostninger', 'Igangvaerende arbejde' categories — companyIds only.</li>
     * </ul>
     *
     * @param asOfDate      Reference date for fiscal year detection (nullable → today)
     * @param sectors       Sector filter (nullable / empty = all); applied to invoices + direct costs only
     * @param serviceLines  Service line filter (nullable / empty = all); applied to invoices + direct costs only
     * @param contractTypes Contract type filter (nullable / empty = all); applied to invoices + direct costs only
     * @param clientId      Single client UUID filter (nullable / blank = all); applied to invoices + direct costs only
     * @param companyIds    Company UUID filter (nullable / empty = all); applied to ALL data sources
     * @return Container with invoices, creditNotes, directCosts, internalInvoices, and opexEntries
     */
    public EbitdaSourceDataDTO getEbitdaSourceData(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        LocalDate today = (asOfDate != null) ? asOfDate : LocalDate.now();

        // Fiscal year: July 1 → June 30
        int fiscalYear = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd   = LocalDate.of(fiscalYear + 1, 6, 30);

        String fyFromKey = String.format("%04d%02d", fyStart.getYear(), fyStart.getMonthValue());
        String fyToKey   = String.format("%04d%02d", fyEnd.getYear(), fyEnd.getMonthValue());

        log.debugf("getEbitdaSourceData: FY=%d (%s→%s), sectors=%s, serviceLines=%s, contractTypes=%s, clientId=%s, companyIds=%s",
                fiscalYear, fyFromKey, fyToKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 1) Invoices + credit notes: reuse same project-scoped query as getAccumulatedRevenueSourceData
        List<InvoiceExportDTO> invoices    = new ArrayList<>();
        List<InvoiceExportDTO> creditNotes = new ArrayList<>();
        queryEbitdaInvoicesAndCreditNotes(fyStart, fyEnd, fyFromKey, fyToKey,
                sectors, serviceLines, contractTypes, clientId, companyIds,
                invoices, creditNotes);

        // 2) Direct costs: fact_project_financials_mat at project×month grain
        List<DirectCostRowDTO> directCosts = queryDirectCosts(
                fyFromKey, fyToKey, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 3) Internal invoices: QUEUED from invoices table + CREATED_GL from finance_details
        List<InternalInvoiceRowDTO> internalInvoices = queryInternalInvoices(fyStart, fyEnd, fyFromKey, fyToKey, companyIds);

        // 4) OPEX: raw finance_details GL entries, excluding non-OPEX categories
        List<OpexRowExportDTO> opexEntries = queryOpexEntries(fyStart, fyEnd, companyIds);

        log.debugf("getEbitdaSourceData: %d invoices, %d creditNotes, %d directCosts, %d internalInvoices, %d opexEntries",
                invoices.size(), creditNotes.size(), directCosts.size(), internalInvoices.size(), opexEntries.size());

        return new EbitdaSourceDataDTO(invoices, creditNotes, directCosts, internalInvoices, opexEntries);
    }

    /**
     * Helper: Query invoices and credit notes for the EBITDA source data export.
     * Uses the same project-resolution pattern as {@link #getAccumulatedRevenueSourceData}.
     * Results are written into the provided mutable lists.
     */
    private void queryEbitdaInvoicesAndCreditNotes(
            LocalDate fyStart, LocalDate fyEnd,
            String fyFromKey, String fyToKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds,
            List<InvoiceExportDTO> invoicesOut, List<InvoiceExportDTO> creditNotesOut) {

        // Step 1: Resolve matching project IDs from fact_project_financials_mat
        StringBuilder projectSql = new StringBuilder(
                "SELECT DISTINCT f.project_id FROM fact_project_financials_mat f " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty())      projectSql.append("AND f.companyuuid IN (:companyIds) ");
        if (sectors != null && !sectors.isEmpty())             projectSql.append("AND f.sector_id IN (:sectors) ");
        if (serviceLines != null && !serviceLines.isEmpty())   projectSql.append("AND f.service_line_id IN (:serviceLines) ");
        if (contractTypes != null && !contractTypes.isEmpty()) projectSql.append("AND f.contract_type_id IN (:contractTypes) ");
        if (clientId != null && !clientId.isBlank())           projectSql.append("AND f.client_id = :clientId ");

        Query projectQuery = em.createNativeQuery(projectSql.toString());
        projectQuery.setParameter("fromKey", fyFromKey);
        projectQuery.setParameter("toKey", fyToKey);
        if (companyIds != null && !companyIds.isEmpty())      projectQuery.setParameter("companyIds", companyIds);
        if (sectors != null && !sectors.isEmpty())             projectQuery.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty())   projectQuery.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) projectQuery.setParameter("contractTypes", contractTypes);
        if (clientId != null && !clientId.isBlank())           projectQuery.setParameter("clientId", clientId);

        @SuppressWarnings("unchecked")
        List<String> matchedProjects = projectQuery.getResultList();

        if (matchedProjects.isEmpty()) {
            log.debugf("getEbitdaSourceData: no matching projects — returning empty invoices/creditNotes");
            return;
        }

        // Step 2: Query invoice records for matched projects
        String invoiceSql =
                "SELECT " +
                "    i.uuid           AS invoice_uuid, " +
                "    i.invoicenumber  AS invoice_number, " +
                "    DATE_FORMAT(i.invoicedate, '%Y-%m-%d') AS invoice_date, " +
                "    i.clientname     AS client_name, " +
                "    p.clientuuid     AS client_uuid, " +
                "    i.projectname    AS project_name, " +
                "    i.projectuuid    AS project_uuid, " +
                "    i.type           AS invoice_type, " +
                "    i.status         AS invoice_status, " +
                "    i.currency       AS currency, " +
                "    COALESCE(i.discount, 0)  AS discount, " +
                "    COALESCE(i.companyuuid, '') AS company_uuid, " +
                "    ii.rate * ii.hours AS item_amount, " +
                "    ii.rate * ii.hours * CASE WHEN i.currency = 'DKK' THEN 1 " +
                "                             ELSE COALESCE(cur.conversion, 1) END AS item_amount_dkk " +
                "FROM invoices i " +
                "    INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid " +
                "    INNER JOIN project p ON p.uuid = i.projectuuid " +
                "    LEFT JOIN currences cur " +
                "        ON cur.currency = i.currency " +
                "        AND cur.month = DATE_FORMAT(i.invoicedate, '%Y%m') " +
                "WHERE i.projectuuid IN (:matchedProjects) " +
                "    AND i.invoicedate BETWEEN :fyStart AND :fyEnd " +
                "    AND i.status = 'CREATED' " +
                "    AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE') " +
                "    AND ii.rate IS NOT NULL " +
                "    AND ii.hours IS NOT NULL " +
                "    AND (i.currency = 'DKK' OR cur.uuid IS NOT NULL) " +
                (companyIds != null && !companyIds.isEmpty() ? "    AND i.companyuuid IN (:companyIds) " : "") +
                "ORDER BY i.invoicedate, i.invoicenumber";

        Query invoiceQuery = em.createNativeQuery(invoiceSql, Tuple.class);
        invoiceQuery.setParameter("matchedProjects", matchedProjects);
        invoiceQuery.setParameter("fyStart", fyStart);
        invoiceQuery.setParameter("fyEnd", fyEnd);
        if (companyIds != null && !companyIds.isEmpty()) invoiceQuery.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = invoiceQuery.getResultList();

        Map<String, InvoiceExportDTO> invoiceMap    = new LinkedHashMap<>();
        Map<String, InvoiceExportDTO> creditNoteMap = new LinkedHashMap<>();

        for (Tuple row : rows) {
            String invoiceUuid   = (String) row.get("invoice_uuid");
            int    invoiceNumber = ((Number) row.get("invoice_number")).intValue();
            String invoiceDate   = (String) row.get("invoice_date");
            String clientName    = (String) row.get("client_name");
            String clientUuid    = (String) row.get("client_uuid");
            String projectName   = (String) row.get("project_name");
            String projectUuid   = (String) row.get("project_uuid");
            String invoiceType   = (String) row.get("invoice_type");
            String invoiceStatus = (String) row.get("invoice_status");
            String currency      = (String) row.get("currency");
            double discount      = ((Number) row.get("discount")).doubleValue();
            String companyUuid   = (String) row.get("company_uuid");
            double itemAmount    = ((Number) row.get("item_amount")).doubleValue();
            double itemAmountDkk = ((Number) row.get("item_amount_dkk")).doubleValue();

            boolean isCreditNote = "CREDIT_NOTE".equals(invoiceType);
            Map<String, InvoiceExportDTO> targetMap = isCreditNote ? creditNoteMap : invoiceMap;

            targetMap.compute(invoiceUuid, (key, existing) -> {
                if (existing == null) {
                    return new InvoiceExportDTO(
                            invoiceUuid, invoiceNumber, invoiceDate,
                            clientName, clientUuid, projectName, projectUuid,
                            invoiceType, invoiceStatus, currency,
                            itemAmount, itemAmountDkk, discount, companyUuid);
                }
                existing.setOriginalAmount(existing.getOriginalAmount() + itemAmount);
                existing.setAmountDkk(existing.getAmountDkk() + itemAmountDkk);
                return existing;
            });
        }

        invoicesOut.addAll(invoiceMap.values());
        creditNotesOut.addAll(creditNoteMap.values());
    }

    /**
     * Helper: Query direct delivery costs from fact_project_financials_mat at project×month grain.
     * Applies all CXO dimension filters. Returns one row per distinct project×month×company combination.
     */
    private List<DirectCostRowDTO> queryDirectCosts(
            String fyFromKey, String fyToKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT " +
                "    f.companyuuid AS company_uuid, " +
                "    comp.name AS company_name, " +
                "    f.project_id AS project_uuid, " +
                "    p.name AS project_name, " +
                "    f.client_id AS client_uuid, " +
                "    c.name AS client_name, " +
                "    f.month_key, " +
                "    f.year, " +
                "    f.month_number, " +
                "    f.sector_id, " +
                "    f.service_line_id, " +
                "    f.contract_type_id, " +
                "    MAX(f.direct_delivery_cost_dkk) AS direct_delivery_cost_dkk " +
                "FROM fact_project_financials_mat f " +
                "    JOIN project p ON p.uuid = f.project_id " +
                "    LEFT JOIN client c ON c.uuid = f.client_id " +
                "    LEFT JOIN companies comp ON comp.uuid = f.companyuuid " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty())      sql.append("AND f.companyuuid IN (:companyIds) ");
        if (sectors != null && !sectors.isEmpty())             sql.append("AND f.sector_id IN (:sectors) ");
        if (serviceLines != null && !serviceLines.isEmpty())   sql.append("AND f.service_line_id IN (:serviceLines) ");
        if (contractTypes != null && !contractTypes.isEmpty()) sql.append("AND f.contract_type_id IN (:contractTypes) ");
        if (clientId != null && !clientId.isBlank())           sql.append("AND f.client_id = :clientId ");
        sql.append("GROUP BY f.companyuuid, comp.name, f.project_id, p.name, f.client_id, c.name, " +
                   "f.month_key, f.year, f.month_number, f.sector_id, f.service_line_id, f.contract_type_id " +
                   "ORDER BY f.month_key, p.name");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fyFromKey);
        query.setParameter("toKey", fyToKey);
        if (companyIds != null && !companyIds.isEmpty())      query.setParameter("companyIds", companyIds);
        if (sectors != null && !sectors.isEmpty())             query.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty())   query.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) query.setParameter("contractTypes", contractTypes);
        if (clientId != null && !clientId.isBlank())           query.setParameter("clientId", clientId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<DirectCostRowDTO> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String monthKey    = (String) row.get("month_key");
            int    year        = ((Number) row.get("year")).intValue();
            int    monthNumber = ((Number) row.get("month_number")).intValue();
            result.add(new DirectCostRowDTO(
                    (String) row.get("company_uuid"),
                    row.get("company_name") != null ? (String) row.get("company_name") : "",
                    (String) row.get("project_uuid"),
                    row.get("project_name") != null ? (String) row.get("project_name") : "",
                    (String) row.get("client_uuid"),
                    row.get("client_name") != null ? (String) row.get("client_name") : "",
                    monthKey,
                    formatMonthLabel(year, monthNumber),
                    (String) row.get("sector_id"),
                    (String) row.get("service_line_id"),
                    (String) row.get("contract_type_id"),
                    ((Number) row.get("direct_delivery_cost_dkk")).doubleValue()
            ));
        }
        return result;
    }

    /**
     * Helper: Query intercompany (INTERNAL) invoice cost rows for the fiscal year.
     * Returns two sets of rows:
     * <ul>
     *   <li>QUEUED_INVOICE: one row per QUEUED INTERNAL invoice, with cost from invoiceitems.</li>
     *   <li>CREATED_GL: one row per finance_details GL entry in accounts 3050/3055/3070/3075/1350.</li>
     * </ul>
     * Filter: companyIds applies to debtor company only. No sector/serviceLine/contractType/clientId filter.
     */
    private List<InternalInvoiceRowDTO> queryInternalInvoices(
            LocalDate fyStart, LocalDate fyEnd,
            String fyFromKey, String fyToKey,
            Set<String> companyIds) {

        List<InternalInvoiceRowDTO> result = new ArrayList<>();

        // Part A: QUEUED internal invoices — one row per invoice
        StringBuilder queuedSql = new StringBuilder(
                "SELECT " +
                "    i.uuid AS invoice_uuid, " +
                "    i.invoicenumber AS invoice_number, " +
                "    DATE_FORMAT(i.invoicedate, '%Y-%m-%d') AS invoice_date, " +
                "    CONCAT(LPAD(YEAR(i.invoicedate), 4, '0'), LPAD(MONTH(i.invoicedate), 2, '0')) AS month_key, " +
                "    i.companyuuid AS issuer_company_uuid, " +
                "    i.debtor_companyuuid AS debtor_company_uuid, " +
                "    i.invoice_ref_uuid AS invoice_ref_uuid, " +
                "    i.status AS invoice_status, " +
                "    i.specificdescription AS description, " +
                "    SUM(ii.rate * ii.hours * " +
                "        CASE WHEN i.currency = 'DKK' THEN 1.0 " +
                "             ELSE COALESCE((SELECT c.conversion FROM currences c " +
                "                           WHERE c.currency = i.currency " +
                "                             AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m') LIMIT 1), 1.0) " +
                "        END) AS amount_dkk " +
                "FROM invoices i " +
                "    JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid " +
                "WHERE i.type = 'INTERNAL' " +
                "  AND i.status = 'QUEUED' " +
                "  AND i.invoicedate BETWEEN :fyStart AND :fyEnd " +
                "  AND i.debtor_companyuuid IS NOT NULL " +
                "  AND ii.rate IS NOT NULL " +
                "  AND ii.hours IS NOT NULL "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            queuedSql.append("  AND i.debtor_companyuuid IN (:companyIds) ");
        }
        queuedSql.append("GROUP BY i.uuid, i.invoicenumber, i.invoicedate, i.companyuuid, " +
                         "i.debtor_companyuuid, i.invoice_ref_uuid, i.status, i.specificdescription " +
                         "ORDER BY i.invoicedate, i.invoicenumber");

        Query queuedQuery = em.createNativeQuery(queuedSql.toString(), Tuple.class);
        queuedQuery.setParameter("fyStart", fyStart);
        queuedQuery.setParameter("fyEnd", fyEnd);
        if (companyIds != null && !companyIds.isEmpty()) {
            queuedQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> queuedRows = queuedQuery.getResultList();

        for (Tuple row : queuedRows) {
            result.add(new InternalInvoiceRowDTO(
                    (String) row.get("invoice_uuid"),
                    ((Number) row.get("invoice_number")).intValue(),
                    (String) row.get("invoice_date"),
                    (String) row.get("month_key"),
                    (String) row.get("issuer_company_uuid"),
                    (String) row.get("debtor_company_uuid"),
                    (String) row.get("invoice_ref_uuid"),
                    (String) row.get("invoice_status"),
                    "QUEUED_INVOICE",
                    row.get("amount_dkk") != null ? ((Number) row.get("amount_dkk")).doubleValue() : 0.0,
                    row.get("description") != null ? (String) row.get("description") : ""
            ));
        }

        // Part B: CREATED_GL — one row per finance_details GL entry in internal invoice accounts
        // Accounts: 3050, 3055, 3070, 3075 (TW A/S as debtor) and 1350 (subsidiaries as debtor)
        StringBuilder glSql = new StringBuilder(
                "SELECT " +
                "    fd.companyuuid AS debtor_company_uuid, " +
                "    fd.accountnumber AS account_number, " +
                "    DATE_FORMAT(fd.expensedate, '%Y-%m-%d') AS expense_date, " +
                "    CONCAT(LPAD(YEAR(fd.expensedate), 4, '0'), LPAD(MONTH(fd.expensedate), 2, '0')) AS month_key, " +
                "    fd.amount AS amount_dkk, " +
                "    fd.entrynumber AS entry_number, " +
                "    fd.text AS gl_text " +
                "FROM finance_details fd " +
                "WHERE fd.expensedate BETWEEN :fyStart AND :fyEnd " +
                "  AND fd.amount != 0 " +
                "  AND fd.accountnumber IN (3050, 3055, 3070, 3075, 1350) "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            glSql.append("  AND fd.companyuuid IN (:companyIds) ");
        }
        glSql.append("ORDER BY fd.expensedate, fd.entrynumber");

        Query glQuery = em.createNativeQuery(glSql.toString(), Tuple.class);
        glQuery.setParameter("fyStart", fyStart);
        glQuery.setParameter("fyEnd", fyEnd);
        if (companyIds != null && !companyIds.isEmpty()) {
            glQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> glRows = glQuery.getResultList();

        for (Tuple row : glRows) {
            result.add(new InternalInvoiceRowDTO(
                    null,
                    0,
                    (String) row.get("expense_date"),
                    (String) row.get("month_key"),
                    null,
                    (String) row.get("debtor_company_uuid"),
                    null,
                    null,
                    "CREATED_GL",
                    row.get("amount_dkk") != null ? ((Number) row.get("amount_dkk")).doubleValue() : 0.0,
                    row.get("gl_text") != null ? (String) row.get("gl_text") : ""
            ));
        }

        return result;
    }

    /**
     * Helper: Query raw OPEX GL entries from finance_details for the fiscal year.
     * Joins to accounting_accounts and accounting_categories for account metadata.
     * Excludes 'Varesalg', 'Direkte omkostninger', and 'Igangvaerende arbejde' categories.
     * Scoped by companyIds only — no sector/serviceLine/contractType/clientId filter.
     */
    private List<OpexRowExportDTO> queryOpexEntries(
            LocalDate fyStart, LocalDate fyEnd,
            Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT " +
                "    fd.companyuuid AS company_uuid, " +
                "    comp.name AS company_name, " +
                "    fd.accountnumber AS account_number, " +
                "    aa.account_description, " +
                "    ac.groupname AS category_groupname, " +
                "    DATE_FORMAT(fd.expensedate, '%Y-%m-%d') AS expense_date, " +
                "    CONCAT(LPAD(YEAR(fd.expensedate), 4, '0'), LPAD(MONTH(fd.expensedate), 2, '0')) AS month_key, " +
                "    YEAR(fd.expensedate) AS year_val, " +
                "    MONTH(fd.expensedate) AS month_val, " +
                "    fd.amount AS amount_dkk, " +
                "    fd.entrynumber AS entry_number, " +
                "    fd.text AS gl_text " +
                "FROM finance_details fd " +
                "    INNER JOIN accounting_accounts aa " +
                "        ON fd.accountnumber = aa.account_code AND fd.companyuuid = aa.companyuuid " +
                "    INNER JOIN accounting_categories ac ON aa.categoryuuid = ac.uuid " +
                "    LEFT JOIN companies comp ON comp.uuid = fd.companyuuid " +
                "WHERE fd.expensedate BETWEEN :fyStart AND :fyEnd " +
                "  AND fd.amount != 0 " +
                "  AND (aa.account_code >= '3000' AND aa.account_code < '6000') " +
                "  AND ac.groupname NOT IN ('Varesalg', 'Direkte omkostninger', 'Igangvaerende arbejde') "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND fd.companyuuid IN (:companyIds) ");
        }
        sql.append("ORDER BY fd.expensedate, fd.companyuuid, fd.accountnumber, fd.entrynumber");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fyStart", fyStart);
        query.setParameter("fyEnd", fyEnd);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<OpexRowExportDTO> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            int year        = ((Number) row.get("year_val")).intValue();
            int monthNumber = ((Number) row.get("month_val")).intValue();
            result.add(new OpexRowExportDTO(
                    (String) row.get("company_uuid"),
                    row.get("company_name") != null ? (String) row.get("company_name") : "",
                    ((Number) row.get("account_number")).intValue(),
                    row.get("account_description") != null ? (String) row.get("account_description") : "",
                    row.get("category_groupname") != null ? (String) row.get("category_groupname") : "",
                    (String) row.get("expense_date"),
                    (String) row.get("month_key"),
                    formatMonthLabel(year, monthNumber),
                    row.get("amount_dkk") != null ? ((Number) row.get("amount_dkk")).doubleValue() : 0.0,
                    row.get("entry_number") != null ? ((Number) row.get("entry_number")).intValue() : 0,
                    row.get("gl_text") != null ? (String) row.get("gl_text") : ""
            ));
        }
        return result;
    }

    /**
     * Helper: Query TTM total revenue and direct delivery cost (deduplicated).
     * Returns double[2]: {revenue, cost}.
     */
    private double[] queryTTMRevenueAndCost(
            String fromKey, String toKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder inner = new StringBuilder(
                "SELECT f.project_id, f.month_key, " +
                "MAX(f.recognized_revenue_dkk) AS revenue, " +
                "MAX(f.direct_delivery_cost_dkk) AS cost " +
                "FROM fact_project_financials_mat f " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) inner.append("AND f.companyuuid IN (:companyIds) ");
        if (sectors != null && !sectors.isEmpty()) inner.append("AND f.sector_id IN (:sectors) ");
        if (serviceLines != null && !serviceLines.isEmpty()) inner.append("AND f.service_line_id IN (:serviceLines) ");
        if (contractTypes != null && !contractTypes.isEmpty()) inner.append("AND f.contract_type_id IN (:contractTypes) ");
        if (clientId != null && !clientId.isBlank()) inner.append("AND f.client_id = :clientId ");
        inner.append("GROUP BY f.project_id, f.month_key");

        String sql = "SELECT COALESCE(SUM(d.revenue), 0.0), COALESCE(SUM(d.cost), 0.0) FROM (" + inner + ") AS d";
        Query query = em.createNativeQuery(sql);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) query.setParameter("companyIds", companyIds);
        if (sectors != null && !sectors.isEmpty()) query.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty()) query.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) query.setParameter("contractTypes", contractTypes);
        if (clientId != null && !clientId.isBlank()) query.setParameter("clientId", clientId);

        Object[] row = (Object[]) query.getSingleResult();
        return new double[]{((Number) row[0]).doubleValue(), ((Number) row[1]).doubleValue()};
    }

    /**
     * Helper: Query average monthly OPEX (OPEX-only, excluding SALARIES) over a TTM window.
     * Delegates to {@link DistributionAwareOpexProvider} for FY-aware data source selection.
     * Filters to non-payroll rows (isPayrollFlag = false) and divides total by distinct month count.
     */
    private double queryAvgMonthlyOpex(String fromKey, String toKey, Set<String> companyIds) {
        List<dk.trustworks.intranet.aggregates.finance.dto.OpexRow> rows =
                opexProvider.getDistributionAwareOpex(fromKey, toKey, companyIds, null, null);
        double totalOpex = 0.0;
        java.util.Set<String> months = new java.util.HashSet<>();
        for (dk.trustworks.intranet.aggregates.finance.dto.OpexRow row : rows) {
            if (!row.isPayrollFlag()) {
                totalOpex += row.opexAmountDkk();
                months.add(row.monthKey());
            }
        }
        return months.isEmpty() ? 0.0 : totalOpex / months.size();
    }

    /**
     * Helper: Query average monthly salary costs (SALARIES only) over a TTM window.
     * Delegates to {@link DistributionAwareOpexProvider} for FY-aware data source selection.
     * Filters to payroll rows (isPayrollFlag = true) and divides total by distinct month count.
     * Used to populate the {@code avgMonthlySalaries} estimate for projected future months.
     */
    private double queryAvgMonthlySalaries(String fromKey, String toKey, Set<String> companyIds) {
        List<dk.trustworks.intranet.aggregates.finance.dto.OpexRow> rows =
                opexProvider.getDistributionAwareOpex(fromKey, toKey, companyIds, null, null);
        double totalSalaries = 0.0;
        java.util.Set<String> months = new java.util.HashSet<>();
        for (dk.trustworks.intranet.aggregates.finance.dto.OpexRow row : rows) {
            if (row.isPayrollFlag()) {
                totalSalaries += row.opexAmountDkk();
                months.add(row.monthKey());
            }
        }
        return months.isEmpty() ? 0.0 : totalSalaries / months.size();
    }

    /**
     * Helper: Query monthly revenue and direct cost (deduplicated) for EBITDA calculation.
     * Returns map of monthKey → double[]{revenue, cost}.
     */
    private java.util.Map<String, double[]> queryMonthlyRevenueAndCost(
            String fromKey, String toKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder inner = new StringBuilder(
                "SELECT f.project_id, f.month_key, f.year, f.month_number, " +
                "MAX(f.recognized_revenue_dkk) AS revenue, " +
                "MAX(f.direct_delivery_cost_dkk) AS cost " +
                "FROM fact_project_financials_mat f " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) inner.append("AND f.companyuuid IN (:companyIds) ");
        if (sectors != null && !sectors.isEmpty()) inner.append("AND f.sector_id IN (:sectors) ");
        if (serviceLines != null && !serviceLines.isEmpty()) inner.append("AND f.service_line_id IN (:serviceLines) ");
        if (contractTypes != null && !contractTypes.isEmpty()) inner.append("AND f.contract_type_id IN (:contractTypes) ");
        if (clientId != null && !clientId.isBlank()) inner.append("AND f.client_id = :clientId ");
        inner.append("GROUP BY f.project_id, f.month_key, f.year, f.month_number");

        String sql = "SELECT d.month_key, SUM(d.revenue), SUM(d.cost) FROM (" + inner + ") AS d " +
                     "GROUP BY d.month_key ORDER BY d.month_key";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) query.setParameter("companyIds", companyIds);
        if (sectors != null && !sectors.isEmpty()) query.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty()) query.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) query.setParameter("contractTypes", contractTypes);
        if (clientId != null && !clientId.isBlank()) query.setParameter("clientId", clientId);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        java.util.Map<String, double[]> result = new java.util.HashMap<>();
        for (Tuple row : rows) {
            String mk = (String) row.get(0);
            double rev  = row.get(1) != null ? ((Number) row.get(1)).doubleValue() : 0.0;
            double cost = row.get(2) != null ? ((Number) row.get(2)).doubleValue() : 0.0;
            result.put(mk, new double[]{rev, cost});
        }
        return result;
    }

    /**
     * Helper: Query monthly direct costs from GL ({@code finance_details}) by month.
     * Returns map of monthKey → double[]{cost} (single-element array for uniform access).
     *
     * <p>Source changed from {@code fact_project_financials_mat} to actual GL entries classified
     * as {@code cost_type = 'DIRECT_COSTS'} in {@code accounting_accounts}. This reflects
     * what was actually booked in the accounting system rather than an estimate.
     *
     * <p><strong>Dimension filter note:</strong> GL-based direct costs have no project, sector,
     * service-line, or client attribution. Dimension filters are accepted for signature
     * compatibility but are intentionally ignored. Only {@code companyIds} applies.
     */
    private java.util.Map<String, double[]> queryMonthlyDirectCostByMonth(
            String fromKey, String toKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT DATE_FORMAT(fd.expensedate, '%Y%m') AS month_key, " +
                "       COALESCE(SUM(ABS(fd.amount)), 0.0) AS cost " +
                "FROM finance_details fd " +
                "INNER JOIN accounting_accounts aa " +
                "    ON fd.accountnumber = aa.account_code " +
                "    AND fd.companyuuid  = aa.companyuuid " +
                "WHERE aa.cost_type = 'DIRECT_COSTS' " +
                "  AND DATE_FORMAT(fd.expensedate, '%Y%m') BETWEEN :fromKey AND :toKey " +
                "  AND fd.amount != 0 "
        );
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND fd.companyuuid IN (:companyIds) ");
        }
        sql.append("GROUP BY DATE_FORMAT(fd.expensedate, '%Y%m') ORDER BY month_key");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        java.util.Map<String, double[]> result = new java.util.HashMap<>();
        for (Tuple row : rows) {
            String mk = (String) row.get("month_key");
            double cost = row.get("cost") != null ? ((Number) row.get("cost")).doubleValue() : 0.0;
            result.put(mk, new double[]{cost});
        }
        return result;
    }

    /**
     * Helper: Query actual OPEX by month (excludes SALARIES — cost_type = 'OPEX' only).
     * Delegates to {@link DistributionAwareOpexProvider} for FY-aware data source selection,
     * then filters returned rows to OPEX cost type only.
     * Returns map of monthKey → total opex_amount_dkk for OPEX rows.
     */
    private java.util.Map<String, Double> queryMonthlyOpex(String fromKey, String toKey, Set<String> companyIds) {
        // DistributionAwareOpexProvider returns both OPEX and SALARIES rows.
        // We filter to OPEX-only here; salaries are queried separately via queryMonthlySalaries.
        List<dk.trustworks.intranet.aggregates.finance.dto.OpexRow> rows =
                opexProvider.getDistributionAwareOpex(fromKey, toKey, companyIds, null, null);
        java.util.Map<String, Double> byMonth = new java.util.TreeMap<>();
        for (dk.trustworks.intranet.aggregates.finance.dto.OpexRow row : rows) {
            if (!row.isPayrollFlag()) {
                byMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
            }
        }
        return byMonth;
    }

    /**
     * Helper: Query salary costs by month (cost_type = 'SALARIES' only).
     * Delegates to {@link DistributionAwareOpexProvider} for FY-aware data source selection,
     * then filters returned rows to SALARIES cost type only (isPayrollFlag = true).
     * Returns map of monthKey → total salary_amount_dkk.
     *
     * <p>Salary amounts are kept separate from OPEX so the EBITDA API can return them
     * in a dedicated {@code monthlySalariesDkk} field, enabling the frontend to render
     * SALARIES as a distinct stacked segment within the Operating Costs bar.
     */
    private java.util.Map<String, Double> queryMonthlySalaries(String fromKey, String toKey, Set<String> companyIds) {
        List<dk.trustworks.intranet.aggregates.finance.dto.OpexRow> rows =
                opexProvider.getDistributionAwareOpex(fromKey, toKey, companyIds, null, null);
        java.util.Map<String, Double> byMonth = new java.util.TreeMap<>();
        for (dk.trustworks.intranet.aggregates.finance.dto.OpexRow row : rows) {
            if (row.isPayrollFlag()) {
                byMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
            }
        }
        return byMonth;
    }

    /**
     * Helper: Query monthly internal invoice cost from fact_internal_invoice_cost_mat.
     * Returns map of monthKey → internal_invoice_cost_dkk.
     * Values may be negative for reversal months (SUM, not ABS — per view design).
     * Covers only actual months; future months should use 0.0 (not forecast).
     */
    private java.util.Map<String, Double> queryMonthlyInternalInvoiceCost(
            String fromKey, String toKey, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT month_key, COALESCE(SUM(internal_invoice_cost_dkk), 0.0) AS internal_cost " +
                "FROM fact_internal_invoice_cost_mat " +
                "WHERE month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) sql.append("AND company_id IN (:companyIds) ");
        sql.append("GROUP BY month_key ORDER BY month_key");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        java.util.Map<String, Double> result = new java.util.HashMap<>();
        for (Tuple row : rows) {
            result.put((String) row.get("month_key"), ((Number) row.get("internal_cost")).doubleValue());
        }
        return result;
    }

    /**
     * Helper: Query total internal invoice cost over a time window (e.g., TTM).
     * Returns the sum of internal_invoice_cost_dkk across all months in the range.
     * Used by getGrossMarginTTM to include intercompany costs in gross margin %.
     */
    private double queryTTMInternalInvoiceCost(String fromKey, String toKey, Set<String> companyIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(internal_invoice_cost_dkk), 0.0) " +
                "FROM fact_internal_invoice_cost_mat " +
                "WHERE month_key BETWEEN :fromKey AND :toKey "
        );
        if (companyIds != null && !companyIds.isEmpty()) sql.append("AND company_id IN (:companyIds) ");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) query.setParameter("companyIds", companyIds);

        return ((Number) query.getSingleResult()).doubleValue();
    }

    /**
     * Helper: Query backlog revenue from fact_backlog for future fiscal months.
     * Returns map of monthKey → backlog_revenue_dkk.
     */
    private java.util.Map<String, Double> queryBacklogByMonth(
            String fromMonthKey, String toMonthKey,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder(
                "SELECT delivery_month_key AS month_key, SUM(backlog_revenue_dkk) AS backlog " +
                "FROM fact_backlog " +
                "WHERE delivery_month_key COLLATE utf8mb4_general_ci BETWEEN :fromMonthKey AND :toMonthKey " +
                "  AND project_status COLLATE utf8mb4_general_ci = 'ACTIVE' "
        );
        if (sectors != null && !sectors.isEmpty()) sql.append("AND sector_id IN (:sectors) ");
        if (serviceLines != null && !serviceLines.isEmpty()) sql.append("AND service_line_id IN (:serviceLines) ");
        if (contractTypes != null && !contractTypes.isEmpty()) sql.append("AND contract_type_id IN (:contractTypes) ");
        if (clientId != null && !clientId.isBlank()) sql.append("AND client_id = :clientId ");
        if (companyIds != null && !companyIds.isEmpty()) sql.append("AND company_id IN (:companyIds) ");
        sql.append("GROUP BY delivery_month_key ORDER BY delivery_month_key");

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);
        if (sectors != null && !sectors.isEmpty()) query.setParameter("sectors", sectors);
        if (serviceLines != null && !serviceLines.isEmpty()) query.setParameter("serviceLines", serviceLines);
        if (contractTypes != null && !contractTypes.isEmpty()) query.setParameter("contractTypes", contractTypes);
        if (clientId != null && !clientId.isBlank()) query.setParameter("clientId", clientId);
        if (companyIds != null && !companyIds.isEmpty()) query.setParameter("companyIds", companyIds);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        java.util.Map<String, Double> result = new java.util.HashMap<>();
        for (Tuple row : rows) {
            result.put((String) row.get("month_key"), ((Number) row.get("backlog")).doubleValue());
        }
        return result;
    }
}
