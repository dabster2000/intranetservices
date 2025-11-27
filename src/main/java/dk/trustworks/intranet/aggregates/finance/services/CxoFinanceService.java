package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.BacklogCoverageDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BillableUtilizationLast4WeeksDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ClientRetentionDTO;
import dk.trustworks.intranet.aggregates.finance.dto.GrossMarginTTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyPipelineBacklogDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RepeatBusinessShareDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenuePerBillableFTETTMDTO;
import dk.trustworks.intranet.aggregates.finance.dto.RevenueYTDDataDTO;
import dk.trustworks.intranet.aggregates.finance.dto.Top5ClientsShareDTO;
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
        // Current period: Last 4 weeks (28 days) ending on asOfDate
        LocalDate currentPeriodEnd = asOfDate;
        LocalDate currentPeriodStart = asOfDate.minusDays(27);  // 28 days total (inclusive)

        // Prior period: 4 weeks before current period (days -55 to -28)
        LocalDate priorPeriodEnd = currentPeriodStart.minusDays(1);
        LocalDate priorPeriodStart = priorPeriodEnd.minusDays(27);  // 28 days total

        log.debugf("Current period: %s to %s (28 days)", currentPeriodStart, currentPeriodEnd);
        log.debugf("Prior period: %s to %s (28 days)", priorPeriodStart, priorPeriodEnd);

        // 2. Query current period utilization
        double[] currentPeriodData = queryUtilizationFor4Weeks(
                currentPeriodStart, currentPeriodEnd, serviceLines, companyIds
        );
        double currentBillableHours = currentPeriodData[0];
        double currentAvailableHours = currentPeriodData[1];

        // 3. Query prior period utilization
        double[] priorPeriodData = queryUtilizationFor4Weeks(
                priorPeriodStart, priorPeriodEnd, serviceLines, companyIds
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
     * Helper method: Query billable and available hours from fact_user_utilization
     * for a specific 4-week period (28 days).
     *
     * @param fromDate Start date (inclusive)
     * @param toDate End date (inclusive)
     * @param serviceLines Optional service line filter
     * @param companyIds Optional company filter
     * @return Array [billableHours, availableHours]
     */
    private double[] queryUtilizationFor4Weeks(
            LocalDate fromDate, LocalDate toDate,
            Set<String> serviceLines, Set<String> companyIds) {

        // Build dynamic SQL query
        StringBuilder sql = new StringBuilder(
                "SELECT " +
                        "  COALESCE(SUM(u.billable_hours), 0.0) AS total_billable_hours, " +
                        "  COALESCE(SUM(u.available_hours), 0.0) AS total_available_hours " +
                        "FROM fact_user_utilization u " +
                        "WHERE u.week_start_date >= :fromDate " +
                        "  AND u.week_start_date <= :toDate "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND u.service_line_id IN (:serviceLines) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND u.company_id IN (:companyIds) ");
        }

        // Execute query
        Query query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        if (serviceLines != null && !serviceLines.isEmpty()) {
            query.setParameter("serviceLines", serviceLines);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Tuple result = (Tuple) query.getSingleResult();
        double billableHours = ((Number) result.get("total_billable_hours")).doubleValue();
        double availableHours = ((Number) result.get("total_available_hours")).doubleValue();

        log.debugf("Utilization query (%s to %s): billable=%.0f, available=%.0f",
                fromDate, toDate, billableHours, availableHours);

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

        // Build dynamic SQL (same pattern as queryActualRevenue)
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(f.direct_delivery_cost_dkk), 0.0) AS total_cost " +
                        "FROM fact_project_financials f " +
                        "WHERE f.month_key BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters (same as revenue query)
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
        StringBuilder sql = new StringBuilder(
                "WITH client_project_revenue AS ( " +
                        "    SELECT " +
                        "        f.client_id, " +
                        "        COUNT(DISTINCT f.project_id) AS project_count, " +
                        "        SUM(f.recognized_revenue_dkk) AS client_revenue " +
                        "    FROM fact_project_financials f " +
                        "    WHERE f.month_key BETWEEN :fromKey AND :toKey " +
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
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT f.client_id " +
                        "FROM fact_project_financials f " +
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

        // Query monthly revenue and costs for last 12 months
        StringBuilder sql = new StringBuilder(
                "SELECT f.month_key, " +
                        "       COALESCE(SUM(f.recognized_revenue_dkk), 0.0) AS monthly_revenue, " +
                        "       COALESCE(SUM(f.direct_delivery_cost_dkk), 0.0) AS monthly_cost " +
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

        String fromKey = String.format("%04d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toKey = String.format("%04d%02d", toDate.getYear(), toDate.getMonthValue());

        StringBuilder sql = new StringBuilder(
                "SELECT AVG(u.billable_fte_count) AS avg_billable_fte " +
                "FROM fact_user_utilization u " +
                "WHERE u.month_key BETWEEN :fromKey AND :toKey "
        );

        // Add optional filters
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("AND u.service_line_id IN (:serviceLines) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND u.company_id IN (:companyIds) ");
        }

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);

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
            revenueSql.append("AND f.company_id IN (:companyIds) ");
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

        // Query 2: Monthly billable FTE from fact_user_utilization
        StringBuilder fteSql = new StringBuilder(
                "SELECT u.month_key, u.billable_fte_count " +
                "FROM fact_user_utilization u " +
                "WHERE u.month_key BETWEEN :fromKey AND :toKey "
        );

        if (serviceLines != null && !serviceLines.isEmpty()) {
            fteSql.append("AND u.service_line_id IN (:serviceLines) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            fteSql.append("AND u.company_id IN (:companyIds) ");
        }

        fteSql.append("ORDER BY u.month_key");

        Query fteQuery = em.createNativeQuery(fteSql.toString(), Tuple.class);
        fteQuery.setParameter("fromKey", fromKey);
        fteQuery.setParameter("toKey", toKey);

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
}
