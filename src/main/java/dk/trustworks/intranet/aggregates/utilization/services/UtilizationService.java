package dk.trustworks.intranet.aggregates.utilization.services;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.dto.DateValueDTO;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
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


    
    public void calcUtilization(String useruuid, int year, int month) {
        String sql = "SELECT " +
                "    ed.useruuid, ed.year, ed.month, " +
                "    (100 * (wd.workduration / (ed.gross_available_hours - ed.paid_leave_hours - ed.non_payd_leave_hours - ed.non_payd_leave_hours - ed.maternity_leave_hours - ed.sick_hours - ed.vacation_hours - ed.unavailable_hours))) as actual_utilization, " +
                "    (100 * (bd.budgetHours / (ed.gross_available_hours - ed.paid_leave_hours - ed.non_payd_leave_hours - ed.non_payd_leave_hours - ed.maternity_leave_hours - ed.sick_hours - ed.vacation_hours - ed.unavailable_hours))) as contract_utilization " +
                "FROM employee_data_per_month ed " +
                "LEFT JOIN " +
                "    (select wdpm.useruuid, wdpm.year, wdpm.month, sum(wdpm.workduration) workduration from work_data_per_month wdpm where useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and year = 2023 and month = 11 group by year, month, useruuid) wd on ed.month = wd.month and ed.year = wd.year and ed.useruuid = wd.useruuid " +
                "LEFT JOIN " +
                "    (select bdpm.useruuid, bdpm.year, bdpm.month, sum(bdpm.budgetHours) budgetHours from budget_data_per_month bdpm where useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and year = 2023 and month = 11 group by year, month, useruuid) bd on ed.month = bd.month and ed.year = bd.year and ed.useruuid = bd.useruuid " +
                "where ed.useruuid = '67874df9-7629-4dee-8ab5-4547e63b310e' and ed.year = 2023 and ed.month = 11;";

    }

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

    public double calculateCompanyAvailabilityByPeriod(String companyuuid, LocalDate startDate, LocalDate endDate) {
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
                            "and b.companyuuid = :companyuuid");

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
    
}
