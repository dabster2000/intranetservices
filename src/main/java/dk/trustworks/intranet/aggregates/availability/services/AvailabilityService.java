package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.model.CompanyAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.NumberUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class AvailabilityService {

    @PersistenceContext
    EntityManager em;

    public List<EmployeeAvailabilityPerMonth> getAllEmployeeAvailabilityByPeriod(LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(EmployeeAvailabilityPerDayAggregate.list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and consultantType = 'CONSULTANT' and statusType != 'TERMINATED'", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue()));
    }

    public List<EmployeeAvailabilityPerMonth> getCompanyEmployeeAvailabilityByPeriod(Company company, LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(EmployeeAvailabilityPerDayAggregate.list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5 and consultantType = 'CONSULTANT' and statusType != 'TERMINATED'", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue(), company));
    }

    public List<CompanyAvailabilityPerMonth> getCompanyAvailabilityByPeriod(Company company, LocalDate startDate, LocalDate endDate) {
        String sql = "STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5 and consultantType = 'CONSULTANT' and statusType != 'TERMINATED'";
        System.out.println("sql = " + sql);
        return getEmployeeAvailabilityPerMonths(company, startDate, endDate, sql);
    }

    public List<EmployeeAvailabilityPerMonth> getEmployeeAvailability(User user) {
        return getEmployeeAvailabilityPerMonths(EmployeeAvailabilityPerDayAggregate.list("consultantType = ?1 AND statusType != ?2 AND user = ?3", ConsultantType.CONSULTANT, StatusType.TERMINATED,user));
    }


    public List<EmployeeAvailabilityPerDayAggregate> getEmployeeDataPerDay(String useruuid, LocalDate fromDate, LocalDate toDate) {
        return EmployeeAvailabilityPerDayAggregate.list("documentDate >= ?1 AND documentDate < ?2 AND user = ?3", fromDate, toDate, User.findById(useruuid));
    }

    public double getSumOfAvailableHoursByUsersAndMonth(LocalDate localDate, String... uuids) {
        return ((Number) em.createNativeQuery("select sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours) as value " +
                "from bi_availability_per_day e " +
                "where e.status_type = 'ACTIVE' and e.consultant_type = 'CONSULTANT' and e.useruuid in ('" + String.join("','", uuids) + "') " +
                "     AND e.year = " + localDate.getYear() + " " +
                "     AND e.month = " + localDate.getMonthValue() + "; ").getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();
    }

    public List<EmployeeAvailabilityPerMonth> getEmployeeDataPerMonth(String useruuid, LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(getEmployeeDataPerDay(useruuid, fromdate, todate));
        /*
        Query nativeQuery = em.createNativeQuery("SELECT " +
                "    ad_agg.id, " +
                "    ad_agg.useruuid, " +
                "    ad_agg.consultant_type, " +
                "    ad_agg.status_type, " +
                "    ad_agg.companyuuid, " +
                "    ad_agg.year, " +
                "    ad_agg.month, " +
                "    ad_agg.gross_available_hours, " +
                "    ad_agg.unavailable_hours, " +
                "    ad_agg.vacation_hours, " +
                "    ad_agg.sick_hours, " +
                "    ad_agg.maternity_leave_hours, " +
                "    ad_agg.non_payd_leave_hours, " +
                "    ad_agg.paid_leave_hours, " +
                "    COALESCE(ww.workduration, 0) AS registered_billable_hours, " +
                "    COALESCE(ww.total_billed, 0) AS registered_amount, " +
                "    COALESCE(bb.budgetHours, 0) AS budget_hours, " +
                "    ad_agg.avg_salary, " +
                "    ad_agg.is_tw_bonus_eligible " +
                "FROM ( " +
                "    SELECT " +
                "        MIN(ad.id) AS id, " +
                "        ad.useruuid, " +
                "        ad.consultant_type, " +
                "        ad.status_type, " +
                "        ad.companyuuid, " +
                "        ad.year, " +
                "        ad.month, " +
                "        SUM(ad.gross_available_hours) AS gross_available_hours, " +
                "        SUM(ad.unavailable_hours) AS unavailable_hours, " +
                "        SUM(ad.vacation_hours) AS vacation_hours, " +
                "        SUM(ad.sick_hours) AS sick_hours, " +
                "        SUM(ad.maternity_leave_hours) AS maternity_leave_hours, " +
                "        SUM(ad.non_payd_leave_hours) AS non_payd_leave_hours, " +
                "        SUM(ad.paid_leave_hours) AS paid_leave_hours, " +
                "        AVG(ad.salary) AS avg_salary, " +
                "        MAX(ad.is_tw_bonus_eligible) AS is_tw_bonus_eligible " +
                "    FROM twservices.bi_availability_per_day ad " +
                "    WHERE useruuid = :useruuid and document_date >= :startDate and document_date < :endDate " +
                "    GROUP BY ad.useruuid, ad.year, ad.month, ad.companyuuid " +
                ") ad_agg " +
                "LEFT JOIN ( " +
                "    SELECT " +
                "        w.useruuid, " +
                "        YEAR(w.registered) AS year, " +
                "        MONTH(w.registered) AS month, " +
                "        SUM(w.workduration) AS workduration, " +
                "        SUM(IFNULL(w.rate, 0) * w.workduration) AS total_billed " +
                "    FROM twservices.work_full w " +
                "    WHERE w.rate > 0 and useruuid = :useruuid and registered >= :startDate and registered < :endDate " +
                "    GROUP BY w.useruuid, YEAR(w.registered), MONTH(w.registered) " +
                ") ww ON ww.year = ad_agg.year AND ww.month = ad_agg.month AND ww.useruuid = ad_agg.useruuid " +
                "LEFT JOIN ( " +
                "    select " +
                "       `ad`.`useruuid`, " +
                "       `ad`.`year`             AS `year`, " +
                "       `ad`.`month`            AS `month`, " +
                "       sum(`ad`.`budgetHours`) AS `budgetHours` " +
                "from `twservices`.`bi_budget_per_day` `ad` " +
                "where useruuid = :useruuid and document_date >= :startDate and document_date < :endDate " +
                "group by `ad`.`useruuid`, `ad`.`year`, `ad`.`month` " +
                ") bb ON bb.year = ad_agg.year AND bb.month = ad_agg.month AND bb.useruuid = ad_agg.useruuid " +
                "ORDER BY ad_agg.year, ad_agg.month;", EmployeeAvailabilityPerMonth.class);
        nativeQuery.setParameter("useruuid", useruuid);
        nativeQuery.setParameter("startDate", fromdate);
        nativeQuery.setParameter("endDate", todate);
        return nativeQuery.getResultList();

         */
    }

    @NotNull
    private ArrayList<CompanyAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(Company company, LocalDate startDate, LocalDate endDate, String sql) {
        return new ArrayList<>(EmployeeAvailabilityPerDayAggregate.<EmployeeAvailabilityPerDayAggregate>list(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), company)
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            EmployeeAvailabilityPerDayAggregate example = list.get(0);
                            int year = example.getYear();
                            int month = example.getMonth();
                            User user = example.getUser();
                            //Company company = example.getCompany();
                            ConsultantType consultantType = example.getConsultantType();
                            StatusType status = example.getStatusType();

                            double grossAvailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getGrossAvailableHours().doubleValue()).sum();
                            double unavavailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getUnavavailableHours().doubleValue()).sum();
                            double vacationHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getVacationHours().doubleValue()).sum();
                            double sickHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getSickHours().doubleValue()).sum();
                            double maternityLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getMaternityLeaveHours().doubleValue()).sum();
                            double nonPaydLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getNonPaydLeaveHours().doubleValue()).sum();
                            double paidLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getPaidLeaveHours().doubleValue()).sum();
                            double salary = list.stream().mapToInt(EmployeeAvailabilityPerDayAggregate::getSalary).average().orElse(0.0);


                            return new CompanyAvailabilityPerMonth(year, month, company, BigDecimal.valueOf(grossAvailableHours), BigDecimal.valueOf(unavavailableHours), BigDecimal.valueOf(vacationHours), BigDecimal.valueOf(sickHours), BigDecimal.valueOf(maternityLeaveHours), BigDecimal.valueOf(nonPaydLeaveHours), BigDecimal.valueOf(paidLeaveHours), NumberUtils.convertDoubleToInt(salary));
                        })
                ))
                .values());
    }

    @NotNull
    private List<EmployeeAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(List<EmployeeAvailabilityPerDayAggregate> aggregates) {
        return aggregates
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()), e.getUser()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            EmployeeAvailabilityPerDayAggregate example = list.get(0);
                            int year = example.getYear();
                            int month = example.getMonth();
                            User user = example.getUser();
                            Company company = example.getCompany();
                            ConsultantType consultantType = example.getConsultantType();
                            StatusType status = example.getStatusType();

                            double grossAvailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getGrossAvailableHours().doubleValue()).sum();
                            double unavavailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getUnavavailableHours().doubleValue()).sum();
                            double vacationHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getVacationHours().doubleValue()).sum();
                            double sickHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getSickHours().doubleValue()).sum();
                            double maternityLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getMaternityLeaveHours().doubleValue()).sum();
                            double nonPaydLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getNonPaydLeaveHours().doubleValue()).sum();
                            double paidLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getPaidLeaveHours().doubleValue()).sum();
                            double salary = list.stream().mapToDouble(EmployeeAvailabilityPerDayAggregate::getSalary).average().orElse(0.0);
                            boolean isTwBonusEligible = list.stream().allMatch(EmployeeAvailabilityPerDayAggregate::isTwBonusEligible);


                            return new EmployeeAvailabilityPerMonth(year, month, company, user.getUuid(), consultantType, status, BigDecimal.valueOf(grossAvailableHours), BigDecimal.valueOf(unavavailableHours), BigDecimal.valueOf(vacationHours), BigDecimal.valueOf(sickHours), BigDecimal.valueOf(maternityLeaveHours), BigDecimal.valueOf(nonPaydLeaveHours), BigDecimal.valueOf(paidLeaveHours), BigDecimal.valueOf(salary), isTwBonusEligible);
                        })
                ))
                .values().stream().toList();
    }
}
