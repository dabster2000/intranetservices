package dk.trustworks.intranet.aggregates.finance.services;

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
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexBridgeDTO;
import dk.trustworks.intranet.aggregates.finance.dto.OpexDetailRowDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RealizationRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RepeatBusinessShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenuePerBillableFTETTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueYTDDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.Top5ClientsShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TTMRevenueGrowthDTO;
import dk.trustworks.intranet.aggregates.finance.dto.VoluntaryAttritionDTO;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for CxO dashboard finance aggregation.
 * Queries the fact_project_financials view for revenue and margin trends.
 */
@JBossLog
@ApplicationScoped
public class CxoFinanceService {

    @Inject
    EntityManager em;

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

        // Helper to build SQL with optional company filter (for DBs where fact view lacks companyuuid)
        java.util.function.Function<Boolean, String> sqlBuilder = includeCompanyFilter -> {
            // NOTE: Added COLLATE to fix collation mismatch between month_key column and String.format() output
            StringBuilder sql = new StringBuilder(
                    "SELECT " +
                            "    f.month_key, " +
                            "    f.year, " +
                            "    f.month_number, " +
                            "    SUM(f.recognized_revenue_dkk) AS revenue, " +
                            "    SUM(f.direct_delivery_cost_dkk) AS cost " +
                            "FROM fact_project_financials f " +
                            "WHERE 1=1 "
            );

            // Time range filter
            sql.append("  AND CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :fromMonthKey ")
                    .append("  AND CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :toMonthKey ");

            // Conditional sector filter
            if (sectors != null && !sectors.isEmpty()) {
                sql.append("  AND f.sector_id IN (:sectors) ");
            }

            // Conditional service line filter
            if (serviceLines != null && !serviceLines.isEmpty()) {
                sql.append("  AND f.service_line_id IN (:serviceLines) ");
            }

            // Conditional contract type filter
            if (contractTypes != null && !contractTypes.isEmpty()) {
                sql.append("  AND f.contract_type_id IN (:contractTypes) ");
            }

            // Conditional client filter
            if (clientId != null && !clientId.isBlank()) {
                sql.append("  AND f.client_id = :clientId ");
            }

            // Conditional company filter (only when requested and supported)
            if (includeCompanyFilter && companyIds != null && !companyIds.isEmpty()) {
                sql.append("  AND f.companyuuid IN (:companyIds) ");
            }

            sql.append("GROUP BY f.year, f.month_number, f.month_key ")
                    .append("ORDER BY f.year ASC, f.month_number ASC");

            return sql.toString();
        };

        // Try with company filter first (if provided). If DB doesn't have the column yet, fall back without it
        boolean wantCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = sqlBuilder.apply(wantCompanyFilter);

        List<Tuple> results;
        try {
            var query = em.createNativeQuery(sql, Tuple.class);
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
            if (wantCompanyFilter) {
                query.setParameter("companyIds", companyIds);
            }

            @SuppressWarnings("unchecked")
            List<Tuple> tmp = query.getResultList();
            results = tmp;
        } catch (RuntimeException ex) {
            // Detect missing column error and retry without company filter
            Throwable cause = ex;
            String errorMessage = ex.getMessage();
            while (cause.getCause() != null) {
                cause = cause.getCause();
                if (cause.getMessage() != null) {
                    errorMessage = cause.getMessage();
                }
            }
            boolean missingCompanyColumn = errorMessage != null && errorMessage.toLowerCase().contains("unknown column 'f.companyuuid'");
            if (wantCompanyFilter && missingCompanyColumn) {
                log.warnf("fact_project_financials missing column companyuuid; retrying without company filter. Error: %s", errorMessage);
                // Retry without company filter
                var retryQuery = em.createNativeQuery(sqlBuilder.apply(false), Tuple.class);
                retryQuery.setParameter("fromMonthKey", fromMonthKey);
                retryQuery.setParameter("toMonthKey", toMonthKey);
                if (sectors != null && !sectors.isEmpty()) {
                    retryQuery.setParameter("sectors", sectors);
                }
                if (serviceLines != null && !serviceLines.isEmpty()) {
                    retryQuery.setParameter("serviceLines", serviceLines);
                }
                if (contractTypes != null && !contractTypes.isEmpty()) {
                    retryQuery.setParameter("contractTypes", contractTypes);
                }
                if (clientId != null && !clientId.isBlank()) {
                    retryQuery.setParameter("clientId", clientId);
                }
                @SuppressWarnings("unchecked")
                List<Tuple> tmp = retryQuery.getResultList();
                results = tmp;
            } else {
                throw ex; // rethrow other errors
            }
        }

