package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.ActiveClientsDTO;
import dk.trustworks.intranet.aggregates.finance.dto.AvgRevenuePerClientDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConcentrationIndexDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Set;

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
}
