package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.MonthlyPipelineBacklogDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueYTDDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TTMRevenueGrowthDTO;
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
import java.util.List;
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
            sql.append("  AND f.month_key >= :fromMonthKey ")
                    .append("  AND f.month_key <= :toMonthKey ");

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
            sql.append("  AND f.month_key >= :fromMonthKey ")
                    .append("  AND f.month_key <= :toMonthKey ");

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
     * Helper method: Query actual revenue from fact_project_financials for a date range.
     * Supports optional filters for sectors, service lines, contract types, client, and companies.
     */
    private double queryActualRevenue(
            LocalDate fromDate, LocalDate toDate,
            Set<String> sectors, Set<String> serviceLines,
            Set<String> contractTypes, String clientId, Set<String> companyIds) {

        // Build dynamic SQL (following Chart A pattern)
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(f.recognized_revenue_dkk), 0.0) AS total_revenue " +
                        "FROM fact_project_financials f " +
                        "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters (following Chart A pattern exactly)
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
                        "WHERE year >= :fromYear AND year <= :toYear " +
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

        // Extract year values from month keys (YYYYMM format)
        int fromYear = Integer.parseInt(fromMonthKey.substring(0, 4));
        int toYear = Integer.parseInt(toMonthKey.substring(0, 4));

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromYear", fromYear);
        query.setParameter("toYear", toYear);

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
                        "WHERE year >= :fromYear AND year <= :toYear " +
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

        // Extract year values from month keys (YYYYMM format)
        int fromYear = Integer.parseInt(fromMonthKey.substring(0, 4));
        int toYear = Integer.parseInt(toMonthKey.substring(0, 4));

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromYear", fromYear);
        query.setParameter("toYear", toYear);

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
                        "WHERE year >= :fromYear AND year <= :toYear "
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

        // Extract year values from month keys (YYYYMM format)
        int fromYear = Integer.parseInt(fromMonthKey.substring(0, 4));
        int toYear = Integer.parseInt(toMonthKey.substring(0, 4));

        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromYear", fromYear);
        query.setParameter("toYear", toYear);

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
}