        log.debugf("Query returned %d rows", results.size());

        // Map results to DTOs, computing margin percentage
        List<MonthlyRevenueMarginDTO> dtos = new ArrayList<>();
        for (Tuple row : results) {
            String monthKey = (String) row.get("month_key");
            int year = ((Number) row.get("year")).intValue();
            int monthNumber = ((Number) row.get("month_number")).intValue();
            double revenue = ((Number) row.get("revenue")).doubleValue();
            double cost = ((Number) row.get("cost")).doubleValue();

            // Calculate margin percentage: (revenue - cost) / revenue * 100
            // Null if revenue is zero (avoid division by zero)
            Double marginPercent = null;
            if (revenue > 0) {
                marginPercent = ((revenue - cost) / revenue) * 100.0;
            }

            String monthLabel = formatMonthLabel(year, monthNumber);

            MonthlyRevenueMarginDTO dto = new MonthlyRevenueMarginDTO(
                    monthKey,
                    year,
                    monthNumber,
                    monthLabel,
                    revenue,
                    cost,
                    marginPercent
            );

            dtos.add(dto);
            log.debugf("Month %s: revenue=%.2f, cost=%.2f, margin=%.2f%%", monthKey, revenue, cost, marginPercent != null ? marginPercent : 0);
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
     * Queries the fact_user_utilization view aggregated by month.
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

        // Convert to YYYYMM month keys for efficient filtering
        String fromMonthKey = String.format("%04d%02d", normalizedFromDate.getYear(), normalizedFromDate.getMonthValue());
        String toMonthKey = String.format("%04d%02d", normalizedToDate.getYear(), normalizedToDate.getMonthValue());

        log.debugf("getUtilizationTrend: fromDate=%s (%s), toDate=%s (%s), practices=%s, companyIds=%s",
                normalizedFromDate, fromMonthKey, normalizedToDate, toMonthKey, practices, companyIds);

        // Helper to build SQL with optional company filter
        java.util.function.Function<Boolean, String> sqlBuilder = includeCompanyFilter -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT " +
                            "    f.month_key, " +
                            "    f.year, " +
                            "    f.month_number, " +
                            "    SUM(f.billable_hours) AS billable_hours, " +
                            "    SUM(f.vacation_hours + f.sick_hours + f.maternity_leave_hours + " +
                            "        f.non_payd_leave_hours + f.paid_leave_hours) AS absence_hours, " +
                            "    SUM(f.net_available_hours) AS net_available_hours, " +
                            "    SUM(f.gross_available_hours) AS gross_available_hours " +
                            "FROM fact_user_utilization f " +
                            "WHERE 1=1 "
            );

            // Time range filter
            // NOTE: Added COLLATE to fix collation mismatch between month_key column and String.format() output
            sql.append("  AND CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :fromMonthKey ")
                    .append("  AND CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :toMonthKey ");

            // Conditional practice filter
            if (practices != null && !practices.isEmpty()) {
                sql.append("  AND f.practice_id IN (:practices) ");
            }

            // Conditional company filter (only when requested and supported)
            if (includeCompanyFilter && companyIds != null && !companyIds.isEmpty()) {
                sql.append("  AND f.companyuuid IN (:companyIds) ");
            }

            sql.append("GROUP BY f.year, f.month_number, f.month_key ")
                    .append("ORDER BY f.year ASC, f.month_number ASC");

