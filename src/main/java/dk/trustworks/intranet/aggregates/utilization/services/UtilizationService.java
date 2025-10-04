package dk.trustworks.intranet.aggregates.utilization.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.dto.BudgetFulfillmentDTO;
import dk.trustworks.intranet.dto.DateValueDTO;
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
import java.util.List;

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
                    "select (SUM(b.registered_billable_hours) / " +
                            "SUM(GREATEST(gross_available_hours " +
                            "- COALESCE(unavailable_hours, 0) " +
                            "- COALESCE(vacation_hours, 0) " +
                            "- COALESCE(sick_hours, 0) " +
                            "- COALESCE(maternity_leave_hours, 0) " +
                            "- COALESCE(non_payd_leave_hours, 0) " +
                            "- COALESCE(paid_leave_hours, 0), 0))) as utilization " +
                            "from bi_data_per_day b " +
                            "where b.document_date >= :startDate " +
                            "and b.document_date < :endDate " +
                            "and b.consultant_type = 'CONSULTANT' " +
                            "and b.status_type = 'ACTIVE' " +
                            (companyuuid.equals("all")?"":"and b.companyuuid = :companyuuid")
            );

            query.setParameter("startDate", startDate);
            query.setParameter("endDate", endDate);
            query.setParameter("companyuuid", companyuuid);

            BigDecimal result = (BigDecimal) query.getSingleResult();
            return result != null ? result.doubleValue() : 0.0;
        } catch (NoResultException e) {
            return 0.0;
        }
        /*
        return em.createNativeQuery(
                "select (SUM(b.registered_billable_hours) / " +
                        "SUM(GREATEST(gross_available_hours " +
                        "- COALESCE(unavailable_hours, 0) " +
                        "- COALESCE(vacation_hours, 0) " +
                        "- COALESCE(sick_hours, 0) " +
                        "- COALESCE(maternity_leave_hours, 0) " +
                        "- COALESCE(non_payd_leave_hours, 0) " +
                        "- COALESCE(paid_leave_hours, 0), 0))) as utilization " +
                        "from bi_data_per_day b " +
                        "where b.document_date >= '"+ DateUtils.stringIt(startDate) +"' " +
                        "and b.document_date < '"+DateUtils.stringIt(endDate)+"' " +
                        "and b.consultant_type = 'CONSULTANT' " +
                        "and b.status_type = 'ACTIVE'" +
                        "and b.companyuuid = '"+companyuuid+"';")

         */
    }

    /**
     * Calculates budget fulfillment metrics for a consultant over a period.
     * Returns monthly aggregated data including net available hours, budget hours,
     * registered billable hours, and calculated utilization metrics.
     *
     * @param useruuid User UUID to calculate metrics for
     * @param fromDate Start date of the period (inclusive)
     * @param toDate End date of the period (exclusive)
     * @return List of BudgetFulfillmentDTO containing monthly metrics
     */
    @CacheResult(cacheName = "budget-fulfillment")
    public @NotNull List<BudgetFulfillmentDTO> calculateBudgetFulfillmentByConsultant(String useruuid, LocalDate fromDate, LocalDate toDate) {
        String sql = "SELECT " +
                "    bdd.year, " +
                "    bdd.month, " +
                "    COALESCE(SUM(GREATEST(bdd.gross_available_hours " +
                "        - COALESCE(bdd.unavailable_hours, 0) " +
                "        - COALESCE(bdd.vacation_hours, 0) " +
                "        - COALESCE(bdd.sick_hours, 0) " +
                "        - COALESCE(bdd.maternity_leave_hours, 0) " +
                "        - COALESCE(bdd.non_payd_leave_hours, 0) " +
                "        - COALESCE(bdd.paid_leave_hours, 0), 0)), 0) as netAvailableHours, " +
                "    COALESCE(SUM(COALESCE(bdd.registered_billable_hours, 0)), 0) as registeredBillableHours, " +
                "    COALESCE(budget.budgetHours, 0) as budgetHours " +
                "FROM bi_data_per_day bdd " +
                "LEFT JOIN ( " +
                "    SELECT useruuid, year, month, SUM(budgetHours) as budgetHours " +
                "    FROM bi_budget_per_day " +
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
