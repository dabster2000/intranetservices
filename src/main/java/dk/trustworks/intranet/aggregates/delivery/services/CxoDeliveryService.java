package dk.trustworks.intranet.aggregates.delivery.services;

import dk.trustworks.intranet.aggregates.delivery.dto.AvgProjectMarginDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.BenchCountDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.ForecastUtilizationDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.OverloadCountDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.RealizationRateDTO;
import dk.trustworks.intranet.aggregates.delivery.dto.UtilizationTTMDTO;
import dk.trustworks.intranet.utils.TwConstants;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;

/**
 * Service for CxO dashboard delivery metrics.
 * Queries the fact_user_utilization view for company-wide utilization KPIs.
 */
@JBossLog
@ApplicationScoped
public class CxoDeliveryService {

    @Inject
    EntityManager em;

    /**
     * Calculates Company Billable Utilization (TTM) KPI.
     * Returns utilization percentage over a trailing 12-month window.
     *
     * Business Logic:
     * - Normalizes dates to a full 12-month window ending at toDate
     * - Calculates prior year window for YoY comparison
     * - Queries fact_user_utilization to sum billable and net available hours
     * - Builds 12-month sparkline showing monthly utilization trends
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param practices Multi-select practice filter (e.g., "PM", "DEV", "BA")
     * @param companyIds Multi-select company filter (UUIDs)
     * @return UtilizationTTMDTO with current/prior percentages, YoY change, and sparkline
     */
    @CacheResult(cacheName = "delivery-utilization-ttm")
    public UtilizationTTMDTO getUtilizationTTM(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window (same 12-month span, 1 year earlier)
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("Utilization TTM: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current TTM utilization percentage
        double currentPercent = queryUtilizationPercent(
                fromDateNormalized, toDateNormalized,
                practices, companyIds);

        // 4. Query prior TTM utilization percentage
        double priorPercent = queryUtilizationPercent(
                priorFromDate, priorToDate,
                practices, companyIds);

        // 5. Calculate YoY change in percentage points (NOT percentage)
        double yoyChangePoints = currentPercent - priorPercent;

        log.debugf("Utilization TTM: current=%.2f%%, prior=%.2f%%, change=%+.2f points",
                currentPercent, priorPercent, yoyChangePoints);

        // 6. Build sparkline (12 monthly utilization percentages ending at toDateNormalized)
        double[] sparklineData = buildSparklineData(
                toDateNormalized, practices, companyIds);

        // 7. Return DTO with values rounded to 1 decimal place
        return new UtilizationTTMDTO(
                Math.round(currentPercent * 10.0) / 10.0,
                Math.round(priorPercent * 10.0) / 10.0,
                Math.round(yoyChangePoints * 10.0) / 10.0,
                sparklineData
        );
    }

    /**
     * Query utilization percentage for a date range.
     * Calculates: (SUM(billable_hours) / SUM(net_available_hours)) * 100
     *
     * IMPORTANT NOTES:
     * - fact_user_utilization has unique rows per user × month (NO deduplication needed)
     * - Filters net_available_hours > 0 to prevent division by zero
     * - Uses collation fix for month_key comparisons
     *
     * @param fromDate Start date (first day of month)
     * @param toDate End date (last day of month)
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Utilization percentage (0-100), rounded to 2 decimals
     */
    private double queryUtilizationPercent(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryUtilizationPercent: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  SUM(f.billable_hours) AS total_billable, ");
        sql.append("  SUM(f.net_available_hours) AS total_available ");
        sql.append("FROM fact_user_utilization f ");
        sql.append("WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("  BETWEEN :fromKey AND :toKey ");
        sql.append("  AND f.net_available_hours > 0 ");  // Prevent division by zero

        // Optional filters (null-safe)
        if (practices != null && !practices.isEmpty()) {
            sql.append("  AND f.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND f.companyuuid IN (:companyIds) ");
        }

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract results
        List<Object[]> results = query.getResultList();

        // Handle empty result set (no data in range)
        if (results.isEmpty()) {
            log.tracef("No utilization data found for range [%s to %s]", fromMonthKey, toMonthKey);
            return 0.0;
        }

        Object[] result = results.get(0);
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal totalBillable = result[0] != null
            ? BigDecimal.valueOf(((Number) result[0]).doubleValue())
            : BigDecimal.ZERO;
        BigDecimal totalAvailable = result[1] != null
            ? BigDecimal.valueOf(((Number) result[1]).doubleValue())
            : BigDecimal.ZERO;

        // Handle null aggregation results
        if (totalBillable == null || totalAvailable == null) {
            log.tracef("Null aggregation results for range [%s to %s]", fromMonthKey, toMonthKey);
            return 0.0;
        }

        // Calculate utilization percentage
        double billableHours = totalBillable.doubleValue();
        double availableHours = totalAvailable.doubleValue();

        if (availableHours <= 0) {
            log.tracef("Zero available hours for range [%s to %s]", fromMonthKey, toMonthKey);
            return 0.0;
        }

        double utilizationPercent = (billableHours / availableHours) * 100.0;

        log.tracef("Range [%s to %s]: billable=%.2f, available=%.2f, util=%.2f%%",
                fromMonthKey, toMonthKey, billableHours, availableHours, utilizationPercent);

        return utilizationPercent;
    }

    /**
     * Build sparkline data: 12 monthly utilization percentages.
     * Array is ordered oldest month first, ending at toDate.
     *
     * Example: If toDate is 2024-12-31, array contains:
     * [Jan-2024, Feb-2024, Mar-2024, ..., Dec-2024]
     *
     * @param toDate End date of sparkline (last day of last month)
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Array of 12 monthly utilization percentages, rounded to 1 decimal
     */
    private double[] buildSparklineData(
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        double[] sparklineData = new double[12];

        // Iterate backwards through 12 months, oldest first in array
        for (int i = 0; i < 12; i++) {
            // Calculate month date (i=0 is oldest, i=11 is most recent)
            LocalDate monthEnd = toDate.minusMonths(11 - i).withDayOfMonth(1);
            LocalDate monthStart = monthEnd.withDayOfMonth(1);
            monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());

            // Query single month utilization
            double monthUtilization = querySingleMonthUtilization(
                    monthStart, monthEnd, practices, companyIds);

            // Round to 1 decimal place
            sparklineData[i] = Math.round(monthUtilization * 10.0) / 10.0;

            log.tracef("Sparkline[%d]: %s = %.2f%%", (Object) i, monthEnd.toString().substring(0, 7), sparklineData[i]);
        }

        return sparklineData;
    }

    /**
     * Query utilization percentage for a single month.
     * Same logic as queryUtilizationPercent() but optimized for single-month queries.
     *
     * @param monthStart First day of month
     * @param monthEnd Last day of month
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Utilization percentage (0-100)
     */
    private double querySingleMonthUtilization(
            LocalDate monthStart,
            LocalDate monthEnd,
            Set<String> practices,
            Set<String> companyIds) {

        // For single-month queries, fromKey == toKey
        String monthKey = String.format("%d%02d", monthStart.getYear(), monthStart.getMonthValue());

        // Build SQL query (same structure as queryUtilizationPercent)
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  SUM(f.billable_hours) AS total_billable, ");
        sql.append("  SUM(f.net_available_hours) AS total_available ");
        sql.append("FROM fact_user_utilization f ");
        sql.append("WHERE f.month_key = :monthKey ");
        sql.append("  AND f.net_available_hours > 0 ");

        // Optional filters
        if (practices != null && !practices.isEmpty()) {
            sql.append("  AND f.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND f.companyuuid IN (:companyIds) ");
        }

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("monthKey", monthKey);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract results
        Object[] result = (Object[]) query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal totalBillable = result[0] != null
            ? BigDecimal.valueOf(((Number) result[0]).doubleValue())
            : BigDecimal.ZERO;
        BigDecimal totalAvailable = result[1] != null
            ? BigDecimal.valueOf(((Number) result[1]).doubleValue())
            : BigDecimal.ZERO;

        // Handle null results
        if (totalBillable == null || totalAvailable == null) {
            return 0.0;
        }

        // Calculate utilization percentage
        double billableHours = totalBillable.doubleValue();
        double availableHours = totalAvailable.doubleValue();

        if (availableHours <= 0) {
            return 0.0;
        }

        return (billableHours / availableHours) * 100.0;
    }

    /**
     * Calculates Forecast Utilization (Next 8 Weeks) KPI.
     * Returns forecasted utilization percentage for the next 8-week period.
     *
     * Business Logic:
     * - Normalizes dates to next 8 weeks from current date (Monday to Sunday)
     * - Calculates prior 8-week period (8-16 weeks ago) for comparison
     * - Queries fact_staffing_forecast_week for forecast data
     * - Builds 8-week sparkline showing weekly forecast utilization trends
     *
     * @param fromDate Start date (optional, auto-calculated to next Monday)
     * @param toDate End date (optional, auto-calculated to 8 weeks ahead)
     * @param practices Multi-select practice filter
     * @param companyIds Multi-select company filter
     * @return ForecastUtilizationDTO with current/prior percentages, change, and sparkline
     */
    @CacheResult(cacheName = "delivery-forecast-utilization")
    public ForecastUtilizationDTO getForecastUtilization(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // 1. Normalize dates to 8-week window starting next Monday
        LocalDate today = LocalDate.now();
        LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        LocalDate fromDateNormalized = nextMonday;
        LocalDate toDateNormalized = nextMonday.plusWeeks(8).minusDays(1); // Sunday of week 8

        // 2. Calculate prior 8-week period (8-16 weeks ago)
        LocalDate priorFromDate = fromDateNormalized.minusWeeks(16);
        LocalDate priorToDate = priorFromDate.plusWeeks(8).minusDays(1);

        log.debugf("Forecast Utilization: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current forecast utilization percentage
        double currentPercent = queryForecastUtilizationPercent(
                fromDateNormalized, toDateNormalized,
                practices, companyIds);

        // 4. Query prior forecast utilization percentage
        double priorPercent = queryForecastUtilizationPercent(
                priorFromDate, priorToDate,
                practices, companyIds);

        // 5. Calculate change in percentage points
        double changePoints = currentPercent - priorPercent;

        log.debugf("Forecast Utilization: current=%.2f%%, prior=%.2f%%, change=%+.2f points",
                currentPercent, priorPercent, changePoints);

        // 6. Build sparkline (8 weekly forecast utilization percentages)
        double[] sparklineData = buildForecastSparklineData(
                fromDateNormalized, practices, companyIds);

        // 7. Return DTO with values rounded to 1 decimal place
        return new ForecastUtilizationDTO(
                Math.round(currentPercent * 10.0) / 10.0,
                Math.round(priorPercent * 10.0) / 10.0,
                Math.round(changePoints * 10.0) / 10.0,
                sparklineData
        );
    }

    /**
     * Query forecast utilization percentage for a date range from fact_staffing_forecast_week.
     * Calculates: (SUM(forecast_billable_hours) / SUM(capacity_hours)) * 100
     *
     * @param fromDate Start date (Monday)
     * @param toDate End date (Sunday)
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Forecast utilization percentage (0-100)
     */
    private double queryForecastUtilizationPercent(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        log.tracef("queryForecastUtilizationPercent: date range [%s to %s]", fromDate, toDate);

        // Build SQL query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  SUM(f.forecast_billable_hours) AS total_forecast, ");
        sql.append("  SUM(f.capacity_hours) AS total_capacity ");
        sql.append("FROM fact_staffing_forecast_week f ");
        sql.append("WHERE f.week_start_date >= :fromDate ");
        sql.append("  AND f.week_start_date <= :toDate ");
        sql.append("  AND f.capacity_hours > 0 ");  // Prevent division by zero

        // Optional filters (null-safe)
        if (practices != null && !practices.isEmpty()) {
            sql.append("  AND f.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND f.company_id IN (:companyIds) ");
        }

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract results
        Object[] result = (Object[]) query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal totalForecast = result[0] != null
            ? BigDecimal.valueOf(((Number) result[0]).doubleValue())
            : BigDecimal.ZERO;
        BigDecimal totalCapacity = result[1] != null
            ? BigDecimal.valueOf(((Number) result[1]).doubleValue())
            : BigDecimal.ZERO;

        // Handle null results (no forecast data in range)
        if (totalForecast == null || totalCapacity == null) {
            log.tracef("No forecast data found for range [%s to %s]", fromDate, toDate);
            return 0.0;
        }

        // Calculate forecast utilization percentage
        double forecastHours = totalForecast.doubleValue();
        double capacityHours = totalCapacity.doubleValue();

        if (capacityHours <= 0) {
            log.tracef("Zero capacity hours for range [%s to %s]", fromDate, toDate);
            return 0.0;
        }

        double forecastPercent = (forecastHours / capacityHours) * 100.0;

        log.tracef("Range [%s to %s]: forecast=%.2f, capacity=%.2f, util=%.2f%%",
                fromDate, toDate, forecastHours, capacityHours, forecastPercent);

        return forecastPercent;
    }

    /**
     * Build sparkline data: 8 weekly forecast utilization percentages.
     * Array is ordered with week 1 (next week) first, ending at week 8.
     *
     * @param startMonday Start date (Monday of first week)
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Array of 8 weekly forecast utilization percentages, rounded to 1 decimal
     */
    private double[] buildForecastSparklineData(
            LocalDate startMonday,
            Set<String> practices,
            Set<String> companyIds) {

        double[] sparklineData = new double[8];

        // Iterate through 8 weeks
        for (int i = 0; i < 8; i++) {
            // Calculate week dates (i=0 is next week, i=7 is week 8)
            LocalDate weekStart = startMonday.plusWeeks(i);
            LocalDate weekEnd = weekStart.plusDays(6); // Sunday

            // Query single week forecast utilization
            double weekUtilization = querySingleWeekForecastUtilization(
                    weekStart, weekEnd, practices, companyIds);

            // Round to 1 decimal place
            sparklineData[i] = Math.round(weekUtilization * 10.0) / 10.0;

            log.tracef("Forecast Sparkline[%d]: %s = %.2f%%",
                    (Object) i, weekStart.toString(), sparklineData[i]);
        }

        return sparklineData;
    }

    /**
     * Query forecast utilization percentage for a single week.
     *
     * @param weekStart Monday of the week
     * @param weekEnd Sunday of the week
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Forecast utilization percentage (0-100)
     */
    private double querySingleWeekForecastUtilization(
            LocalDate weekStart,
            LocalDate weekEnd,
            Set<String> practices,
            Set<String> companyIds) {

        // Build SQL query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  SUM(f.forecast_billable_hours) AS total_forecast, ");
        sql.append("  SUM(f.capacity_hours) AS total_capacity ");
        sql.append("FROM fact_staffing_forecast_week f ");
        sql.append("WHERE f.week_start_date = :weekStart ");
        sql.append("  AND f.capacity_hours > 0 ");

        // Optional filters
        if (practices != null && !practices.isEmpty()) {
            sql.append("  AND f.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND f.company_id IN (:companyIds) ");
        }

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("weekStart", weekStart);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract results
        Object[] result = (Object[]) query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal totalForecast = result[0] != null
            ? BigDecimal.valueOf(((Number) result[0]).doubleValue())
            : BigDecimal.ZERO;
        BigDecimal totalCapacity = result[1] != null
            ? BigDecimal.valueOf(((Number) result[1]).doubleValue())
            : BigDecimal.ZERO;

        // Handle null results
        if (totalForecast == null || totalCapacity == null) {
            return 0.0;
        }

        // Calculate forecast utilization percentage
        double forecastHours = totalForecast.doubleValue();
        double capacityHours = totalCapacity.doubleValue();

        if (capacityHours <= 0) {
            return 0.0;
        }

        return (forecastHours / capacityHours) * 100.0;
    }

    /**
     * Calculates Bench FTE Count (< 50% Utilization) KPI.
     * Returns count of consultants currently on the bench with low utilization.
     *
     * Business Logic:
     * - Uses last 4 weeks (1 month) as measurement window
     * - Calculates user-level utilization: (billable_hours / net_available_hours * 100)
     * - Counts users where utilization < 50%
     * - Compares to prior 4-week period for trend analysis
     *
     * @param fromDate Start date (optional, auto-calculated)
     * @param toDate End date (optional, defaults to today)
     * @param practices Multi-select practice filter
     * @param companyIds Multi-select company filter
     * @return BenchCountDTO with current/prior counts and absolute change
     */
    @CacheResult(cacheName = "delivery-bench-count")
    public BenchCountDTO getBenchCount(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // 1. Normalize dates to 4-week window ending at toDate
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(1).withDayOfMonth(1);

        // 2. Calculate prior 4-week window
        LocalDate priorFromDate = fromDateNormalized.minusMonths(1);
        LocalDate priorToDate = toDateNormalized.minusMonths(1);

        log.debugf("Bench Count: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current bench count
        int currentCount = queryBenchCount(
                fromDateNormalized, toDateNormalized,
                practices, companyIds);

        // 4. Query prior bench count
        int priorCount = queryBenchCount(
                priorFromDate, priorToDate,
                practices, companyIds);

        // 5. Calculate absolute change
        int change = currentCount - priorCount;

        log.debugf("Bench Count: current=%d, prior=%d, change=%+d",
                currentCount, priorCount, change);

        // 6. Return DTO
        return new BenchCountDTO(currentCount, priorCount, change);
    }

    /**
     * Query bench count: users with < 50% utilization.
     * Uses CTE to calculate user-level utilization, then counts users below threshold.
     *
     * @param fromDate Start date
     * @param toDate End date
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Count of bench users
     */
    private int queryBenchCount(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryBenchCount: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL query with CTE
        StringBuilder sql = new StringBuilder();
        sql.append("WITH user_util AS ( ");
        sql.append("  SELECT ");
        sql.append("    f.user_id, ");
        sql.append("    SUM(f.billable_hours) AS billable, ");
        sql.append("    SUM(f.net_available_hours) AS available ");
        sql.append("  FROM fact_user_utilization f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.net_available_hours > 0 ");

        // Optional filters
        if (practices != null && !practices.isEmpty()) {
            sql.append("    AND f.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        sql.append("  GROUP BY f.user_id ");
        sql.append(") ");
        sql.append("SELECT COUNT(*) AS bench_count ");
        sql.append("FROM user_util ");
        sql.append("WHERE (billable / available * 100) < 50 ");

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract result
        Object resultObj = query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal result = resultObj != null
            ? BigDecimal.valueOf(((Number) resultObj).doubleValue())
            : BigDecimal.ZERO;

        int count = (result != null) ? result.intValue() : 0;

        log.tracef("Range [%s to %s]: bench_count=%d", fromMonthKey, toMonthKey, count);

        return count;
    }

    /**
     * Calculates Overload Count (> 95% Utilization) KPI.
     * Returns count of consultants currently overloaded with high utilization.
     *
     * Business Logic:
     * - Uses last 4 weeks (1 month) as measurement window
     * - Calculates user-level utilization: (billable_hours / net_available_hours * 100)
     * - Counts users where utilization > 95%
     * - Compares to prior 4-week period for trend analysis
     *
     * @param fromDate Start date (optional, auto-calculated)
     * @param toDate End date (optional, defaults to today)
     * @param practices Multi-select practice filter
     * @param companyIds Multi-select company filter
     * @return OverloadCountDTO with current/prior counts and absolute change
     */
    @CacheResult(cacheName = "delivery-overload-count")
    public OverloadCountDTO getOverloadCount(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // 1. Normalize dates to 4-week window ending at toDate
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(1).withDayOfMonth(1);

        // 2. Calculate prior 4-week window
        LocalDate priorFromDate = fromDateNormalized.minusMonths(1);
        LocalDate priorToDate = toDateNormalized.minusMonths(1);

        log.debugf("Overload Count: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current overload count
        int currentCount = queryOverloadCount(
                fromDateNormalized, toDateNormalized,
                practices, companyIds);

        // 4. Query prior overload count
        int priorCount = queryOverloadCount(
                priorFromDate, priorToDate,
                practices, companyIds);

        // 5. Calculate absolute change
        int change = currentCount - priorCount;

        log.debugf("Overload Count: current=%d, prior=%d, change=%+d",
                currentCount, priorCount, change);

        // 6. Return DTO
        return new OverloadCountDTO(currentCount, priorCount, change);
    }

    /**
     * Query overload count: users with > 95% utilization.
     * Uses CTE to calculate user-level utilization, then counts users above threshold.
     *
     * @param fromDate Start date
     * @param toDate End date
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Count of overloaded users
     */
    private int queryOverloadCount(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryOverloadCount: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL query with CTE
        StringBuilder sql = new StringBuilder();
        sql.append("WITH user_util AS ( ");
        sql.append("  SELECT ");
        sql.append("    f.user_id, ");
        sql.append("    SUM(f.billable_hours) AS billable, ");
        sql.append("    SUM(f.net_available_hours) AS available ");
        sql.append("  FROM fact_user_utilization f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.net_available_hours > 0 ");

        // Optional filters
        if (practices != null && !practices.isEmpty()) {
            sql.append("    AND f.practice_id IN (:practices) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        sql.append("  GROUP BY f.user_id ");
        sql.append(") ");
        sql.append("SELECT COUNT(*) AS overload_count ");
        sql.append("FROM user_util ");
        sql.append("WHERE (billable / available * 100) > 95 ");

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);

        if (practices != null && !practices.isEmpty()) {
            query.setParameter("practices", practices);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract result
        Object resultObj = query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal result = resultObj != null
            ? BigDecimal.valueOf(((Number) resultObj).doubleValue())
            : BigDecimal.ZERO;

        int count = (result != null) ? result.intValue() : 0;

        log.tracef("Range [%s to %s]: overload_count=%d", fromMonthKey, toMonthKey, count);

        return count;
    }

    /**
     * Calculates Realization Rate (TTM) KPI.
     * Returns percentage of potential billable value actually billed to clients.
     *
     * Business Logic:
     * - Normalizes dates to a full 12-month window ending at toDate
     * - Calculates prior year window for YoY comparison
     * - Queries work_full_optimized view for billable work records
     * - Builds 12-month sparkline showing monthly realization rates
     *
     * Realization Formula:
     * - Billed Value = SUM(workduration × rate) where rate > 0
     * - Expected Value = SUM(workduration × rate) (all billable work)
     * - Realization Rate = (Billed Value / Expected Value) × 100
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param practices Multi-select practice filter (e.g., "PM", "DEV", "BA")
     * @param companyIds Multi-select company filter (UUIDs)
     * @return RealizationRateDTO with current/prior percentages, YoY change, and sparkline
     */
    @CacheResult(cacheName = "delivery-realization-rate")
    public RealizationRateDTO getRealizationRate(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window (same 12-month span, 1 year earlier)
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("Realization Rate TTM: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current TTM realization percentage
        double currentPercent = queryRealizationPercent(
                fromDateNormalized, toDateNormalized,
                practices, companyIds);

        // 4. Query prior TTM realization percentage
        double priorPercent = queryRealizationPercent(
                priorFromDate, priorToDate,
                practices, companyIds);

        // 5. Calculate YoY change in percentage points (NOT percentage)
        double yoyChangePoints = currentPercent - priorPercent;

        log.debugf("Realization Rate TTM: current=%.2f%%, prior=%.2f%%, change=%+.2f points",
                currentPercent, priorPercent, yoyChangePoints);

        // 6. Build sparkline (12 monthly realization percentages ending at toDateNormalized)
        double[] sparklineData = buildRealizationSparkline(
                toDateNormalized, practices, companyIds);

        // 7. Return DTO with values rounded to 1 decimal place
        return new RealizationRateDTO(
                Math.round(currentPercent * 10.0) / 10.0,
                Math.round(priorPercent * 10.0) / 10.0,
                Math.round(yoyChangePoints * 10.0) / 10.0,
                sparklineData
        );
    }

    /**
     * Query realization rate percentage for a date range.
     * Calculates: (SUM(billed_value) / SUM(expected_value)) * 100
     *
     * Uses work_full_optimized view which joins work with contracts to get actual and contract rates.
     *
     * @param fromDate Start date
     * @param toDate End date
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Realization percentage (0-100+), rounded to 2 decimals
     */
    private double queryRealizationPercent(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        log.tracef("queryRealizationPercent: date range [%s to %s]", fromDate, toDate);

        // Build SQL query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append("  SUM(CASE WHEN w.rate > 0 THEN w.workduration * w.rate ELSE 0 END) AS billed_value, ");
        sql.append("  SUM(w.workduration * w.rate) AS expected_value ");
        sql.append("FROM work_full_optimized w ");
        sql.append("WHERE w.registered >= :fromDate ");
        sql.append("  AND w.registered <= :toDate ");
        sql.append("  AND w.type = 'CONSULTANT' ");
        sql.append("  AND w.billable = true ");  // Only billable work

        // Optional filters (null-safe)
        // Note: work_full_optimized doesn't have practice_id directly
        // We'll filter by consultant_company_uuid
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND w.consultant_company_uuid IN (:companyIds) ");
        }

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);

        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract results
        Object[] result = (Object[]) query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal billedValue = result[0] != null
            ? BigDecimal.valueOf(((Number) result[0]).doubleValue())
            : BigDecimal.ZERO;
        BigDecimal expectedValue = result[1] != null
            ? BigDecimal.valueOf(((Number) result[1]).doubleValue())
            : BigDecimal.ZERO;

        // Handle null results (no data in range)
        if (billedValue == null || expectedValue == null) {
            log.tracef("No realization data found for range [%s to %s]", fromDate, toDate);
            return 0.0;
        }

        // Calculate realization percentage
        double billed = billedValue.doubleValue();
        double expected = expectedValue.doubleValue();

        if (expected <= 0) {
            log.tracef("Zero expected value for range [%s to %s]", fromDate, toDate);
            return 0.0;
        }

        double realizationPercent = (billed / expected) * 100.0;

        log.tracef("Range [%s to %s]: billed=%.2f, expected=%.2f, realization=%.2f%%",
                fromDate, toDate, billed, expected, realizationPercent);

        return realizationPercent;
    }

    /**
     * Build sparkline data: 12 monthly realization percentages.
     * Array is ordered oldest month first, ending at toDate.
     *
     * @param toDate End date of sparkline (last day of last month)
     * @param practices Optional practice filter
     * @param companyIds Optional company filter
     * @return Array of 12 monthly realization percentages, rounded to 1 decimal
     */
    private double[] buildRealizationSparkline(
            LocalDate toDate,
            Set<String> practices,
            Set<String> companyIds) {

        double[] sparklineData = new double[12];

        // Iterate backwards through 12 months, oldest first in array
        for (int i = 0; i < 12; i++) {
            // Calculate month date (i=0 is oldest, i=11 is most recent)
            LocalDate monthEnd = toDate.minusMonths(11 - i);
            LocalDate monthStart = monthEnd.withDayOfMonth(1);
            monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());

            // Query single month realization
            double monthRealization = queryRealizationPercent(
                    monthStart, monthEnd, practices, companyIds);

            // Round to 1 decimal place
            sparklineData[i] = Math.round(monthRealization * 10.0) / 10.0;

            log.tracef("Sparkline[%d]: %s = %.2f%%", (Object) i, monthEnd.toString().substring(0, 7), sparklineData[i]);
        }

        return sparklineData;
    }

    /**
     * Calculates Average Project Margin (TTM) KPI.
     * Returns gross margin percentage across all projects in the TTM window.
     *
     * Business Logic:
     * - Normalizes dates to a full 12-month window ending at toDate
     * - Calculates prior year window for YoY comparison
     * - Queries fact_project_financials with V118 deduplication
     * - Builds 12-month sparkline showing monthly project margins
     *
     * Margin Formula:
     * - Avg Project Margin = ((Total Revenue - Total Cost) / Total Revenue) × 100
     * - Uses V118 deduplication: GROUP BY project_id, month_key
     *
     * CRITICAL: Supports ALL 5 filters (project-centric, not user-centric like KPI 1-5).
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param sectors Multi-select sector filter (e.g., "PUBLIC", "HEALTH")
     * @param serviceLines Multi-select service line filter (e.g., "PM", "DEV")
     * @param contractTypes Multi-select contract type filter (e.g., "T&M", "FIXED")
     * @param clientId Single client filter (UUID)
     * @param companyIds Multi-select company filter (UUIDs)
     * @return AvgProjectMarginDTO with current/prior percentages, YoY change, and sparkline
     */
    @CacheResult(cacheName = "delivery-avg-project-margin")
    public AvgProjectMarginDTO getAvgProjectMargin(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window (same 12-month span, 1 year earlier)
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("Avg Project Margin TTM: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current TTM margin percentage
        double currentPercent = queryProjectMarginPercent(
                fromDateNormalized, toDateNormalized,
                sectors, serviceLines, contractTypes, clientId, companyIds);

        // 4. Query prior TTM margin percentage
        double priorPercent = queryProjectMarginPercent(
                priorFromDate, priorToDate,
                sectors, serviceLines, contractTypes, clientId, companyIds);

        // 5. Calculate YoY change in percentage points (NOT percentage)
        double yoyChangePoints = currentPercent - priorPercent;

        log.debugf("Avg Project Margin TTM: current=%.2f%%, prior=%.2f%%, change=%+.2f points",
                currentPercent, priorPercent, yoyChangePoints);

        // 6. Build sparkline (12 monthly margin percentages ending at toDateNormalized)
        double[] sparklineData = buildMarginSparkline(
                toDateNormalized, sectors, serviceLines, contractTypes, clientId, companyIds);

        // 7. Return DTO with values rounded to 1 decimal place
        return new AvgProjectMarginDTO(
                Math.round(currentPercent * 10.0) / 10.0,
                Math.round(priorPercent * 10.0) / 10.0,
                Math.round(yoyChangePoints * 10.0) / 10.0,
                sparklineData
        );
    }

    /**
     * Query average project margin percentage for a date range.
     * Calculates: ((SUM(revenue) - SUM(cost)) / SUM(revenue)) * 100
     *
     * CRITICAL: Uses V118 deduplication (GROUP BY project_id, month_key)
     * to prevent double-counting from company distribution in fact_project_financials.
     *
     * @param fromDate Start date (first day of month)
     * @param toDate End date (last day of month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param clientId Optional single client filter
     * @param companyIds Optional company filter
     * @return Margin percentage (0-100), rounded to 2 decimals
     */
    private double queryProjectMarginPercent(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryProjectMarginPercent: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL with V118 deduplication pattern
        StringBuilder sql = new StringBuilder();
        sql.append("WITH dedupe AS ( ");
        sql.append("  SELECT f.project_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) AS revenue, ");
        sql.append("         MAX(f.direct_delivery_cost_dkk) AS cost ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.recognized_revenue_dkk > 0 ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");

        // Optional filters (null-safe)
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (clientId != null && !clientId.isBlank()) {
            sql.append("    AND f.client_id = :clientId ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication - prevent double-counting
        sql.append("  GROUP BY f.project_id, f.month_key ");
        sql.append(") ");
        sql.append("SELECT ");
        sql.append("  SUM(revenue) AS total_revenue, ");
        sql.append("  SUM(cost) AS total_cost ");
        sql.append("FROM dedupe");

        // Create and bind query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);
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
        if (clientId != null && !clientId.isBlank()) {
            query.setParameter("clientId", clientId);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        // Execute query and extract results
        Object[] result = (Object[]) query.getSingleResult();
        // Safe conversion: handle Double/BigDecimal/Number types from native SQL
        BigDecimal totalRevenue = result[0] != null
            ? BigDecimal.valueOf(((Number) result[0]).doubleValue())
            : BigDecimal.ZERO;
        BigDecimal totalCost = result[1] != null
            ? BigDecimal.valueOf(((Number) result[1]).doubleValue())
            : BigDecimal.ZERO;

        // Handle null results (no data in range)
        if (totalRevenue == null || totalCost == null) {
            log.tracef("No margin data found for range [%s to %s]", fromMonthKey, toMonthKey);
            return 0.0;
        }

        // Calculate margin percentage
        double revenue = totalRevenue.doubleValue();
        double cost = totalCost.doubleValue();

        if (revenue <= 0) {
            log.tracef("Zero revenue for range [%s to %s]", fromMonthKey, toMonthKey);
            return 0.0;
        }

        double marginPercent = ((revenue - cost) / revenue) * 100.0;

        log.tracef("Range [%s to %s]: revenue=%.2f, cost=%.2f, margin=%.2f%%",
                fromMonthKey, toMonthKey, revenue, cost, marginPercent);

        return marginPercent;
    }

    /**
     * Build sparkline data: 12 monthly project margin percentages.
     * Array is ordered oldest month first, ending at toDate.
     *
     * @param toDate End date of sparkline (last day of last month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param clientId Optional single client filter
     * @param companyIds Optional company filter
     * @return Array of 12 monthly margin percentages, rounded to 1 decimal
     */
    private double[] buildMarginSparkline(
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            String clientId,
            Set<String> companyIds) {

        double[] sparklineData = new double[12];

        // Iterate backwards through 12 months, oldest first in array
        for (int i = 0; i < 12; i++) {
            // Calculate month date (i=0 is oldest, i=11 is most recent)
            LocalDate monthEnd = toDate.minusMonths(11 - i);
            LocalDate monthStart = monthEnd.withDayOfMonth(1);
            monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());

            // Query single month margin
            double monthMargin = queryProjectMarginPercent(
                    monthStart, monthEnd, sectors, serviceLines, contractTypes, clientId, companyIds);

            // Round to 1 decimal place
            sparklineData[i] = Math.round(monthMargin * 10.0) / 10.0;

            log.tracef("Sparkline[%d]: %s = %.2f%%", (Object) i, monthEnd.toString().substring(0, 7), sparklineData[i]);
        }

        return sparklineData;
    }
}
