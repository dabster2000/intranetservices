package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.model.CompanyAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.NumberUtils;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.jbosslog.JBossLog;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class AvailabilityService {

    @PersistenceContext
    EntityManager em;

    @CacheResult(cacheName = "employee-availability")
    public List<EmployeeAvailabilityPerMonth> getAllEmployeeAvailabilityByPeriod(LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(
                BiDataPerDay.<BiDataPerDay>stream("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and (consultantType = 'CONSULTANT' or consultantType = 'STUDENT') and statusType not in ('TERMINATED','PREBOARDING') ", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue()).toList()
        );
    }

    @CacheResult(cacheName = "employee-availability")
    public List<EmployeeAvailabilityPerMonth> getCompanyEmployeeAvailabilityByPeriod(Company company, LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(
                BiDataPerDay.<BiDataPerDay>stream("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5 and consultantType IN ('CONSULTANT','STAFF','STUDENT') and statusType not in ('TERMINATED','PREBOARDING') ", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue(), company).toList()
        );
    }

    @CacheResult(cacheName = "company-availability")
    public List<CompanyAvailabilityPerMonth> getCompanyAvailabilityByPeriod(Company company, LocalDate startDate, LocalDate endDate) {
        String sql = "STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5 and consultantType = 'CONSULTANT' and statusType != 'TERMINATED'";
        return getEmployeeAvailabilityPerMonths(company, startDate, endDate, sql);
    }

    @CacheResult(cacheName = "employee-availability")
    public List<BiDataPerDay> getEmployeeDataPerDay(String useruuid, LocalDate fromDate, LocalDate toDate) {
        return BiDataPerDay.<BiDataPerDay>stream("documentDate >= ?1 AND documentDate < ?2 AND user = ?3", fromDate, toDate, User.findById(useruuid))
                .sorted(Comparator.comparing(BiDataPerDay::getDocumentDate))
                .toList();
    }

    public double getSumOfAvailableHoursByUsersAndMonth(LocalDate localDate, String... uuids) {
        return ((Number) em.createNativeQuery(
                "SELECT GREATEST(0.0, SUM(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours " +
                "  - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours)) AS value " +
                "FROM bi_data_per_day e " +
                "WHERE e.status_type = 'ACTIVE' " +
                "  AND e.consultant_type = 'CONSULTANT' " +
                "  AND e.useruuid IN :uuids " +
                "  AND e.year = :year " +
                "  AND e.month = :month")
                .setParameter("uuids", List.of(uuids))
                .setParameter("year", localDate.getYear())
                .setParameter("month", localDate.getMonthValue())
                .getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();
    }

    public List<EmployeeAvailabilityPerMonth> getEmployeeDataPerMonth(String useruuid, LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(getEmployeeDataPerDay(useruuid, fromdate, todate));
    }

    @NotNull
    private ArrayList<CompanyAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(Company company, LocalDate startDate, LocalDate endDate, String sql) {
        return new ArrayList<>(BiDataPerDay.<BiDataPerDay>stream(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), company)
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            BiDataPerDay example = list.get(0);
                            int year = example.getYear();
                            int month = example.getMonth();
                            User user = example.getUser();
                            //Company company = example.getCompany();
                            ConsultantType consultantType = example.getConsultantType();
                            StatusType status = example.getStatusType();

                            double grossAvailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getGrossAvailableHours().doubleValue()).sum();
                            double unavavailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getUnavailableHours().doubleValue()).sum();
                            double vacationHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getVacationHours().doubleValue()).sum();
                            double sickHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getSickHours().doubleValue()).sum();
                            double maternityLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getMaternityLeaveHours().doubleValue()).sum();
                            double nonPaydLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getNonPaydLeaveHours().doubleValue()).sum();
                            double paidLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getPaidLeaveHours().doubleValue()).sum();
                            double salary = list.stream().mapToInt(BiDataPerDay::getSalary).average().orElse(0.0);


                            return new CompanyAvailabilityPerMonth(year, month, company, BigDecimal.valueOf(grossAvailableHours), BigDecimal.valueOf(unavavailableHours), BigDecimal.valueOf(vacationHours), BigDecimal.valueOf(sickHours), BigDecimal.valueOf(maternityLeaveHours), BigDecimal.valueOf(nonPaydLeaveHours), BigDecimal.valueOf(paidLeaveHours), NumberUtils.convertDoubleToInt(salary));
                        })
                ))
                .values());
    }

    // AvailabilityService.java
    @NotNull
    private List<EmployeeAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(List<BiDataPerDay> aggregates) {
        return aggregates.stream()
                .collect(Collectors.groupingBy(
                        d -> new AbstractMap.SimpleEntry<>(new AbstractMap.SimpleEntry<>(d.getYear(), d.getMonth()), d.getUser()),
                        Collectors.toList()
                ))
                .values().stream()
                .map(list -> {
                    BiDataPerDay first = list.getFirst();
                    int year = first.getYear();
                    int month = first.getMonth();
                    User user = first.getUser();
                    Company company = first.getCompany();
                    ConsultantType consultantType = first.getConsultantType();

                    // ---- Daily → monthly aggregation (use ALL days) ----
                    double grossAvailableHours     = list.stream().map(d -> d.getGrossAvailableHours()     != null ? d.getGrossAvailableHours().doubleValue()     : 0.0).mapToDouble(Double::doubleValue).sum();
                    double unavailableHours        = list.stream().map(d -> d.getUnavailableHours()        != null ? d.getUnavailableHours().doubleValue()        : 0.0).mapToDouble(Double::doubleValue).sum();
                    double vacationHours           = list.stream().map(d -> d.getVacationHours()           != null ? d.getVacationHours().doubleValue()           : 0.0).mapToDouble(Double::doubleValue).sum();
                    double sickHours               = list.stream().map(d -> d.getSickHours()               != null ? d.getSickHours().doubleValue()               : 0.0).mapToDouble(Double::doubleValue).sum();
                    double maternityLeaveHours     = list.stream().map(d -> d.getMaternityLeaveHours()     != null ? d.getMaternityLeaveHours().doubleValue()     : 0.0).mapToDouble(Double::doubleValue).sum();
                    double nonPaydLeaveHours       = list.stream().map(d -> d.getNonPaydLeaveHours()       != null ? d.getNonPaydLeaveHours().doubleValue()       : 0.0).mapToDouble(Double::doubleValue).sum();
                    double paidLeaveHours          = list.stream().map(d -> d.getPaidLeaveHours()          != null ? d.getPaidLeaveHours().doubleValue()          : 0.0).mapToDouble(Double::doubleValue).sum();

                    // Average of daily salary (0 on non-paid/terminated days) => proportional monthly basis
                    double avgSalary = list.stream().mapToDouble(d -> d.getSalary() != null ? d.getSalary() : 0).average().orElse(0.0);

                    long eligibleDays = list.stream().filter(d -> d.getSalary() != null && d.getSalary() > 0).count();
                    boolean isTwBonusEligible = eligibleDays > 0;

                    // Month-level status (keep ACTIVE if any day is not PREBOARDING/TERMINATED)
                    StatusType status = list.stream()
                            .map(BiDataPerDay::getStatusType)
                            .filter(Objects::nonNull)
                            .anyMatch(st -> st != StatusType.PREBOARDING && st != StatusType.TERMINATED)
                            ? StatusType.ACTIVE
                            : StatusType.TERMINATED;

                    return new EmployeeAvailabilityPerMonth(
                            year, month, company, user.getUuid(), consultantType, status,
                            BigDecimal.valueOf(grossAvailableHours),
                            BigDecimal.valueOf(unavailableHours),
                            BigDecimal.valueOf(vacationHours),
                            BigDecimal.valueOf(sickHours),
                            BigDecimal.valueOf(maternityLeaveHours),
                            BigDecimal.valueOf(nonPaydLeaveHours),
                            BigDecimal.valueOf(paidLeaveHours),
                            BigDecimal.valueOf(avgSalary),
                            isTwBonusEligible
                    );
                })
                .collect(Collectors.toList());
    }


    /*
    @NotNull
    private List<EmployeeAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(List<BiDataPerDay> aggregates) {
        return aggregates
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()), e.getUser()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            BiDataPerDay example = list.getFirst();
                            int year = example.getYear();
                            int month = example.getMonth();
                            User user = example.getUser();
                            Company company = example.getCompany();
                            ConsultantType consultantType = example.getConsultantType();
                            StatusType status = example.getStatusType();

                            // statusType not in ('TERMINATED','PREBOARDING') or null
                            if(status == null || status.equals(StatusType.TERMINATED) || status.equals(StatusType.PREBOARDING) || status.equals(StatusType.NON_PAY_LEAVE)) {
                                return new EmployeeAvailabilityPerMonth(year, month, company, user.getUuid(), consultantType, StatusType.TERMINATED, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
                            }

                            double grossAvailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getGrossAvailableHours().doubleValue()).sum();
                            double unavavailableHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getUnavailableHours().doubleValue()).sum();
                            double vacationHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getVacationHours().doubleValue()).sum();
                            double sickHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getSickHours().doubleValue()).sum();
                            double maternityLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getMaternityLeaveHours().doubleValue()).sum();
                            double nonPaydLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getNonPaydLeaveHours().doubleValue()).sum();
                            double paidLeaveHours = list.stream().mapToDouble(employeeAvailabilityPerDayAggregate -> employeeAvailabilityPerDayAggregate.getPaidLeaveHours().doubleValue()).sum();
                            double salary = list.stream().mapToDouble(BiDataPerDay::getSalary).average().orElse(0.0);
                            boolean isTwBonusEligible = list.stream().allMatch(BiDataPerDay::isTwBonusEligible);


                            return new EmployeeAvailabilityPerMonth(year, month, company, user.getUuid(), consultantType, status, BigDecimal.valueOf(grossAvailableHours), BigDecimal.valueOf(unavavailableHours), BigDecimal.valueOf(vacationHours), BigDecimal.valueOf(sickHours), BigDecimal.valueOf(maternityLeaveHours), BigDecimal.valueOf(nonPaydLeaveHours), BigDecimal.valueOf(paidLeaveHours), BigDecimal.valueOf(salary), isTwBonusEligible);
                        })
                ))
                .values().stream().toList();
    }

     */

    public double calculateSalarySum(Company company, LocalDate date, List<EmployeeAvailabilityPerMonth> data) {
        AtomicReference<Double> sum = new AtomicReference<>(0.0);
        data.stream().filter(employeeDataPerMonth ->
                        employeeDataPerMonth.getYear() == date.getYear() &&
                                employeeDataPerMonth.getMonth() == date.getMonthValue() &&
                                employeeDataPerMonth.getCompany()!=null &&
                                employeeDataPerMonth.getCompany().getUuid().equals(company.getUuid()) &&
                                !employeeDataPerMonth.getStatus().equals(StatusType.TERMINATED) &&
                                !employeeDataPerMonth.getStatus().equals(StatusType.NON_PAY_LEAVE) &&
                                employeeDataPerMonth.getConsultantType().equals(ConsultantType.CONSULTANT))
                //.peek(em -> System.out.println("User = " + User.<User>findById(em.getUseruuid()).getFullname() + ": " + em.getAvgSalary()))
                .forEach(employeeDataPerMonth -> {
                    sum.updateAndGet(v -> v + employeeDataPerMonth.getAvgSalary().doubleValue());
                });
        return sum.get();
    }

    public Double calculateConsultantCount(Company company, LocalDate date, List<EmployeeAvailabilityPerMonth> data) {
        AtomicReference<Double> sum = new AtomicReference<>(0.0);
        data.stream().filter(employeeDataPerMonth ->
                employeeDataPerMonth.getYear() == date.getYear() &&
                        employeeDataPerMonth.getMonth() == date.getMonthValue() &&
                        employeeDataPerMonth.getCompany()!=null &&
                        employeeDataPerMonth.getCompany().getUuid().equals(company.getUuid()) &&
                        !employeeDataPerMonth.getStatus().equals(StatusType.TERMINATED) &&
                        !employeeDataPerMonth.getStatus().equals(StatusType.NON_PAY_LEAVE) &&
                        employeeDataPerMonth.getConsultantType().equals(ConsultantType.CONSULTANT)
        ).forEach(employeeDataPerMonth -> {
            sum.updateAndGet(v -> v + 1);
        });
        return sum.get();
    }

    public double calculateSalarySum(Company company, List<EmployeeAvailabilityPerMonth> data) {
        AtomicReference<Double> sum = new AtomicReference<>(0.0);
        data.stream().filter(employeeDataPerMonth ->
                employeeDataPerMonth.getCompany()!=null &&
                        employeeDataPerMonth.getCompany().getUuid().equals(company.getUuid()) &&
                        !employeeDataPerMonth.getStatus().equals(StatusType.TERMINATED) &&
                        !employeeDataPerMonth.getStatus().equals(StatusType.NON_PAY_LEAVE) &&
                        employeeDataPerMonth.getConsultantType().equals(ConsultantType.CONSULTANT)
        ).forEach(employeeDataPerMonth -> {
            sum.updateAndGet(v -> v + employeeDataPerMonth.getAvgSalary().doubleValue());
        });
        return sum.get();
    }

    public Double calculateConsultantCount(Company company, List<EmployeeAvailabilityPerMonth> data) {
        AtomicReference<Double> sum = new AtomicReference<>(0.0);
        data.stream().filter(employeeDataPerMonth ->
                employeeDataPerMonth.getCompany()!=null &&
                        employeeDataPerMonth.getCompany().getUuid().equals(company.getUuid()) &&
                        !employeeDataPerMonth.getStatus().equals(StatusType.TERMINATED) &&
                        !employeeDataPerMonth.getStatus().equals(StatusType.NON_PAY_LEAVE) &&
                        employeeDataPerMonth.getConsultantType().equals(ConsultantType.CONSULTANT)
        ).forEach(employeeDataPerMonth -> {
            sum.updateAndGet(v -> v + 1);
        });
        return sum.get();
    }
}
