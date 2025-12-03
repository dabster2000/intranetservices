package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.*;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.utils.TwConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for CxO dashboard client metrics.
 * Queries the fact_project_financials view for client-level KPIs.
 */
@JBossLog
@ApplicationScoped
public class CxoClientService {

    @Inject
    EntityManager em;

    /**
     * Calculates Active Clients (TTM) KPI.
     * Returns the count of distinct clients with revenue in a trailing 12-month window.
     *
     * Business Logic:
     * - Normalizes dates to a full 12-month window ending at toDate
     * - Calculates prior year window for YoY comparison
     * - Uses V118 deduplication to prevent double-counting from company distribution
     * - Builds 12-month sparkline showing monthly active client counts
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param sectors Multi-select sector filter (e.g., "PUBLIC", "HEALTH")
     * @param serviceLines Multi-select service line filter (e.g., "PM", "DEV")
     * @param contractTypes Multi-select contract type filter (e.g., "T&M", "FIXED")
     * @param companyIds Multi-select company filter (UUIDs)
     * @return ActiveClientsDTO with current/prior counts, YoY change, and sparkline
     */
    public ActiveClientsDTO getActiveClients(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window (same 12-month span, 1 year earlier)
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("Active Clients TTM: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current TTM client count
        int currentCount = queryActiveClientCount(
                fromDateNormalized, toDateNormalized,
                sectors, serviceLines, contractTypes, companyIds);

        // 4. Query prior TTM client count
        int priorCount = queryActiveClientCount(
                priorFromDate, priorToDate,
                sectors, serviceLines, contractTypes, companyIds);

        // 5. Calculate YoY metrics
        int yoyChange = currentCount - priorCount;
        double yoyChangePercent = (priorCount > 0)
                ? ((double) (currentCount - priorCount) / priorCount) * 100.0
                : 0.0;

        log.debugf("Active Clients: current=%d, prior=%d, change=%+d (%.2f%%)",
                currentCount, priorCount, yoyChange, yoyChangePercent);

        // 6. Build sparkline (12 monthly client counts ending at toDateNormalized)
        int[] sparklineData = buildSparklineData(
                toDateNormalized, sectors, serviceLines, contractTypes, companyIds);

        // 7. Return DTO
        return new ActiveClientsDTO(
                currentCount,
                priorCount,
                yoyChange,
                Math.round(yoyChangePercent * 100.0) / 100.0, // Round to 2 decimals
                sparklineData
        );
    }

    /**
     * Query count of distinct active clients in a date range.
     * Uses V118 deduplication pattern to prevent double-counting from company distribution.
     *
     * Definition: A client is "active" if they have recognized_revenue_dkk > 0
     * in at least one month within the specified window.
     *
     * CRITICAL: The fact_project_financials view has multiple rows per project-month
     * due to company distribution. Always deduplicate using GROUP BY project_id, month_key
     * before counting distinct clients.
     *
     * @param fromDate Start date (first day of month)
     * @param toDate End date (last day of month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return Count of distinct active clients
     */
    private int queryActiveClientCount(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryActiveClientCount: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL with V118 deduplication pattern
        // Strategy: First deduplicate by project_id + month_key (subquery),
        // then count distinct client_id on deduplicated data
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT dedupe.client_id) AS active_clients ");
        sql.append("FROM ( ");
        sql.append("  SELECT f.client_id, f.project_id, f.month_key ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");
        sql.append("    AND f.recognized_revenue_dkk > 0 ");

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
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication - group by project and month to eliminate company distribution duplicates
        sql.append("  GROUP BY f.project_id, f.month_key ");
        sql.append(") AS dedupe");

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        // Bind optional filter parameters
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

        Number result = (Number) query.getSingleResult();
        int count = result != null ? result.intValue() : 0;

        log.tracef("Active clients in [%s to %s]: %d", fromMonthKey, toMonthKey, count);
        return count;
    }

    /**
     * Build sparkline data showing monthly active client counts for the past 12 months.
     * Each array element represents the count of distinct active clients in a single month.
     *
     * Array structure:
     * - Index 0: Oldest month (12 months ago)
     * - Index 11: Most recent month (anchorDate month)
     *
     * Example for anchorDate = 2024-12-31:
     * - sparkline[0] = active clients in Jan 2024
     * - sparkline[11] = active clients in Dec 2024
     *
     * @param anchorDate End date (last day of most recent month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return Array of 12 monthly active client counts
     */
    private int[] buildSparklineData(
            LocalDate anchorDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        int[] sparkline = new int[12];

        log.tracef("Building sparkline for 12 months ending %s", anchorDate);

        // For each of the last 12 months, calculate active clients for THAT month
        for (int i = 0; i < 12; i++) {
            // Calculate month offset: i=0 is 11 months ago, i=11 is current month
            LocalDate monthEnd = anchorDate.minusMonths(11 - i);
            monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());
            LocalDate monthStart = monthEnd.withDayOfMonth(1);

            // Query active clients for this single month
            int monthlyCount = queryActiveClientCount(
                    monthStart, monthEnd, sectors, serviceLines, contractTypes, companyIds);

            sparkline[i] = monthlyCount;

            log.tracef("Sparkline[%d] (%s): %d clients", Integer.valueOf(i), monthEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")), Integer.valueOf(monthlyCount));
        }

        return sparkline;
    }

