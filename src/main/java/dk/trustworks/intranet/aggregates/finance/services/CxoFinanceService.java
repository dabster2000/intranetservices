package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.MonthlyRevenueMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.MonthlyUtilizationDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.YearMonth;
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
}
