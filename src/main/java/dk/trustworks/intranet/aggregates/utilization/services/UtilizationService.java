package dk.trustworks.intranet.aggregates.utilization.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.dto.BudgetFulfillmentDTO;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.KeyDateValueListDTO;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class UtilizationService {

    @Inject
    EntityManager em;

    @Inject
    AvailabilityService availabilityService;

    @CacheResult(cacheName = "utilization")
    public @NotNull List<DateValueDTO> calculateActualUtilizationPerMonthByConsultant(String useruuid, LocalDate fromDate, LocalDate toDate, List<DateValueDTO> workService) {
        List<EmployeeAvailabilityPerMonth> availabilityPerMonths = availabilityService.getEmployeeDataPerMonth(useruuid, fromDate, toDate);

        List<DateValueDTO> results = new ArrayList<>();

        do {
            LocalDate finalFromDate = fromDate;
            workService.stream().filter(b -> b.getDate().getYear() == finalFromDate.getYear() && b.getDate().getMonthValue() == finalFromDate.getMonthValue()).findFirst().ifPresentOrElse(results::add, () -> results.add(new DateValueDTO(finalFromDate, 0.0)));
            fromDate = fromDate.plusMonths(1);
        } while (!fromDate.isAfter(toDate));

        for (DateValueDTO value : results) {
            availabilityPerMonths.stream().filter(a -> a.getYear() == value.getDate().getYear() && a.getMonth() == value.getDate().getMonthValue()).findFirst().ifPresentOrElse(a -> value.setValue(value.getValue() / a.getNetAvailableHours()), () -> value.setValue(0.0));
        }
        return results;
    }

    public double calculateCompanyActualUtilizationByPeriod(String companyuuid, LocalDate startDate, LocalDate endDate) {
        try {
            Query query = em.createNativeQuery(
                    "SELECT (SUM(b.registered_billable_hours) / " +
                            "SUM(b.net_available_hours)) AS utilization " +
                            "FROM fact_user_day b " +
                            "WHERE b.document_date >= :startDate " +
                            "AND b.document_date < :endDate " +
                            "AND b.consultant_type = 'CONSULTANT' " +
                            "AND b.status_type = 'ACTIVE' " +
                            (companyuuid.equals("all") ? "" : "AND b.companyuuid = :companyuuid")
            );

            query.setParameter("startDate", startDate);
            query.setParameter("endDate", endDate);
            if (!companyuuid.equals("all")) {
                query.setParameter("companyuuid", companyuuid);
            }

            BigDecimal result = (BigDecimal) query.getSingleResult();
            return result != null ? result.doubleValue() : 0.0;
        } catch (NoResultException e) {
            return 0.0;
        }
    }

    /**
     * Calculates team budget utilization per month.
     * Single aggregate query replacing inline SQL from UtilizationResource.
     */
    @SuppressWarnings("unchecked")
    public List<DateValueDTO> calculateTeamBudgetUtilization(String teamuuid, LocalDate fromDate, LocalDate toDate) {
        String sql = """
                SELECT
                    bdd.year,
                    bdd.month,
                    COALESCE(SUM(b.total_budget), 0) AS budget_hours,
                    GREATEST(0.0, SUM(bdd.net_available_hours)) AS available_hours
                FROM teamroles tr
                JOIN fact_user_day bdd
                    ON bdd.useruuid = tr.useruuid
                    AND bdd.document_date >= :fromDate
                    AND bdd.document_date < :toDate
                    AND bdd.status_type = 'ACTIVE'
                    AND bdd.consultant_type = 'CONSULTANT'
                LEFT JOIN (
                    SELECT useruuid, document_date, SUM(budgetHours) AS total_budget
                    FROM fact_budget_day
                    GROUP BY useruuid, document_date
                ) b ON b.useruuid = tr.useruuid
                    AND b.document_date = bdd.document_date
                WHERE tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= bdd.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > bdd.document_date)
                GROUP BY bdd.year, bdd.month
                ORDER BY bdd.year, bdd.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("teamId", teamuuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(t -> {
                    double budget = ((Number) t.get("budget_hours")).doubleValue();
                    double available = ((Number) t.get("available_hours")).doubleValue();
                    return new DateValueDTO(
                            LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1),
                            available > 0 ? budget / available : 0.0
                    );
                })
                .toList();
    }

    /**
     * Calculates team actual utilization per month.
     * Single aggregate query replacing inline SQL from UtilizationResource.
     */
    @SuppressWarnings("unchecked")
    public List<DateValueDTO> calculateTeamActualUtilization(String teamuuid, LocalDate fromDate, LocalDate toDate) {
        String sql = """
                SELECT
                    DISTINCT_BDD.year,
                    DISTINCT_BDD.month,
                    COALESCE(SUM(CASE WHEN wf.rate > 0 THEN wf.workduration ELSE 0 END), 0) AS billable_hours,
                    GREATEST(0.0, SUM(DISTINCT_BDD.net_available_hours)) AS available_hours
                FROM teamroles tr
                JOIN (
                    SELECT
                        useruuid, year, month, document_date, net_available_hours
                    FROM fact_user_day
                    WHERE document_date >= :fromDate
                        AND document_date < :toDate
                        AND status_type = 'ACTIVE'
                        AND consultant_type = 'CONSULTANT'
                ) DISTINCT_BDD
                    ON DISTINCT_BDD.useruuid = tr.useruuid
                LEFT JOIN work_full wf
                    ON wf.useruuid = tr.useruuid
                    AND wf.registered = DISTINCT_BDD.document_date
                WHERE tr.teamuuid = :teamId
                    AND tr.membertype = 'MEMBER'
                    AND tr.startdate <= DISTINCT_BDD.document_date
                    AND (tr.enddate IS NULL OR tr.enddate > DISTINCT_BDD.document_date)
                GROUP BY DISTINCT_BDD.year, DISTINCT_BDD.month
                ORDER BY DISTINCT_BDD.year, DISTINCT_BDD.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("teamId", teamuuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(t -> {
                    double billable = ((Number) t.get("billable_hours")).doubleValue();
                    double available = ((Number) t.get("available_hours")).doubleValue();
                    return new DateValueDTO(
                            LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1),
                            available > 0 ? billable / available : 0.0
                    );
                })
                .toList();
    }

    /**
     * Calculates employee budget utilization per month using a single aggregate query.
     * Replaces the N+1 pattern (loop over users with individual queries).
     */
    @SuppressWarnings("unchecked")
    public List<KeyDateValueListDTO> calculateEmployeeBudgetUtilization(String companyuuid, LocalDate fromDate, LocalDate toDate) {
        String sql = """
                SELECT
                    bdd.useruuid,
                    bdd.year,
                    bdd.month,
                    COALESCE(budget.budgetHours, 0) AS budget_hours,
                    GREATEST(0.0, SUM(bdd.net_available_hours)) AS available_hours
                FROM fact_user_day bdd
                LEFT JOIN (
                    SELECT useruuid, year, month, SUM(budgetHours) AS budgetHours
                    FROM fact_budget_day
                    WHERE document_date >= :fromDate AND document_date < :toDate
                    GROUP BY useruuid, year, month
                ) budget ON bdd.useruuid = budget.useruuid
                    AND bdd.year = budget.year
                    AND bdd.month = budget.month
                WHERE bdd.companyuuid = :companyuuid
                    AND bdd.document_date >= :fromDate
                    AND bdd.document_date < :toDate
                    AND bdd.consultant_type = 'CONSULTANT'
                    AND bdd.status_type = 'ACTIVE'
                GROUP BY bdd.useruuid, bdd.year, bdd.month
                ORDER BY bdd.useruuid, bdd.year, bdd.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("companyuuid", companyuuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        // Group results by user UUID
        Map<String, List<DateValueDTO>> userMap = new LinkedHashMap<>();
        for (Tuple t : results) {
            String useruuid = (String) t.get("useruuid");
            double budget = ((Number) t.get("budget_hours")).doubleValue();
            double available = ((Number) t.get("available_hours")).doubleValue();
            LocalDate monthDate = LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1);
            double utilization = available > 0 ? budget / available : 0.0;

            userMap.computeIfAbsent(useruuid, k -> new ArrayList<>())
                    .add(new DateValueDTO(monthDate, utilization));
        }

        return userMap.entrySet().stream()
                .map(e -> new KeyDateValueListDTO(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Calculates employee actual utilization per month using a single aggregate query.
     * Replaces the N+1 pattern (loop over users with individual queries).
     */
    @SuppressWarnings("unchecked")
    public List<KeyDateValueListDTO> calculateEmployeeActualUtilization(String companyuuid, LocalDate fromDate, LocalDate toDate) {
        String sql = """
                SELECT
                    bdd.useruuid,
                    u.firstname,
                    u.lastname,
                    bdd.year,
                    bdd.month,
                    COALESCE(SUM(bdd.registered_billable_hours), 0) AS billable_hours,
                    GREATEST(0.0, SUM(bdd.net_available_hours)) AS available_hours
                FROM fact_user_day bdd
                JOIN user u ON u.uuid = bdd.useruuid
                WHERE bdd.companyuuid = :companyuuid
                    AND bdd.document_date >= :fromDate
                    AND bdd.document_date < :toDate
                    AND bdd.consultant_type = 'CONSULTANT'
                    AND bdd.status_type = 'ACTIVE'
                GROUP BY bdd.useruuid, u.firstname, u.lastname, bdd.year, bdd.month
                ORDER BY bdd.useruuid, bdd.year, bdd.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("companyuuid", companyuuid)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        // Group results by user full name
        Map<String, List<DateValueDTO>> userMap = new LinkedHashMap<>();
        for (Tuple t : results) {
            String firstname = (String) t.get("firstname");
            String lastname = (String) t.get("lastname");
            String fullname = firstname + " " + lastname;
            double billable = ((Number) t.get("billable_hours")).doubleValue();
            double available = ((Number) t.get("available_hours")).doubleValue();
            LocalDate monthDate = LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1);
            double utilization = available > 0 ? billable / available : 0.0;

            userMap.computeIfAbsent(fullname, k -> new ArrayList<>())
                    .add(new DateValueDTO(monthDate, utilization));
        }

        return userMap.entrySet().stream()
                .map(e -> new KeyDateValueListDTO(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Calculates actual utilization per month across all companies.
     * Uses fact_user_day with registered_billable_hours / net_available_hours.
     */
    @SuppressWarnings("unchecked")
    public List<DateValueDTO> calculateAllCompaniesActualUtilizationByMonth(LocalDate fromDate, LocalDate toDate) {
        String sql = """
                SELECT
                    bdd.year,
                    bdd.month,
                    COALESCE(SUM(bdd.registered_billable_hours), 0) AS billable_hours,
                    GREATEST(0.0, SUM(bdd.net_available_hours)) AS available_hours
                FROM fact_user_day bdd
                WHERE bdd.document_date >= :fromDate
                    AND bdd.document_date < :toDate
                    AND bdd.consultant_type = 'CONSULTANT'
                    AND bdd.status_type = 'ACTIVE'
                GROUP BY bdd.year, bdd.month
                ORDER BY bdd.year, bdd.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(t -> {
                    double billable = ((Number) t.get("billable_hours")).doubleValue();
                    double available = ((Number) t.get("available_hours")).doubleValue();
                    return new DateValueDTO(
                            LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1),
                            available > 0 ? billable / available : 0.0
                    );
                })
                .toList();
    }

    /**
     * Calculates budget utilization per month across all companies.
     * Uses fact_user_day + fact_budget_day with budgetHours / net_available_hours.
     */
    @SuppressWarnings("unchecked")
    public List<DateValueDTO> calculateAllCompaniesBudgetUtilizationByMonth(LocalDate fromDate, LocalDate toDate) {
        String sql = """
                SELECT
                    bdd.year,
                    bdd.month,
                    COALESCE(SUM(b.total_budget), 0) AS budget_hours,
                    GREATEST(0.0, SUM(bdd.net_available_hours)) AS available_hours
                FROM fact_user_day bdd
                LEFT JOIN (
                    SELECT useruuid, document_date, SUM(budgetHours) AS total_budget
                    FROM fact_budget_day
                    GROUP BY useruuid, document_date
                ) b ON b.useruuid = bdd.useruuid
                    AND b.document_date = bdd.document_date
                WHERE bdd.document_date >= :fromDate
                    AND bdd.document_date < :toDate
                    AND bdd.consultant_type = 'CONSULTANT'
                    AND bdd.status_type = 'ACTIVE'
                GROUP BY bdd.year, bdd.month
                ORDER BY bdd.year, bdd.month
                """;

        List<Tuple> results = em.createNativeQuery(sql, Tuple.class)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        return results.stream()
                .map(t -> {
                    double budget = ((Number) t.get("budget_hours")).doubleValue();
                    double available = ((Number) t.get("available_hours")).doubleValue();
                    return new DateValueDTO(
                            LocalDate.of(((Number) t.get("year")).intValue(), ((Number) t.get("month")).intValue(), 1),
                            available > 0 ? budget / available : 0.0
                    );
                })
                .toList();
    }

    /**
     * Calculates budget fulfillment metrics for a consultant over a period.
     * Returns monthly aggregated data including net available hours, budget hours,
     * registered billable hours, and calculated utilization metrics.
     */
    @CacheResult(cacheName = "budget-fulfillment")
    public @NotNull List<BudgetFulfillmentDTO> calculateBudgetFulfillmentByConsultant(String useruuid, LocalDate fromDate, LocalDate toDate) {
        String sql = "SELECT " +
                "    bdd.year, " +
                "    bdd.month, " +
                "    COALESCE(SUM(bdd.net_available_hours), 0) AS netAvailableHours, " +
                "    COALESCE(SUM(COALESCE(bdd.registered_billable_hours, 0)), 0) AS registeredBillableHours, " +
                "    COALESCE(budget.budgetHours, 0) AS budgetHours " +
                "FROM fact_user_day bdd " +
                "LEFT JOIN ( " +
                "    SELECT useruuid, year, month, SUM(budgetHours) AS budgetHours " +
                "    FROM fact_budget_day " +
                "    WHERE useruuid = :useruuid " +
                "        AND document_date >= :startDate " +
                "        AND document_date < :endDate " +
                "    GROUP BY useruuid, year, month " +
                ") budget ON bdd.useruuid = budget.useruuid " +
                "    AND bdd.year = budget.year " +
                "    AND bdd.month = budget.month " +
                "WHERE bdd.useruuid = :useruuid " +
                "    AND bdd.document_date >= :startDate " +
                "    AND bdd.document_date < :endDate " +
                "GROUP BY bdd.year, bdd.month " +
                "ORDER BY bdd.year, bdd.month";

        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("useruuid", useruuid);
        query.setParameter("startDate", fromDate);
        query.setParameter("endDate", toDate);

        @SuppressWarnings("unchecked")
        List<Tuple> results = query.getResultList();

        return results.stream()
                .map(tuple -> {
                    int year = ((Number) tuple.get("year")).intValue();
                    int month = ((Number) tuple.get("month")).intValue();
                    LocalDate monthDate = LocalDate.of(year, month, 1);

                    Double netAvailableHours = ((Number) tuple.get("netAvailableHours")).doubleValue();
                    Double registeredBillableHours = ((Number) tuple.get("registeredBillableHours")).doubleValue();
                    Double budgetHours = ((Number) tuple.get("budgetHours")).doubleValue();

                    return BudgetFulfillmentDTO.create(monthDate, netAvailableHours, budgetHours, registeredBillableHours);
                })
                .toList();
    }

}