            return sql.toString();
        };

        // Try with company filter first (if provided). Fall back if DB doesn't have the column
        boolean wantCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String sql = sqlBuilder.apply(wantCompanyFilter);

        List<Tuple> results;
        try {
            var query = em.createNativeQuery(sql, Tuple.class);
            query.setParameter("fromMonthKey", fromMonthKey);
            query.setParameter("toMonthKey", toMonthKey);

            if (practices != null && !practices.isEmpty()) {
                query.setParameter("practices", practices);
            }
            if (wantCompanyFilter) {
                query.setParameter("companyIds", companyIds);
            }

            @SuppressWarnings("unchecked")
            List<Tuple> tmp = query.getResultList();
            results = tmp;
        } catch (RuntimeException ex) {
            // Detect missing column error and retry without company filter
            Throwable cause = ex;
            String errorMessage = ex.getMessage();
            while (cause.getCause() != null) {
                cause = cause.getCause();
                if (cause.getMessage() != null) {
                    errorMessage = cause.getMessage();
                }
            }
            boolean missingCompanyColumn = errorMessage != null && errorMessage.toLowerCase().contains("unknown column 'f.companyuuid'");
            if (wantCompanyFilter && missingCompanyColumn) {
                log.warnf("fact_user_utilization missing column companyuuid; retrying without company filter. Error: %s", errorMessage);
                // Retry without company filter
                var retryQuery = em.createNativeQuery(sqlBuilder.apply(false), Tuple.class);
                retryQuery.setParameter("fromMonthKey", fromMonthKey);
                retryQuery.setParameter("toMonthKey", toMonthKey);
                if (practices != null && !practices.isEmpty()) {
                    retryQuery.setParameter("practices", practices);
                }
                @SuppressWarnings("unchecked")
                List<Tuple> tmp = retryQuery.getResultList();
                results = tmp;
            } else {
                throw ex; // rethrow other errors
            }
        }

        log.debugf("Utilization query returned %d rows", results.size());

        // Map results to DTOs, computing non-billable hours and utilization percentage
        List<MonthlyUtilizationDTO> dtos = new ArrayList<>();
        for (Tuple row : results) {
            String monthKey = (String) row.get("month_key");
            int year = ((Number) row.get("year")).intValue();
            int monthNumber = ((Number) row.get("month_number")).intValue();
            double billableHours = ((Number) row.get("billable_hours")).doubleValue();
            double absenceHours = ((Number) row.get("absence_hours")).doubleValue();
            double netAvailableHours = ((Number) row.get("net_available_hours")).doubleValue();
            double grossAvailableHours = ((Number) row.get("gross_available_hours")).doubleValue();

            // Calculate non-billable hours: net_available - billable - absence
            // This represents time worked but not billed (internal projects, admin, etc.)
            double nonBillableHours = Math.max(0, netAvailableHours - billableHours - absenceHours);

            // Calculate utilization percentage: billable / (net_available - absence) * 100
            // This measures utilization against actual working hours
            // net_available already excludes unavailable_hours (holidays, half-day Fridays)
            // Then subtract absence (vacation, sick, maternity, non-paid leave, paid leave)
            // Null if working hours is zero (avoid division by zero)
            double workingHours = netAvailableHours - absenceHours;
            Double utilizationPercent = null;
            if (workingHours > 0) {
                utilizationPercent = (billableHours / workingHours) * 100.0;
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

        // 2. Query actual revenue YTD (current fiscal year)
        double actualYTD = queryActualRevenue(
                fiscalYearStart, ytdEnd, sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 3. Query budget YTD (current fiscal year)
        double budgetYTD = queryBudgetRevenue(
                fiscalYearStart, ytdEnd, sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 4. Query prior year actual YTD (same fiscal period)
        double priorYearYTD = queryActualRevenue(
                priorYearStart, priorYearEnd, sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 5. Query sparkline data (last 12 months actual revenue)
        LocalDate sparklineStart = normalizedAsOfDate.minusMonths(11).withDayOfMonth(1);
        double[] sparklineData = querySparklineRevenue(
                sparklineStart, normalizedAsOfDate,
                sectors, serviceLines, contractTypes, clientId, companyIds
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

        // 4. Query current TTM revenue
        double currentTTM = queryActualRevenue(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 5. Query prior TTM revenue
        double priorTTM = queryActualRevenue(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 6. Calculate growth percentage (handle division by zero)
        double growthPercent = (priorTTM > 0)
                ? ((currentTTM - priorTTM) / priorTTM) * 100.0
                : 0.0;

        // 7. Query sparkline data (last 12 calendar months from anchor date)
        double[] sparklineData = querySparklineRevenue(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
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

        // 4. Query current TTM revenue and costs
        double currentRevenue = queryActualRevenue(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double currentCost = queryActualCosts(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 5. Query prior TTM revenue and costs
        double priorRevenue = queryActualRevenue(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorCost = queryActualCosts(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

        // 6. Calculate margin percentages (handle division by zero)
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
                        "WHERE delivery_month_key >= :currentMonthKey " +
                        "AND project_status = 'ACTIVE' "
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

        // 2. Query revenue for current and prior TTM periods
        double currentTTMRevenue = queryActualRevenue(
                currentTTMStart, currentTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );
        double priorTTMRevenue = queryActualRevenue(
                priorTTMStart, priorTTMEnd,
                sectors, serviceLines, contractTypes, clientId, companyIds
        );

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
     *
     * IMPORTANT: Billable hours include a 7-day grace period to account for late time registration.
     * This prevents artificially low utilization when consultants register time after the fact.
     *
     * Example: For "last 4 weeks" ending Nov 27:
     * - Billable hours: Oct 24 - Nov 27 (35 days, includes 7-day grace period)
     * - Available hours: Oct 31 - Nov 27 (28 days, actual work period)
     *
     * Grace period rationale: If a consultant works Nov 10-17 but registers it on Nov 20,
     * it will still be counted in the "last 4 weeks" metric even if today is Nov 27.
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

        // Query 1: Get billable hours from work_full (accurate source for billable work)
        // Uses w.registered (registration date) with grace period to catch late registrations
        StringBuilder billableSql = new StringBuilder(
                "SELECT COALESCE(SUM(CASE WHEN w.rate > 0 AND w.workduration > 0 THEN w.workduration ELSE 0 END), 0.0) AS total_billable_hours " +
                "FROM work_full w " +
                "WHERE w.registered >= :billableFromDate " +
                "  AND w.registered <= :billableToDate " +
                "  AND w.type = 'CONSULTANT' "
        );

        // Add optional filters for billable hours query
        if (serviceLines != null && !serviceLines.isEmpty()) {
            // Join to user table to get practice/service line
            billableSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            billableSql.append("AND w.consultant_company_uuid IN (:companyIds) ");
        }

        Query billableQuery = em.createNativeQuery(billableSql.toString());
        billableQuery.setParameter("billableFromDate", billableFromDate);
        billableQuery.setParameter("billableToDate", billableToDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            billableQuery.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            billableQuery.setParameter("companyIds", companyIds);
        }

        double billableHours = ((Number) billableQuery.getSingleResult()).doubleValue();

        // Query 2: Get available hours from bi_data_per_day (daily availability data)
        // Uses b.document_date (actual work date) for the true 4-week period
        StringBuilder availableSql = new StringBuilder(
                "SELECT COALESCE(SUM(b.gross_available_hours - COALESCE(b.unavailable_hours, 0)), 0.0) AS total_available_hours " +
                "FROM bi_data_per_day b " +
                "WHERE b.document_date >= :availableFromDate " +
                "  AND b.document_date <= :availableToDate " +
                "  AND b.consultant_type = 'CONSULTANT' " +
                "  AND b.status_type = 'ACTIVE' "
        );

        // Add optional filters for available hours query
        if (serviceLines != null && !serviceLines.isEmpty()) {
            // Join to user table to get practice/service line
            availableSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = b.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            availableSql.append("AND b.companyuuid IN (:companyIds) ");
        }

        Query availableQuery = em.createNativeQuery(availableSql.toString());
        availableQuery.setParameter("availableFromDate", availableFromDate);
        availableQuery.setParameter("availableToDate", availableToDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            availableQuery.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            availableQuery.setParameter("companyIds", companyIds);
        }

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
                        "    FROM fact_project_financials f " +
                        "    WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) BETWEEN :fromKey AND :toKey "
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
     * Helper method: Query actual costs (direct delivery costs) from fact_project_financials for a date range.
     * Supports optional filters for sectors, service lines, contract types, client, and companies.
     * Uses direct_delivery_cost_dkk column which includes:
     * - Employee salary costs (temporal salary join)
     * - External consultant costs (HOURLY contractors)
     * - Project expenses (travel, materials, etc.)
     */
    private double queryActualCosts(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        // Build dynamic SQL with deduplication (same pattern as queryActualRevenue)
        // NOTE: V118 migration added companyuuid column, creating multiple rows per project-month
        // NOTE: Added COLLATE to fix collation mismatch between month_key column and DATE_FORMAT() function
        // Strategy: GROUP BY project_id + month_key, then MAX(cost) to get single cost value per project-month
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(max_cost), 0.0) AS total_cost " +
                        "FROM ( " +
                        "    SELECT " +
                        "        f.project_id, " +
                        "        f.month_key, " +
                        "        MAX(f.direct_delivery_cost_dkk) AS max_cost " +
                        "    FROM fact_project_financials f " +
                        "    WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters (same as revenue query)
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
                        "    FROM fact_project_financials f " +
                        "    WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) BETWEEN :fromKey AND :toKey " +
                        "      AND f.client_id IS NOT NULL " +
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
                        "FROM fact_project_financials f " +
                        "WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) BETWEEN :fromKey AND :toKey " +
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

        // Query monthly revenue and costs for last 12 months
        // NOTE: Added COLLATE to fix collation mismatch between month_key column and DATE_FORMAT() function
        StringBuilder sql = new StringBuilder(
                "SELECT f.month_key, " +
                        "       COALESCE(SUM(f.recognized_revenue_dkk), 0.0) AS monthly_revenue, " +
                        "       COALESCE(SUM(f.direct_delivery_cost_dkk), 0.0) AS monthly_cost " +
                        "FROM fact_project_financials f " +
                        "WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) BETWEEN :fromKey AND :toKey "
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
        // Calculate margin % for each month: (revenue - cost) / revenue * 100
        double[] sparklineData = new double[12];
        for (int i = 0; i < results.size() && i < 12; i++) {
            Tuple tuple = results.get(i);
            double monthlyRevenue = ((Number) tuple.get("monthly_revenue")).doubleValue();
            double monthlyCost = ((Number) tuple.get("monthly_cost")).doubleValue();

            // Calculate margin % (handle zero revenue)
            if (monthlyRevenue > 0) {
                sparklineData[i] = ((monthlyRevenue - monthlyCost) / monthlyRevenue) * 100.0;
            } else {
                sparklineData[i] = 0.0;
            }
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
                        "FROM fact_revenue_budget b " +
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
                        "FROM fact_project_financials f " +
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
                        "WHERE expected_revenue_month_key BETWEEN :fromMonthKey AND :toMonthKey " +
                        "  AND stage_category COLLATE utf8mb4_uca1400_ai_ci NOT IN ('WON', 'LOST') "
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
                        "WHERE delivery_month_key BETWEEN :fromMonthKey AND :toMonthKey " +
                        "  AND project_status COLLATE utf8mb4_uca1400_ai_ci = 'ACTIVE' "
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
                        "FROM fact_revenue_budget " +
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
            sql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
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

        // Query 1: Monthly revenue from fact_project_financials
        StringBuilder revenueSql = new StringBuilder(
                "SELECT f.month_key, SUM(f.recognized_revenue_dkk) AS monthly_revenue " +
                "FROM fact_project_financials f " +
                "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );

        if (sectors != null && !sectors.isEmpty()) {
            revenueSql.append("AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            revenueSql.append("AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            revenueSql.append("AND f.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            revenueSql.append("AND f.client_id = :clientId ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            revenueSql.append("AND f.companyuuid IN (:companyIds) ");
        }

        revenueSql.append("GROUP BY f.month_key ORDER BY f.month_key");

        Query revenueQuery = em.createNativeQuery(revenueSql.toString(), Tuple.class);
        revenueQuery.setParameter("fromKey", fromKey);
        revenueQuery.setParameter("toKey", toKey);

        if (sectors != null && !sectors.isEmpty()) {
            revenueQuery.setParameter("sectors", sectors);
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            revenueQuery.setParameter("serviceLines", serviceLines);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            revenueQuery.setParameter("contractTypes", contractTypes);
        }
        if (clientId != null && !clientId.trim().isEmpty()) {
            revenueQuery.setParameter("clientId", clientId);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            revenueQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> revenueResults = revenueQuery.getResultList();

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
            fteSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
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

        // Build maps for easy lookup
        java.util.Map<String, Double> revenueByMonth = new java.util.HashMap<>();
        for (Tuple row : revenueResults) {
            String monthKey = (String) row.get(0);
            double revenue = ((Number) row.get(1)).doubleValue();
            revenueByMonth.put(monthKey, revenue);
        }

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
        // so we filter by service line (consultant's primaryskilltype) and company
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
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
            expectedSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
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
                "FROM fact_project_financials f " +
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
                        "    FROM fact_project_financials f " +
                        "    WHERE f.month_key BETWEEN :fromKey AND :toKey " +
                        "      AND f.client_id IS NOT NULL " +
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

        // Query 1: Get billable hours from work_full (accurate source for billable work)
        StringBuilder billableSql = new StringBuilder(
                "SELECT COALESCE(SUM(CASE WHEN w.rate > 0 AND w.workduration > 0 THEN w.workduration ELSE 0 END), 0.0) AS total_billable_hours " +
                "FROM work_full w " +
                "WHERE w.registered >= :fromDate " +
                "  AND w.registered <= :toDate " +
                "  AND w.type = 'CONSULTANT' "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            billableSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = w.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            billableSql.append("AND w.consultant_company_uuid IN (:companyIds) ");
        }

        Query billableQuery = em.createNativeQuery(billableSql.toString());
        billableQuery.setParameter("fromDate", fromDate);
        billableQuery.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            billableQuery.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            billableQuery.setParameter("companyIds", companyIds);
        }

        double billableHours = ((Number) billableQuery.getSingleResult()).doubleValue();

        // Query 2: Get available hours from bi_data_per_day (daily availability data)
        StringBuilder availableSql = new StringBuilder(
                "SELECT COALESCE(SUM(b.gross_available_hours - COALESCE(b.unavailable_hours, 0)), 0.0) AS total_available_hours " +
                "FROM bi_data_per_day b " +
                "WHERE b.document_date >= :fromDate " +
                "  AND b.document_date <= :toDate " +
                "  AND b.consultant_type = 'CONSULTANT' " +
                "  AND b.status_type = 'ACTIVE' "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            availableSql.append("AND EXISTS (SELECT 1 FROM user u WHERE u.uuid = b.useruuid AND u.primaryskilltype IN (:serviceLines)) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            availableSql.append("AND b.companyuuid IN (:companyIds) ");
        }

        Query availableQuery = em.createNativeQuery(availableSql.toString());
        availableQuery.setParameter("fromDate", fromDate);
        availableQuery.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            availableQuery.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            availableQuery.setParameter("companyIds", companyIds);
        }

        double capacityHours = ((Number) availableQuery.getSingleResult()).doubleValue();

        log.debugf("Prior utilization data: fromDate=%s, toDate=%s, billable=%.0f, capacity=%.0f",
                fromDate, toDate, billableHours, capacityHours);

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

        // Query both fiscal years with category breakdown
        String sql = buildOpexByFYQuery(costCenters, companyIds);
        Query query = em.createNativeQuery(sql);
        query.setParameter("currentFromKey", currentFromMonthKey);
        query.setParameter("currentToKey", currentToMonthKey);
        query.setParameter("priorFromKey", priorFromMonthKey);
        query.setParameter("priorToKey", priorToMonthKey);

        if (costCenters != null && !costCenters.isEmpty()) {
            query.setParameter("costCenters", costCenters);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // Parse results into category changes
        double priorFYOpex = 0.0;
        double currentFYOpex = 0.0;
        double peopleNonBillableChange = 0.0;
        double toolsSoftwareChange = 0.0;
        double officeFacilitiesChange = 0.0;
        double salesMarketingChange = 0.0;
        double otherOpexChange = 0.0;

        java.util.Map<String, Double> priorByCategory = new java.util.HashMap<>();
        java.util.Map<String, Double> currentByCategory = new java.util.HashMap<>();

        for (Object[] row : results) {
            String fiscalYearLabel = (String) row[0];
            String categoryId = (String) row[1];
            double amount = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;

            if ("PRIOR".equals(fiscalYearLabel)) {
                priorFYOpex += amount;
                priorByCategory.put(categoryId, amount);
            } else if ("CURRENT".equals(fiscalYearLabel)) {
                currentFYOpex += amount;
                currentByCategory.put(categoryId, amount);
            }
        }

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

        StringBuilder sql = new StringBuilder(
                "SELECT month_key, expense_category_id, SUM(opex_amount_dkk) AS amount " +
                "FROM fact_opex WHERE 1=1 "
        );

        sql.append("  AND CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :fromMonthKey ")
                .append("  AND CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :toMonthKey ");

        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("  AND cost_center_id IN (:costCenters) ");
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            sql.append("  AND expense_category_id IN (:expenseCategories) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND company_uuid IN (:companyIds) ");
        }

        sql.append("GROUP BY month_key, expense_category_id ORDER BY month_key ASC");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (costCenters != null && !costCenters.isEmpty()) {
            query.setParameter("costCenters", costCenters);
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            query.setParameter("expenseCategories", expenseCategories);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // Group by month and calculate percentages
        java.util.Map<String, java.util.Map<String, Double>> monthlyData = new java.util.LinkedHashMap<>();
        for (Object[] row : results) {
            String monthKey = (String) row[0];
            String categoryId = (String) row[1];
            double amount = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;

            monthlyData.putIfAbsent(monthKey, new java.util.HashMap<>());
            monthlyData.get(monthKey).put(categoryId, amount);
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

        StringBuilder sql = new StringBuilder(
                "SELECT month_key, cost_center_id, SUM(opex_amount_dkk) AS amount " +
                "FROM fact_opex WHERE 1=1 "
        );

        sql.append("  AND CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :fromMonthKey ")
                .append("  AND CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :toMonthKey ");

        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("  AND cost_center_id IN (:costCenters) ");
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            sql.append("  AND expense_category_id IN (:expenseCategories) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND company_uuid IN (:companyIds) ");
        }

        sql.append("GROUP BY month_key, cost_center_id ORDER BY month_key ASC");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (costCenters != null && !costCenters.isEmpty()) {
            query.setParameter("costCenters", costCenters);
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            query.setParameter("expenseCategories", expenseCategories);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // Group by month
        java.util.Map<String, java.util.Map<String, Double>> monthlyData = new java.util.LinkedHashMap<>();
        for (Object[] row : results) {
            String monthKey = (String) row[0];
            String costCenterId = (String) row[1];
            double amount = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;

            monthlyData.putIfAbsent(monthKey, new java.util.HashMap<>());
            monthlyData.get(monthKey).put(costCenterId, amount);
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

        // Build SQL joining payroll data, FTE data, and revenue
        StringBuilder sql = new StringBuilder(
                "SELECT e.month_key, " +
                "  SUM(e.fte_billable) AS billable_fte, " +
                "  SUM(e.fte_non_billable) AS non_billable_fte, " +
                "  COALESCE((SELECT SUM(o.opex_amount_dkk) FROM fact_opex o " +
                "    WHERE o.month_key = e.month_key AND o.is_payroll_flag = 1), 0) AS total_payroll, " +
                "  COALESCE((SELECT SUM(f.recognized_revenue_dkk) FROM fact_project_financials f " +
                "    WHERE f.month_key = e.month_key), 0) AS total_revenue " +
                "FROM fact_employee_monthly e " +
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

        List<MonthlyPayrollHeadcountDTO> resultList = new java.util.ArrayList<>();
        for (Object[] row : results) {
            String monthKey = (String) row[0];
            double billableFTE = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
            double nonBillableFTE = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            double totalPayroll = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
            double totalRevenue = row[4] != null ? ((Number) row[4]).doubleValue() : 0.0;

            double payrollAsPercentOfRevenue = totalRevenue > 0 ? (totalPayroll / totalRevenue) * 100.0 : 0.0;
            double totalFTE = billableFTE + nonBillableFTE;

            String monthLabel = formatMonthLabel(Integer.parseInt(monthKey.substring(0, 4)), Integer.parseInt(monthKey.substring(4)));

            resultList.add(new MonthlyPayrollHeadcountDTO(
                    monthLabel, monthKey,
                    billableFTE, nonBillableFTE,
                    totalPayroll, totalRevenue, payrollAsPercentOfRevenue, totalFTE
            ));
        }

        log.debugf("Returning %d payroll/headcount data points", resultList.size());
        return resultList;
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

        // For each month in range, calculate TTM metrics
        YearMonth start = YearMonth.from(normalizedFromDate);
        YearMonth end = YearMonth.from(normalizedToDate);
        YearMonth current = start;

        while (!current.isAfter(end)) {
            LocalDate monthEnd = current.atEndOfMonth();
            LocalDate ttmStart = monthEnd.minusMonths(11).withDayOfMonth(1);

            String ttmStartKey = String.format("%04d%02d", ttmStart.getYear(), ttmStart.getMonthValue());
            String ttmEndKey = String.format("%04d%02d", monthEnd.getYear(), monthEnd.getMonthValue());

            // Query TTM non-payroll OPEX
            StringBuilder opexSql = new StringBuilder(
                    "SELECT SUM(opex_amount_dkk) FROM fact_opex " +
                    "WHERE is_payroll_flag = 0 " +
                    "  AND month_key >= :fromKey AND month_key <= :toKey "
            );
            if (companyIds != null && !companyIds.isEmpty()) {
                opexSql.append("  AND company_uuid IN (:companyIds) ");
            }

            Query opexQuery = em.createNativeQuery(opexSql.toString());
            opexQuery.setParameter("fromKey", ttmStartKey);
            opexQuery.setParameter("toKey", ttmEndKey);
            if (companyIds != null && !companyIds.isEmpty()) {
                opexQuery.setParameter("companyIds", companyIds);
            }
            double ttmNonPayrollOpex = ((Number) opexQuery.getSingleResult()).doubleValue();

            // Query TTM average FTE
            StringBuilder fteSql = new StringBuilder(
                    "SELECT AVG(fte_billable + fte_non_billable) AS avg_total_fte, " +
                    "  AVG(fte_billable) AS avg_billable_fte " +
                    "FROM fact_employee_monthly " +
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

        StringBuilder sql = new StringBuilder(
                "SELECT a.month_key, c.name AS company_name, a.cost_center_id, a.expense_category_id, " +
                "  '' AS account_code, '' AS account_name, " +
                "  SUM(a.opex_amount_dkk) AS opex_amount, " +
                "  SUM(COALESCE(b.budget_opex_dkk, 0)) AS budget_amount, " +
                "  COUNT(*) AS invoice_count, " +
                "  MAX(a.is_payroll_flag) AS is_payroll " +
                "FROM fact_opex a " +
                "LEFT JOIN fact_opex_budget b ON a.company_id = b.company_id " +
                "  AND a.month_key = b.month_key " +
                "  AND a.cost_center_id = b.cost_center_id " +
                "  AND a.expense_category_id = b.expense_category_id " +
                "JOIN companies c ON a.company_id = c.uuid " +
                "WHERE 1=1 "
        );

        sql.append("  AND CAST(a.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :fromMonthKey ")
                .append("  AND CAST(a.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :toMonthKey ");

        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("  AND a.cost_center_id IN (:costCenters) ");
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            sql.append("  AND a.expense_category_id IN (:expenseCategories) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND a.company_uuid IN (:companyIds) ");
        }

        sql.append("GROUP BY a.month_key, c.name, a.cost_center_id, a.expense_category_id ")
                .append("ORDER BY a.month_key DESC, c.name, a.cost_center_id, a.expense_category_id");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromMonthKey", fromMonthKey);
        query.setParameter("toMonthKey", toMonthKey);

        if (costCenters != null && !costCenters.isEmpty()) {
            query.setParameter("costCenters", costCenters);
        }
        if (expenseCategories != null && !expenseCategories.isEmpty()) {
            query.setParameter("expenseCategories", expenseCategories);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        List<OpexDetailRowDTO> resultList = new java.util.ArrayList<>();
        for (Object[] row : results) {
            String monthKey = (String) row[0];
            String companyName = (String) row[1];
            String costCenterId = (String) row[2];
            String expenseCategoryId = (String) row[3];
            String accountCode = (String) row[4];
            String accountName = (String) row[5];
            double opexAmount = row[6] != null ? ((Number) row[6]).doubleValue() : 0.0;
            double budgetAmount = row[7] != null ? ((Number) row[7]).doubleValue() : 0.0;
            int invoiceCount = row[8] != null ? ((Number) row[8]).intValue() : 0;
            boolean isPayroll = row[9] != null && ((Number) row[9]).intValue() == 1;

            double varianceAmount = opexAmount - budgetAmount;
            Double variancePercent = budgetAmount > 0 ? (varianceAmount / budgetAmount) * 100.0 : null;

            int year = Integer.parseInt(monthKey.substring(0, 4));
            int month = Integer.parseInt(monthKey.substring(4));
            int fiscalYear = month >= 7 ? year : year - 1;
            int fiscalMonthNumber = month >= 7 ? month - 6 : month + 6;

            String monthLabel = formatMonthLabel(year, month);

            resultList.add(new OpexDetailRowDTO(
                    companyName, costCenterId, expenseCategoryId, accountCode, accountName,
                    monthKey, monthLabel,
                    opexAmount, budgetAmount, varianceAmount, variancePercent,
                    invoiceCount, isPayroll,
                    fiscalYear, fiscalMonthNumber
            ));
        }

        log.debugf("Returning %d expense detail rows", resultList.size());
        return resultList;
    }

    // ========== PRIVATE HELPER METHODS ==========

    private String buildOpexByFYQuery(Set<String> costCenters, Set<String> companyIds) {
        StringBuilder sql = new StringBuilder(
                "SELECT fy_label, expense_category_id, SUM(opex_amount_dkk) AS amount " +
                "FROM ( " +
                "  SELECT 'CURRENT' AS fy_label, expense_category_id, opex_amount_dkk " +
                "  FROM fact_opex " +
                "  WHERE CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :currentFromKey " +
                "    AND CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :currentToKey "
        );

        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("    AND cost_center_id IN (:costCenters) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND company_uuid IN (:companyIds) ");
        }

        sql.append("  UNION ALL " +
                "  SELECT 'PRIOR' AS fy_label, expense_category_id, opex_amount_dkk " +
                "  FROM fact_opex " +
                "  WHERE CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) >= :priorFromKey " +
                "    AND CAST(month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) <= :priorToKey "
        );

        if (costCenters != null && !costCenters.isEmpty()) {
            sql.append("    AND cost_center_id IN (:costCenters) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND company_uuid IN (:companyIds) ");
        }

        sql.append(") combined " +
                "GROUP BY fy_label, expense_category_id");

        return sql.toString();
    }

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
                "FROM fact_employee_monthly " +
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
}