    /**
     * Calculates Herfindahl-Hirschman Index (HHI) Concentration metric.
     * Measures revenue concentration risk across client portfolio.
     *
     * Business Logic:
     * - HHI = Σ(market_share_percentage²)
     * - Lower values = more diversified (better)
     * - Higher values = more concentrated (risky)
     * - Normalizes dates to TTM window (12 months)
     * - Calculates prior TTM for comparison
     * - Builds 12-month sparkline of HHI values
     *
     * HHI Interpretation:
     * - < 1000: Highly Diversified
     * - 1000-1800: Moderate Concentration
     * - 1800-2500: Concentrated
     * - > 2500: Dominant Client Risk
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param companyIds Multi-select company filter
     * @return ConcentrationIndexDTO with current/prior HHI, change, and sparkline
     */
    public ConcentrationIndexDTO getConcentrationIndex(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window (same 12-month span, 1 year earlier)
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("HHI Concentration Index: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Calculate current TTM HHI
        double currentHHI = queryHHIIndex(
                fromDateNormalized, toDateNormalized,
                sectors, serviceLines, contractTypes, companyIds);

        // 4. Calculate prior TTM HHI
        double priorHHI = queryHHIIndex(
                priorFromDate, priorToDate,
                sectors, serviceLines, contractTypes, companyIds);

        // 5. Calculate change (negative = improvement = less concentrated)
        double changePoints = currentHHI - priorHHI;

        log.debugf("HHI Index: current=%.2f, prior=%.2f, change=%+.2f",
                currentHHI, priorHHI, changePoints);

        // 6. Build sparkline (12 monthly HHI values)
        double[] sparklineData = buildHHISparkline(
                toDateNormalized, sectors, serviceLines, contractTypes, companyIds);

        // 7. Return DTO
        return new ConcentrationIndexDTO(
                currentHHI,
                priorHHI,
                changePoints,
                sparklineData
        );
    }

    /**
     * Calculates HHI (Herfindahl-Hirschman Index) for a given date range.
     * Uses V118 deduplication pattern to prevent double-counting.
     *
     * HHI Formula:
     * 1. Group revenue by client (sum across all projects)
     * 2. Calculate each client's market share % = (client_revenue / total_revenue) * 100
     * 3. HHI = Σ(market_share_percentage²)
     *
     * Example:
     * - 5 equal clients (20% each): HHI = 5 × (20²) = 2,000
     * - 1 dominant client (80%) + 4 small (5% each): HHI = 6,400 + 100 = 6,500
     *
     * @param fromDate Start date (first day of month)
     * @param toDate End date (last day of month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return HHI value (0-10,000 scale)
     */
    private double queryHHIIndex(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryHHIIndex: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL with V118 deduplication + HHI calculation
        // Strategy:
        // 1. CTE dedupe: Deduplicate by project_id + month_key (V118 pattern)
        // 2. CTE client_revenue: Sum revenue by client
        // 3. CTE total_revenue: Sum all revenue
        // 4. CTE market_shares: Calculate each client's market share percentage
        // 5. Final SELECT: Sum of squared market shares = HHI
        StringBuilder sql = new StringBuilder();

        // CTE 1: Deduplicate project-month combinations
        sql.append("WITH dedupe AS ( ");
        sql.append("  SELECT f.client_id, f.project_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) as recognized_revenue_dkk ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");
        sql.append("    AND f.recognized_revenue_dkk > 0 ");

        // Optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication - prevent double-counting
        sql.append("  GROUP BY f.project_id, f.month_key ");
        sql.append("), ");

        // CTE 2: Aggregate revenue by client
        sql.append("client_revenue AS ( ");
        sql.append("  SELECT client_id, SUM(recognized_revenue_dkk) as client_revenue ");
        sql.append("  FROM dedupe ");
        sql.append("  GROUP BY client_id ");
        sql.append("), ");

        // CTE 3: Calculate total revenue
        sql.append("total_revenue AS ( ");
        sql.append("  SELECT SUM(client_revenue) as total ");
        sql.append("  FROM client_revenue ");
        sql.append("), ");

        // CTE 4: Calculate market share percentage for each client
        sql.append("market_shares AS ( ");
        sql.append("  SELECT (cr.client_revenue / tr.total) * 100 as market_share_pct ");
        sql.append("  FROM client_revenue cr, total_revenue tr ");
        sql.append("  WHERE tr.total > 0 ");
        sql.append(") ");

        // Final SELECT: Sum of squared market shares = HHI
        sql.append("SELECT COALESCE(SUM(market_share_pct * market_share_pct), 0.0) as hhi ");
        sql.append("FROM market_shares");

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        // Bind optional filter parameters
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

        Number result = (Number) query.getSingleResult();
        double hhi = result != null ? result.doubleValue() : 0.0;

        log.tracef("HHI in [%s to %s]: %.2f", fromMonthKey, toMonthKey, hhi);
        return hhi;
    }

    /**
     * Build sparkline data showing monthly HHI values for the past 12 months.
     * Each array element represents the HHI concentration index for a single month.
     *
     * Array structure:
     * - Index 0: Oldest month (12 months ago)
     * - Index 11: Most recent month (anchorDate month)
     *
     * Note: Monthly HHI is less meaningful than TTM HHI (more volatile),
     * but provides trend visualization for executive dashboard.
     *
     * @param anchorDate End date (last day of most recent month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return Array of 12 monthly HHI values
     */
    private double[] buildHHISparkline(
            LocalDate anchorDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        double[] sparkline = new double[12];

        log.tracef("Building HHI sparkline for 12 months ending %s", anchorDate);

        // For each of the last 12 months, calculate HHI for a TTM window ending that month
        for (int i = 0; i < 12; i++) {
            // Calculate month offset: i=0 is 11 months ago, i=11 is current month
            LocalDate monthEnd = anchorDate.minusMonths(11 - i);
            monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());
            LocalDate monthStart = monthEnd.minusMonths(11).withDayOfMonth(1);

            // Calculate HHI for this TTM window
            double monthlyHHI = queryHHIIndex(
                    monthStart, monthEnd, sectors, serviceLines, contractTypes, companyIds);

            sparkline[i] = monthlyHHI;

            log.tracef("Sparkline[%d] (%s): HHI=%.2f",
                    Integer.valueOf(i),
                    monthEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")),
                    Double.valueOf(monthlyHHI));
        }

        return sparkline;
    }

    /**
     * Calculates Average Revenue Per Client (TTM) KPI.
     * Returns the average revenue generated per active client in the TTM window.
     *
     * Business Logic:
     * - Calculates total revenue and active client count for current TTM window
     * - Calculates same metrics for prior year TTM window
     * - Computes average revenue = total revenue / client count
     * - YoY change % = ((Current Avg - Prior Avg) / Prior Avg) × 100
     * - Builds 12-month sparkline showing monthly average revenue per client
     *
     * Uses V118 deduplication pattern to prevent double-counting.
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param companyIds Multi-select company filter (UUIDs)
     * @return AvgRevenuePerClientDTO with current/prior averages, YoY change, and sparkline
     */
    public AvgRevenuePerClientDTO getAvgRevenuePerClient(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("Avg Revenue Per Client TTM: Current window [%s to %s], Prior window [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // 3. Query current TTM metrics (revenue + client count)
        double currentRevenue = queryActualRevenue(
                fromDateNormalized, toDateNormalized,
                sectors, serviceLines, contractTypes, companyIds);
        int currentClientCount = queryActiveClientCount(
                fromDateNormalized, toDateNormalized,
                sectors, serviceLines, contractTypes, companyIds);

        // 4. Query prior TTM metrics
        double priorRevenue = queryActualRevenue(
                priorFromDate, priorToDate,
                sectors, serviceLines, contractTypes, companyIds);
        int priorClientCount = queryActiveClientCount(
                priorFromDate, priorToDate,
                sectors, serviceLines, contractTypes, companyIds);

        // 5. Calculate average revenue per client (avoid division by zero)
        double currentAvgRevenue = (currentClientCount > 0)
                ? currentRevenue / currentClientCount
                : 0.0;
        double priorAvgRevenue = (priorClientCount > 0)
                ? priorRevenue / priorClientCount
                : 0.0;

        // 6. Calculate YoY change %
        double yoyChangePercent = (priorAvgRevenue > 0)
                ? ((currentAvgRevenue - priorAvgRevenue) / priorAvgRevenue) * 100.0
                : 0.0;

        log.debugf("Avg Revenue Per Client: current=%.2f DKK (%d clients), prior=%.2f DKK (%d clients), change=%.2f%%",
                currentAvgRevenue, currentClientCount, priorAvgRevenue, priorClientCount, yoyChangePercent);

        // 7. Build sparkline (12 monthly average revenue per client values)
        double[] sparklineData = buildAvgRevenueSparkline(
                toDateNormalized, sectors, serviceLines, contractTypes, companyIds);

        // 8. Return DTO
        return new AvgRevenuePerClientDTO(
                currentAvgRevenue,
                priorAvgRevenue,
                Math.round(yoyChangePercent * 100.0) / 100.0, // Round to 2 decimals
                sparklineData
        );
    }

    /**
     * Query total actual revenue in a date range.
     * Uses V118 deduplication pattern to prevent double-counting from company distribution.
     *
     * CRITICAL: The fact_project_financials view has multiple rows per project-month
     * due to company distribution. Always deduplicate using GROUP BY project_id, month_key
     * and MAX(recognized_revenue_dkk) before summing revenue.
     *
     * @param fromDate Start date (first day of month)
     * @param toDate End date (last day of month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return Total recognized revenue (DKK)
     */
    private double queryActualRevenue(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        log.tracef("queryActualRevenue: monthKey range [%s to %s]", fromMonthKey, toMonthKey);

        // Build SQL with V118 deduplication pattern
        // Strategy: First deduplicate by project_id + month_key (subquery),
        // then sum MAX(recognized_revenue_dkk) to get total revenue
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COALESCE(SUM(dedupe.max_revenue), 0.0) AS total_revenue ");
        sql.append("FROM ( ");
        sql.append("  SELECT f.project_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) AS max_revenue ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
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
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication - group by project and month
        sql.append("  GROUP BY f.project_id, f.month_key ");
        sql.append(") AS dedupe");

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        // Bind optional filter parameters
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

        Number result = (Number) query.getSingleResult();
        double revenue = result != null ? result.doubleValue() : 0.0;

        log.tracef("Total revenue in [%s to %s]: %.2f DKK", fromMonthKey, toMonthKey, revenue);
        return revenue;
    }

    /**
     * Build sparkline data showing monthly average revenue per client for the past 12 months.
     * Each array element represents average revenue per client in a single month.
     *
     * Formula: Monthly Avg = Total Monthly Revenue / Active Client Count in that month
     *
     * @param anchorDate End date (last day of most recent month)
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return Array of 12 monthly average revenue values (DKK)
     */
    private double[] buildAvgRevenueSparkline(
            LocalDate anchorDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        double[] sparkline = new double[12];

        log.tracef("Building avg revenue sparkline for 12 months ending %s", anchorDate);

        // For each of the last 12 months, calculate avg revenue for THAT month
        for (int i = 0; i < 12; i++) {
            // Calculate month offset: i=0 is 11 months ago, i=11 is current month
            LocalDate monthEnd = anchorDate.minusMonths(11 - i);
            monthEnd = monthEnd.withDayOfMonth(monthEnd.lengthOfMonth());
            LocalDate monthStart = monthEnd.withDayOfMonth(1);

            // Query revenue and client count for this single month
            double monthRevenue = queryActualRevenue(
                    monthStart, monthEnd, sectors, serviceLines, contractTypes, companyIds);
            int monthClientCount = queryActiveClientCount(
                    monthStart, monthEnd, sectors, serviceLines, contractTypes, companyIds);

            // Calculate average (avoid division by zero)
            double monthAvgRevenue = (monthClientCount > 0)
                    ? monthRevenue / monthClientCount
                    : 0.0;

            sparkline[i] = monthAvgRevenue;

            log.tracef("Sparkline[%d] (%s): %.2f DKK avg (%d clients)",
                    Integer.valueOf(i), monthEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")),
                    monthAvgRevenue, Integer.valueOf(monthClientCount));
        }

        return sparkline;
    }

    /**
     * Gets Client Revenue Pareto chart data (Chart A).
     * Returns top 20 clients by TTM revenue with cumulative percentage and margin classification.
     *
     * Business Logic:
     * - Identifies top 20 clients by total TTM revenue
     * - Calculates gross margin percentage for each client
     * - Computes cumulative percentage (Pareto curve)
     * - Classifies margin into bands (High/Medium/Low) for color-coding
     * - Uses V118 deduplication pattern to prevent double-counting
     *
     * Margin Band Thresholds:
     * - High: > 30% (green bar)
     * - Medium: 15-30% (orange bar)
     * - Low: < 15% (red bar)
     *
     * @param fromDate Start date (typically 12 months ago)
     * @param toDate End date (typically today)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param companyIds Multi-select company filter
     * @return ClientRevenueParetoDTO with top 20 clients and total revenue
     */
    public ClientRevenueParetoDTO getClientRevenuePareto(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        String fromMonthKey = String.format("%d%02d", fromDateNormalized.getYear(), fromDateNormalized.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDateNormalized.getYear(), toDateNormalized.getMonthValue());

        log.debugf("Client Revenue Pareto: TTM window [%s to %s]", fromMonthKey, toMonthKey);

        // 2. Build SQL with V118 deduplication
        StringBuilder sql = new StringBuilder();
        sql.append("WITH deduplicated AS ( ");
        sql.append("  SELECT f.client_id, f.project_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) as revenue, ");
        sql.append("         MAX(f.direct_delivery_cost_dkk) as cost ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");

        // Optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication
        sql.append("  GROUP BY f.client_id, f.project_id, f.month_key ");
        sql.append("), ");

        sql.append("client_metrics AS ( ");
        sql.append("  SELECT d.client_id, ");
        sql.append("         SUM(d.revenue) as total_revenue, ");
        sql.append("         SUM(d.cost) as total_cost ");
        sql.append("  FROM deduplicated d ");
        sql.append("  GROUP BY d.client_id ");
        sql.append("), ");

        sql.append("with_margin AS ( ");
        sql.append("  SELECT cm.client_id, cm.total_revenue, cm.total_cost, ");
        sql.append("         CASE WHEN cm.total_revenue > 0 ");
        sql.append("              THEN ((cm.total_revenue - cm.total_cost) / cm.total_revenue) * 100.0 ");
        sql.append("              ELSE 0.0 END as margin_pct ");
        sql.append("  FROM client_metrics cm ");
        sql.append("), ");

        sql.append("ranked AS ( ");
        sql.append("  SELECT wm.client_id, wm.total_revenue, wm.margin_pct, ");
        sql.append("         ROW_NUMBER() OVER (ORDER BY wm.total_revenue DESC) as rank ");
        sql.append("  FROM with_margin wm ");
        sql.append("), ");

        sql.append("total_calc AS ( ");
        sql.append("  SELECT SUM(total_revenue) as grand_total FROM ranked WHERE rank <= 20 ");
        sql.append(") ");

        sql.append("SELECT r.client_id, r.total_revenue, r.margin_pct, ");
        sql.append("       SUM(r.total_revenue) OVER (ORDER BY r.rank) / tc.grand_total * 100.0 as cumulative_pct ");
        sql.append("FROM ranked r, total_calc tc ");
        sql.append("WHERE r.rank <= 20 ");
        sql.append("ORDER BY r.rank");

        // 3. Execute query
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
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // 4. Map results to DTOs
        List<ParetoClientDTO> clients = new ArrayList<>();
        double totalRevenue = 0.0;

        for (Object[] row : results) {
            String clientId = (String) row[0];
            double revenue = ((Number) row[1]).doubleValue();
            double marginPct = ((Number) row[2]).doubleValue();
            double cumulativePct = ((Number) row[3]).doubleValue();

            // Get client name
            Client client = em.find(Client.class, clientId);
            String clientName = (client != null) ? client.getName() : "Unknown Client";

            // Convert revenue to millions
            double revenueM = revenue / 1_000_000.0;

            // Determine margin band
            String marginBand;
            if (marginPct > 30.0) {
                marginBand = "High";
            } else if (marginPct >= 15.0) {
                marginBand = "Medium";
            } else {
                marginBand = "Low";
            }

            clients.add(new ParetoClientDTO(
                    clientId,
                    clientName,
                    revenueM,
                    marginPct,
                    cumulativePct,
                    marginBand
            ));

            totalRevenue += revenue;
        }

        double totalRevenueM = totalRevenue / 1_000_000.0;

        log.debugf("Client Revenue Pareto: %d clients, total %.2f M kr", Integer.valueOf(clients.size()), Double.valueOf(totalRevenueM));

        return new ClientRevenueParetoDTO(clients, totalRevenueM);
    }

    /**
     * Gets Client Portfolio Bubble chart data (Chart B).
     * Returns clients positioned by revenue (X-axis) and margin (Y-axis), grouped by sector.
     *
     * Business Logic:
     * - Calculates TTM revenue and gross margin for all active clients
     * - Normalizes bubble size relative to max revenue
     * - Groups clients by sector for color-coded series
     * - Uses V118 deduplication pattern
     *
     * Chart Quadrants:
     * - Top-Right: High revenue + High margin (strategic accounts)
     * - Top-Left: Low revenue + High margin (growth opportunities)
     * - Bottom-Right: High revenue + Low margin (at-risk accounts)
     * - Bottom-Left: Low revenue + Low margin (consider exit)
     *
     * @param fromDate Start date (typically 12 months ago)
     * @param toDate End date (typically today)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param companyIds Multi-select company filter
     * @return ClientPortfolioBubbleDTO with clients grouped by sector
     */
    public ClientPortfolioBubbleDTO getClientPortfolioBubble(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        String fromMonthKey = String.format("%d%02d", fromDateNormalized.getYear(), fromDateNormalized.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDateNormalized.getYear(), toDateNormalized.getMonthValue());

        log.debugf("Client Portfolio Bubble: TTM window [%s to %s]", fromMonthKey, toMonthKey);

        // 2. Build SQL with V118 deduplication
        StringBuilder sql = new StringBuilder();
        sql.append("WITH deduplicated AS ( ");
        sql.append("  SELECT f.client_id, f.sector_id, f.project_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) as revenue, ");
        sql.append("         MAX(f.direct_delivery_cost_dkk) as cost ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");

        // Optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication
        sql.append("  GROUP BY f.client_id, f.sector_id, f.project_id, f.month_key ");
        sql.append(") ");

        sql.append("SELECT d.client_id, d.sector_id, ");
        sql.append("       SUM(d.revenue) as total_revenue, ");
        sql.append("       SUM(d.cost) as total_cost, ");
        sql.append("       CASE WHEN SUM(d.revenue) > 0 ");
        sql.append("            THEN ((SUM(d.revenue) - SUM(d.cost)) / SUM(d.revenue)) * 100.0 ");
        sql.append("            ELSE 0.0 END as margin_pct ");
        sql.append("FROM deduplicated d ");
        sql.append("GROUP BY d.client_id, d.sector_id ");
        sql.append("HAVING SUM(d.revenue) > 0 ");
        sql.append("ORDER BY total_revenue DESC");

        // 3. Execute query
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
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // 4. Find max revenue for bubble size normalization
        double maxRevenue = 0.0;
        double maxMargin = 0.0;
        for (Object[] row : results) {
            double revenue = ((Number) row[2]).doubleValue();
            double marginPct = ((Number) row[4]).doubleValue();
            if (revenue > maxRevenue) maxRevenue = revenue;
            if (marginPct > maxMargin) maxMargin = marginPct;
        }

        // 5. Map results to DTOs grouped by sector
        Map<String, List<ClientBubbleDTO>> sectorData = new LinkedHashMap<>();

        for (Object[] row : results) {
            String clientId = (String) row[0];
            String sectorId = (String) row[1];
            double revenue = ((Number) row[2]).doubleValue();
            double marginPct = ((Number) row[4]).doubleValue();

            // Get client name
            Client client = em.find(Client.class, clientId);
            String clientName = (client != null) ? client.getName() : "Unknown Client";

            // Convert revenue to millions
            double revenueM = revenue / 1_000_000.0;

            // Calculate normalized bubble size
            double bubbleSize = (maxRevenue > 0) ? (revenue / maxRevenue) * 100.0 : 50.0;

            // Use sector ID as sector name (or map to friendly name if needed)
            String sectorName = (sectorId != null) ? sectorId : "Other";

            ClientBubbleDTO bubble = new ClientBubbleDTO(
                    clientId,
                    clientName,
                    revenueM,
                    marginPct,
                    bubbleSize,
                    sectorName
            );

            sectorData.computeIfAbsent(sectorName, k -> new ArrayList<>()).add(bubble);
        }

        double maxRevenueM = maxRevenue / 1_000_000.0;

        log.debugf("Client Portfolio Bubble: %d sectors, max revenue %.2f M kr",
                Integer.valueOf(sectorData.size()), Double.valueOf(maxRevenueM));

        return new ClientPortfolioBubbleDTO(sectorData, maxRevenueM, maxMargin);
    }

    /**
     * Retrieves the Client Detail Table data for Table E.
     * Returns all clients with comprehensive TTM metrics including:
     * - TTM revenue and gross margin
     * - Year-over-year growth
     * - Active project count
     * - Service line penetration count
     * - Last invoice date
     *
     * Business Logic:
     * - Normalizes dates to TTM window (12 months ending at toDate)
     * - Uses V118 deduplication to prevent double-counting
     * - Calculates YoY growth by comparing current TTM to prior year TTM
     * - Orders clients by TTM revenue descending
     *
     * @param fromDate Start date (optional, auto-calculated if null)
     * @param toDate End date (optional, defaults to today)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param companyIds Multi-select company filter
     * @return ClientDetailTableDTO with list of all clients and total count
     */
    public ClientDetailTableDTO getClientDetailTable(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // 1. Normalize dates to TTM window (12 months ending on toDate)
        LocalDate toDateNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        LocalDate fromDateNormalized = toDateNormalized.minusMonths(11).withDayOfMonth(1);

        // 2. Calculate prior year TTM window for YoY comparison
        LocalDate priorFromDate = fromDateNormalized.minusYears(1);
        LocalDate priorToDate = toDateNormalized.minusYears(1);

        log.debugf("Client Detail Table: Current TTM [%s to %s], Prior TTM [%s to %s]",
                fromDateNormalized, toDateNormalized, priorFromDate, priorToDate);

        // Convert dates to YYYYMM month key format
        String fromMonthKey = String.format("%d%02d", fromDateNormalized.getYear(), fromDateNormalized.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDateNormalized.getYear(), toDateNormalized.getMonthValue());
        String priorFromKey = String.format("%d%02d", priorFromDate.getYear(), priorFromDate.getMonthValue());
        String priorToKey = String.format("%d%02d", priorToDate.getYear(), priorToDate.getMonthValue());

        // 3. Build SQL with V118 deduplication and comprehensive metrics
        StringBuilder sql = new StringBuilder();

        // CTE 1: Deduplicated current TTM data
        sql.append("WITH dedupe_current AS ( ");
        sql.append("  SELECT f.client_id, f.client_name, f.sector_id, ");
        sql.append("         f.project_id, f.service_line_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) as revenue, ");
        sql.append("         MAX(f.direct_delivery_cost_dkk) as cost, ");
        sql.append("         MAX(f.invoice_date) as last_invoice ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");

        // Optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication
        sql.append("  GROUP BY f.client_id, f.project_id, f.month_key ");
        sql.append("), ");

        // CTE 2: Deduplicated prior year TTM data (for YoY calculation)
        sql.append("dedupe_prior AS ( ");
        sql.append("  SELECT f.client_id, ");
        sql.append("         MAX(f.recognized_revenue_dkk) as revenue ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :priorFromKey AND :priorToKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");

        // Same filters for prior period
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        sql.append("  GROUP BY f.client_id, f.project_id, f.month_key ");
        sql.append("), ");

        // CTE 3: Current period metrics by client
        sql.append("current_metrics AS ( ");
        sql.append("  SELECT client_id, client_name, sector_id, ");
        sql.append("         SUM(revenue) as ttm_revenue, ");
        sql.append("         SUM(cost) as ttm_cost, ");
        sql.append("         COUNT(DISTINCT project_id) as active_projects, ");
        sql.append("         COUNT(DISTINCT service_line_id) as service_line_count, ");
        sql.append("         MAX(last_invoice) as last_invoice_date ");
        sql.append("  FROM dedupe_current ");
        sql.append("  GROUP BY client_id, client_name, sector_id ");
        sql.append("), ");

        // CTE 4: Prior period revenue by client (for YoY)
        sql.append("prior_revenue AS ( ");
        sql.append("  SELECT client_id, ");
        sql.append("         SUM(revenue) as prior_ttm_revenue ");
        sql.append("  FROM dedupe_prior ");
        sql.append("  GROUP BY client_id ");
        sql.append(") ");

        // Final SELECT: Join current and prior metrics
        sql.append("SELECT cm.client_id, cm.client_name, cm.sector_id, ");
        sql.append("       cm.ttm_revenue, cm.ttm_cost, ");
        sql.append("       cm.active_projects, cm.service_line_count, ");
        sql.append("       cm.last_invoice_date, ");
        sql.append("       COALESCE(pr.prior_ttm_revenue, 0) as prior_revenue ");
        sql.append("FROM current_metrics cm ");
        sql.append("LEFT JOIN prior_revenue pr ON cm.client_id = pr.client_id ");
        sql.append("ORDER BY cm.ttm_revenue DESC");

        // Execute query
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromMonthKey);
        query.setParameter("toKey", toMonthKey);
        query.setParameter("priorFromKey", priorFromKey);
        query.setParameter("priorToKey", priorToKey);
        query.setParameter("excludedClientIds", TwConstants.EXCLUDED_CLIENT_IDS);

        // Bind optional filter parameters (for both current and prior CTEs)
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
        List<Object[]> results = query.getResultList();

        // Map results to DTOs
        List<ClientDetailDTO> clients = new ArrayList<>();
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (Object[] row : results) {
            String clientId = (String) row[0];
            String clientName = (String) row[1];
            String sector = (String) row[2];
            double ttmRevenue = ((Number) row[3]).doubleValue();
            double ttmCost = ((Number) row[4]).doubleValue();
            int activeProjects = ((Number) row[5]).intValue();
            int serviceLineCount = ((Number) row[6]).intValue();
            Object lastInvoiceObj = row[7];
            double priorRevenue = ((Number) row[8]).doubleValue();

            // Calculate metrics
            double ttmRevenueM = ttmRevenue / 1_000_000.0;
            double grossMarginPct = (ttmRevenue > 0)
                    ? ((ttmRevenue - ttmCost) / ttmRevenue) * 100.0
                    : 0.0;
            double yoyGrowthPct = (priorRevenue > 0)
                    ? ((ttmRevenue - priorRevenue) / priorRevenue) * 100.0
                    : 0.0;

            // Format last invoice date
            String lastInvoiceDate = "";
            if (lastInvoiceObj != null) {
                if (lastInvoiceObj instanceof java.sql.Date) {
                    lastInvoiceDate = ((java.sql.Date) lastInvoiceObj).toLocalDate().format(dateFormatter);
                } else if (lastInvoiceObj instanceof LocalDate) {
                    lastInvoiceDate = ((LocalDate) lastInvoiceObj).format(dateFormatter);
                } else {
                    lastInvoiceDate = lastInvoiceObj.toString();
                }
            }

            ClientDetailDTO dto = new ClientDetailDTO(
                    clientId,
                    clientName,
                    sector != null ? sector : "Unknown",
                    Math.round(ttmRevenueM * 10.0) / 10.0,  // Round to 1 decimal
                    Math.round(grossMarginPct * 10.0) / 10.0,  // Round to 1 decimal
                    Math.round(yoyGrowthPct * 10.0) / 10.0,  // Round to 1 decimal
                    activeProjects,
                    lastInvoiceDate,
                    serviceLineCount
            );

            clients.add(dto);
        }

        log.debugf("Client Detail Table: %d clients retrieved", clients.size());

        return new ClientDetailTableDTO(clients, clients.size());
    }

    /**
     * Calculates Client Retention & Growth Trend (Chart C).
     * Returns quarterly retention metrics for the past 8 fiscal quarters.
     *
     * Business Logic:
     * - Fiscal quarters: Q1=Jul-Sep, Q2=Oct-Dec, Q3=Jan-Mar, Q4=Apr-Jun
     * - Retention rate = (retained clients / previous quarter clients) * 100
     * - New clients = clients in current quarter but not in previous quarter
     * - Churned clients = clients in previous quarter but not in current quarter
     * - Retained clients = clients in both previous and current quarter
     *
     * @param asOfDate Anchor date (typically today)
     * @param sectors Multi-select sector filter
     * @param serviceLines Multi-select service line filter
     * @param contractTypes Multi-select contract type filter
     * @param companyIds Multi-select company filter
     * @return ClientRetentionTrendDTO with 8 quarters of retention data
     */
    public ClientRetentionTrendDTO getClientRetentionTrend(
            LocalDate asOfDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Normalize to end of current fiscal quarter
        LocalDate normalizedDate = (asOfDate != null) ? asOfDate : LocalDate.now();

        log.debugf("Client Retention Trend: calculating 8 quarters from %s", normalizedDate);

        // Calculate 8 fiscal quarters ending at normalized date
        List<QuarterlyRetentionDTO> quarters = new ArrayList<>();

        for (int i = 7; i >= 0; i--) {
            // Calculate quarter start/end (fiscal quarters)
            LocalDate currentQuarterEnd = normalizedDate.minusMonths(i * 3);
            currentQuarterEnd = getFiscalQuarterEnd(currentQuarterEnd);
            LocalDate currentQuarterStart = currentQuarterEnd.minusMonths(2).withDayOfMonth(1);

            LocalDate previousQuarterEnd = currentQuarterStart.minusDays(1);
            LocalDate previousQuarterStart = previousQuarterEnd.minusMonths(2).withDayOfMonth(1);

            // Get client sets for both quarters
            Set<String> currentClients = getActiveClientIds(
                    currentQuarterStart, currentQuarterEnd,
                    sectors, serviceLines, contractTypes, companyIds);
            Set<String> previousClients = getActiveClientIds(
                    previousQuarterStart, previousQuarterEnd,
                    sectors, serviceLines, contractTypes, companyIds);

            // Calculate metrics
            Set<String> retainedClientsSet = new HashSet<>(previousClients);
            retainedClientsSet.retainAll(currentClients);

            Set<String> newClientsSet = new HashSet<>(currentClients);
            newClientsSet.removeAll(previousClients);

            Set<String> churnedClientsSet = new HashSet<>(previousClients);
            churnedClientsSet.removeAll(currentClients);

            int retainedCount = retainedClientsSet.size();
            int newCount = newClientsSet.size();
            int churnedCount = churnedClientsSet.size();

            double retentionRate = (previousClients.size() > 0)
                    ? (retainedCount / (double) previousClients.size()) * 100.0
                    : 0.0;

            // Format quarter label
            String quarterLabel = getFiscalQuarterLabel(currentQuarterEnd);

            // Fetch client names for new and churned clients
            List<String> newClientNames = getClientNames(newClientsSet);
            List<String> churnedClientNames = getClientNames(churnedClientsSet);

            quarters.add(new QuarterlyRetentionDTO(
                    quarterLabel, retentionRate, newCount, churnedCount, retainedCount,
                    newClientNames, churnedClientNames));

            log.tracef("Quarter %s: retention=%.1f%%, new=%d, churned=%d, retained=%d",
                    quarterLabel, retentionRate, newCount, churnedCount, retainedCount);
        }

        return new ClientRetentionTrendDTO(quarters);
    }

    /**
     * Helper: Fetch client names for a set of client IDs.
     * Uses EntityManager for efficient name lookup.
     *
     * @param clientIds Set of client UUIDs
     * @return List of client names, sorted alphabetically, empty list if no clients
     */
    private List<String> getClientNames(Set<String> clientIds) {
        if (clientIds == null || clientIds.isEmpty()) {
            return new ArrayList<>();
        }

        return clientIds.stream()
                .map(id -> {
                    Client client = em.find(Client.class, id);
                    return (client != null && client.getName() != null && !client.getName().isBlank())
                            ? client.getName()
                            : null;
                })
                .filter(name -> name != null)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Gets set of active client IDs in a date range.
     * Used by retention calculation to compare client cohorts across quarters.
     *
     * @param fromDate Start date
     * @param toDate End date
     * @param sectors Optional sector filter
     * @param serviceLines Optional service line filter
     * @param contractTypes Optional contract type filter
     * @param companyIds Optional company filter
     * @return Set of distinct client UUIDs
     */
    private Set<String> getActiveClientIds(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> serviceLines,
            Set<String> contractTypes,
            Set<String> companyIds) {

        String fromMonthKey = String.format("%d%02d", fromDate.getYear(), fromDate.getMonthValue());
        String toMonthKey = String.format("%d%02d", toDate.getYear(), toDate.getMonthValue());

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT dedupe.client_id ");
        sql.append("FROM ( ");
        sql.append("  SELECT f.client_id, f.project_id, f.month_key ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.client_id NOT IN (:excludedClientIds) ");
        sql.append("    AND f.recognized_revenue_dkk > 0 ");

        // Optional filters
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (serviceLines != null && !serviceLines.isEmpty()) {
            sql.append("    AND f.service_line_id IN (:serviceLines) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // V118 deduplication
        sql.append("  GROUP BY f.project_id, f.month_key ");
        sql.append(") AS dedupe");

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
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<String> results = query.getResultList();
        return new HashSet<>(results);
    }

    /**
     * Gets fiscal quarter end date for a given date.
     * Fiscal quarters: Q1=Jul-Sep (Sep 30), Q2=Oct-Dec (Dec 31), Q3=Jan-Mar (Mar 31), Q4=Apr-Jun (Jun 30)
     *
     * @param date Any date in the quarter
     * @return Last day of the fiscal quarter
     */
    private LocalDate getFiscalQuarterEnd(LocalDate date) {
        int month = date.getMonthValue();
        int year = date.getYear();

        if (month >= 7 && month <= 9) {
            // Q1 FY: Jul-Sep -> Sep 30
            return LocalDate.of(year, 9, 30);
        } else if (month >= 10 && month <= 12) {
            // Q2 FY: Oct-Dec -> Dec 31
            return LocalDate.of(year, 12, 31);
        } else if (month >= 1 && month <= 3) {
            // Q3 FY: Jan-Mar -> Mar 31
            return LocalDate.of(year, 3, 31);
        } else {
            // Q4 FY: Apr-Jun -> Jun 30
            return LocalDate.of(year, 6, 30);
        }
    }

    /**
     * Gets fiscal quarter label for a given date.
     * Format: "Q1 FY25" for fiscal year quarters
     *
     * @param date Date in the quarter
     * @return Quarter label
     */
    private String getFiscalQuarterLabel(LocalDate date) {
        int month = date.getMonthValue();
        int year = date.getYear();

        String quarter;
        int fiscalYear;

        if (month >= 7 && month <= 9) {
            quarter = "Q1";
            fiscalYear = year + 1; // Q1 of FY that starts July 1
        } else if (month >= 10 && month <= 12) {
            quarter = "Q2";
            fiscalYear = year + 1;
        } else if (month >= 1 && month <= 3) {
            quarter = "Q3";
            fiscalYear = year;
        } else {
            quarter = "Q4";
            fiscalYear = year;
        }

        return String.format("%s FY%02d", quarter, fiscalYear % 100);
    }

    /**
     * Calculates Service Line Penetration heatmap data (Chart D).
     * Returns revenue matrix showing which service lines are used by top clients.
     *
     * Business Logic:
     * - Matrix: Top 15 clients (rows) × All service lines (columns)
     * - Cell value: TTM revenue in DKK for that client × service line combination
     * - Uses V118 deduplication: GROUP BY client_id, service_line_id, project_id, month_key
     * - Clients ordered by total TTM revenue descending
     *
     * @param fromDate Start date (typically 12 months ago)
     * @param toDate End date (typically today)
     * @param sectors Multi-select sector filter
     * @param contractTypes Multi-select contract type filter (serviceLines excluded intentionally)
     * @param companyIds Multi-select company filter
     * @return ServiceLinePenetrationDTO with client × service line revenue matrix
     */
    public ServiceLinePenetrationDTO getServiceLinePenetration(
            LocalDate fromDate,
            LocalDate toDate,
            Set<String> sectors,
            Set<String> contractTypes,
            Set<String> companyIds) {

        // Normalize to TTM window
        LocalDate toNormalized = (toDate != null)
                ? toDate.withDayOfMonth(toDate.lengthOfMonth())
                : LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
        LocalDate fromNormalized = toNormalized.minusMonths(11).withDayOfMonth(1);

        String fromKey = String.format("%d%02d", fromNormalized.getYear(), fromNormalized.getMonthValue());
        String toKey = String.format("%d%02d", toNormalized.getYear(), toNormalized.getMonthValue());

        log.debugf("Service Line Penetration: TTM window [%s to %s]", fromNormalized, toNormalized);

        // Build SQL with V118 deduplication
        StringBuilder sql = new StringBuilder();
        sql.append("WITH dedupe AS ( ");
        sql.append("  SELECT f.client_id, f.service_line_id, f.project_id, f.month_key, ");
        sql.append("         MAX(f.recognized_revenue_dkk) as revenue ");
        sql.append("  FROM fact_project_financials f ");
        sql.append("  WHERE CAST(f.month_key AS CHAR CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci) ");
        sql.append("    BETWEEN :fromKey AND :toKey ");
        sql.append("    AND f.client_id IS NOT NULL ");
        sql.append("    AND f.service_line_id IS NOT NULL ");

        // Optional filters (serviceLines intentionally excluded per design)
        if (sectors != null && !sectors.isEmpty()) {
            sql.append("    AND f.sector_id IN (:sectors) ");
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            sql.append("    AND f.contract_type_id IN (:contractTypes) ");
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND f.companyuuid IN (:companyIds) ");
        }

        // CRITICAL: V118 deduplication for Chart D
        sql.append("  GROUP BY f.client_id, f.service_line_id, f.project_id, f.month_key ");
        sql.append(") ");
        sql.append("SELECT dedupe.client_id, dedupe.service_line_id, SUM(dedupe.revenue) as total_revenue ");
        sql.append("FROM dedupe ");
        sql.append("GROUP BY dedupe.client_id, dedupe.service_line_id ");
        sql.append("ORDER BY dedupe.client_id, dedupe.service_line_id");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);

        if (sectors != null && !sectors.isEmpty()) {
            query.setParameter("sectors", sectors);
        }
        if (contractTypes != null && !contractTypes.isEmpty()) {
            query.setParameter("contractTypes", contractTypes);
        }
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        // Build client revenue map and service line set
        Map<String, Map<String, Double>> clientServiceRevenue = new HashMap<>();
        Map<String, Double> clientTotalRevenue = new HashMap<>();
        Set<String> allServiceLines = new TreeSet<>();

        for (Object[] row : results) {
            String clientId = (String) row[0];
            String serviceLineId = (String) row[1];
            double revenue = ((Number) row[2]).doubleValue();

            clientServiceRevenue
                    .computeIfAbsent(clientId, k -> new HashMap<>())
                    .put(serviceLineId, revenue);

            clientTotalRevenue.merge(clientId, revenue, Double::sum);
            allServiceLines.add(serviceLineId);
        }

        // Get top 15 clients by total revenue
        List<String> top15ClientIds = clientTotalRevenue.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .limit(15)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Get client names
        List<String> clientNames = new ArrayList<>();
        for (String clientId : top15ClientIds) {
            String clientName = getClientName(clientId);
            clientNames.add(clientName);
        }

        // Build matrix
        List<String> serviceLineList = new ArrayList<>(allServiceLines);
        double[][] matrix = new double[top15ClientIds.size()][serviceLineList.size()];
        double maxRevenue = 0.0;

        for (int i = 0; i < top15ClientIds.size(); i++) {
            String clientId = top15ClientIds.get(i);
            Map<String, Double> clientServices = clientServiceRevenue.getOrDefault(clientId, Map.of());

            for (int j = 0; j < serviceLineList.size(); j++) {
                String serviceLineId = serviceLineList.get(j);
                double revenue = clientServices.getOrDefault(serviceLineId, 0.0);
                matrix[i][j] = revenue;
                maxRevenue = Math.max(maxRevenue, revenue);
            }
        }

        log.debugf("Service Line Penetration: %d clients × %d service lines, max revenue=%.2f DKK",
                Integer.valueOf(clientNames.size()), Integer.valueOf(serviceLineList.size()), Double.valueOf(maxRevenue));

        return new ServiceLinePenetrationDTO(clientNames, serviceLineList, matrix, maxRevenue);
    }

    /**
     * Gets client name by UUID.
     * Uses direct SQL query to client table.
     *
     * @param clientId Client UUID
     * @return Client name or UUID if not found
     */
    private String getClientName(String clientId) {
        try {
            Query query = em.createNativeQuery("SELECT name FROM client WHERE uuid = :clientId");
            query.setParameter("clientId", clientId);
            Object result = query.getSingleResult();
            return result != null ? result.toString() : clientId;
        } catch (Exception e) {
            log.warnf("Failed to get client name for %s, using ID", clientId);
            return clientId;
        }
    }
}
