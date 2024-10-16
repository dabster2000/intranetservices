package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.model.CompanyAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.User;
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
                BiDataPerDay.<BiDataPerDay>stream("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and (consultantType = 'CONSULTANT' or consultantType = 'STUDENT') and statusType not in ('TERMINATED','PREBOARDING') ", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue())
                        /*
                        .map(biDataPerDay -> {
                            EmployeeAvailabilityPerDayAggregate employeeAvailabilityPerDayAggregate = new EmployeeAvailabilityPerDayAggregate();
                            employeeAvailabilityPerDayAggregate.setCompany(biDataPerDay.getCompany());
                            employeeAvailabilityPerDayAggregate.setDocumentDate(biDataPerDay.getDocumentDate());
                            employeeAvailabilityPerDayAggregate.setYear(biDataPerDay.getYear());
                            employeeAvailabilityPerDayAggregate.setMonth(biDataPerDay.getMonth());
                            employeeAvailabilityPerDayAggregate.setDay(biDataPerDay.getDay());
                            employeeAvailabilityPerDayAggregate.setUser(biDataPerDay.getUser());
                            employeeAvailabilityPerDayAggregate.setGrossAvailableHours(biDataPerDay.getGrossAvailableHours());
                            employeeAvailabilityPerDayAggregate.setUnavavailableHours(biDataPerDay.getUnavailableHours());
                            employeeAvailabilityPerDayAggregate.setVacationHours(biDataPerDay.getVacationHours());
                            employeeAvailabilityPerDayAggregate.setSickHours(biDataPerDay.getSickHours());
                            employeeAvailabilityPerDayAggregate.setMaternityLeaveHours(biDataPerDay.getMaternityLeaveHours());
                            employeeAvailabilityPerDayAggregate.setNonPaydLeaveHours(biDataPerDay.getNonPaydLeaveHours());
                            employeeAvailabilityPerDayAggregate.setPaidLeaveHours(biDataPerDay.getPaidLeaveHours());
                            employeeAvailabilityPerDayAggregate.setSalary(biDataPerDay.getSalary());
                            employeeAvailabilityPerDayAggregate.setConsultantType(
                                    Optional.ofNullable(biDataPerDay.getConsultantType())
                                            .map(ConsultantType::valueOf)
                                            .orElse(ConsultantType.CONSULTANT) // or provide a default value here if needed
                            );
                            employeeAvailabilityPerDayAggregate.setStatusType(
                                    Optional.ofNullable(biDataPerDay.getStatusType())
                                            .map(StatusType::valueOf)
                                            .orElse(StatusType.TERMINATED) // or provide a default value here if needed
                            );
                            employeeAvailabilityPerDayAggregate.setTwBonusEligible(biDataPerDay.isTwBonusEligible());
                            return employeeAvailabilityPerDayAggregate;
                        })
                         */
                        .toList()
                //EmployeeAvailabilityPerDayAggregate.list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and (consultantType = 'CONSULTANT' or consultantType = 'STUDENT') and statusType not in ('TERMINATED','PREBOARDING') ", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue())
        );
    }

    @CacheResult(cacheName = "employee-availability")
    public List<EmployeeAvailabilityPerMonth> getCompanyEmployeeAvailabilityByPeriod(Company company, LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(
                BiDataPerDay.<BiDataPerDay>stream("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5 and consultantType IN ('CONSULTANT','STAFF','STUDENT') and statusType not in ('TERMINATED','PREBOARDING') ", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue(), company)
                        /*
                        .map(biDataPerDay -> {
                            EmployeeAvailabilityPerDayAggregate employeeAvailabilityPerDayAggregate = new EmployeeAvailabilityPerDayAggregate();
                            employeeAvailabilityPerDayAggregate.setCompany(biDataPerDay.getCompany());
                            employeeAvailabilityPerDayAggregate.setDocumentDate(biDataPerDay.getDocumentDate());
                            employeeAvailabilityPerDayAggregate.setYear(biDataPerDay.getYear());
                            employeeAvailabilityPerDayAggregate.setMonth(biDataPerDay.getMonth());
                            employeeAvailabilityPerDayAggregate.setDay(biDataPerDay.getDay());
                            employeeAvailabilityPerDayAggregate.setUser(biDataPerDay.getUser());
                            employeeAvailabilityPerDayAggregate.setGrossAvailableHours(biDataPerDay.getGrossAvailableHours());
                            employeeAvailabilityPerDayAggregate.setUnavavailableHours(biDataPerDay.getUnavailableHours());
                            employeeAvailabilityPerDayAggregate.setVacationHours(biDataPerDay.getVacationHours());
                            employeeAvailabilityPerDayAggregate.setSickHours(biDataPerDay.getSickHours());
                            employeeAvailabilityPerDayAggregate.setMaternityLeaveHours(biDataPerDay.getMaternityLeaveHours());
                            employeeAvailabilityPerDayAggregate.setNonPaydLeaveHours(biDataPerDay.getNonPaydLeaveHours());
                            employeeAvailabilityPerDayAggregate.setPaidLeaveHours(biDataPerDay.getPaidLeaveHours());
                            employeeAvailabilityPerDayAggregate.setSalary(biDataPerDay.getSalary());
                            employeeAvailabilityPerDayAggregate.setConsultantType(
                                    Optional.ofNullable(biDataPerDay.getConsultantType())
                                            .map(ConsultantType::valueOf)
                                            .orElse(ConsultantType.CONSULTANT) // or provide a default value here if needed
                            );
                            employeeAvailabilityPerDayAggregate.setStatusType(
                                    Optional.ofNullable(biDataPerDay.getStatusType())
                                            .map(StatusType::valueOf)
                                            .orElse(StatusType.TERMINATED) // or provide a default value here if needed
                            );
                            employeeAvailabilityPerDayAggregate.setTwBonusEligible(biDataPerDay.isTwBonusEligible());
                            return employeeAvailabilityPerDayAggregate;
                        })

                         */
                        .toList()
                //EmployeeAvailabilityPerDayAggregate.list("STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') >= STR_TO_DATE(CONCAT(?1, '-', ?2, '-01'), '%Y-%m-%d') AND STR_TO_DATE(CONCAT(year, '-', month, '-01'), '%Y-%m-%d') < STR_TO_DATE(CONCAT(?3, '-', ?4, '-01'), '%Y-%m-%d') and company = ?5 and consultantType IN ('CONSULTANT','STAFF','STUDENT') and statusType not in ('TERMINATED','PREBOARDING') ", fromdate.getYear(), fromdate.getMonthValue(), todate.getYear(), todate.getMonthValue(), company)
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
                // Map to EmployeeAvailabilityPerDayAggregate
                /*
                .map(biDataPerDay -> {
                    EmployeeAvailabilityPerDayAggregate employeeAvailabilityPerDayAggregate = new EmployeeAvailabilityPerDayAggregate();
                    employeeAvailabilityPerDayAggregate.setCompany(biDataPerDay.getCompany());
                    employeeAvailabilityPerDayAggregate.setDocumentDate(biDataPerDay.getDocumentDate());
                    employeeAvailabilityPerDayAggregate.setYear(biDataPerDay.getYear());
                    employeeAvailabilityPerDayAggregate.setMonth(biDataPerDay.getMonth());
                    employeeAvailabilityPerDayAggregate.setDay(biDataPerDay.getDay());
                    employeeAvailabilityPerDayAggregate.setUser(biDataPerDay.getUser());
                    employeeAvailabilityPerDayAggregate.setGrossAvailableHours(biDataPerDay.getGrossAvailableHours());
                    employeeAvailabilityPerDayAggregate.setUnavavailableHours(biDataPerDay.getUnavailableHours());
                    employeeAvailabilityPerDayAggregate.setVacationHours(biDataPerDay.getVacationHours());
                    employeeAvailabilityPerDayAggregate.setSickHours(biDataPerDay.getSickHours());
                    employeeAvailabilityPerDayAggregate.setMaternityLeaveHours(biDataPerDay.getMaternityLeaveHours());
                    employeeAvailabilityPerDayAggregate.setNonPaydLeaveHours(biDataPerDay.getNonPaydLeaveHours());
                    employeeAvailabilityPerDayAggregate.setPaidLeaveHours(biDataPerDay.getPaidLeaveHours());
                    employeeAvailabilityPerDayAggregate.setSalary(biDataPerDay.getSalary());
                    employeeAvailabilityPerDayAggregate.setConsultantType(
                            Optional.ofNullable(biDataPerDay.getConsultantType())
                                    .map(ConsultantType::valueOf)
                                    .orElse(ConsultantType.CONSULTANT) // or provide a default value here if needed
                    );
                    employeeAvailabilityPerDayAggregate.setStatusType(
                            Optional.ofNullable(biDataPerDay.getStatusType())
                                    .map(StatusType::valueOf)
                                    .orElse(StatusType.TERMINATED) // or provide a default value here if needed
                    );
                    employeeAvailabilityPerDayAggregate.setTwBonusEligible(biDataPerDay.isTwBonusEligible());
                    return employeeAvailabilityPerDayAggregate;
                })*/
                .toList();


        //return EmployeeAvailabilityPerDayAggregate.<EmployeeAvailabilityPerDayAggregate>stream("documentDate >= ?1 AND documentDate < ?2 AND user = ?3", fromDate, toDate, User.findById(useruuid)).sorted(Comparator.comparing(EmployeeAvailabilityPerDayAggregate::getDocumentDate)).toList();
    }

    public double getSumOfAvailableHoursByUsersAndMonth(LocalDate localDate, String... uuids) {
        return ((Number) em.createNativeQuery("select greatest(0.0, sum(e.gross_available_hours - e.paid_leave_hours - e.non_payd_leave_hours - e.maternity_leave_hours - e.sick_hours - e.vacation_hours - e.unavailable_hours)) as value " +
                //"from bi_availability_per_day e " +
                "from bi_data_per_day e " +
                "where e.status_type = 'ACTIVE' and e.consultant_type = 'CONSULTANT' and e.useruuid in ('" + String.join("','", uuids) + "') " +
                "     AND e.year = " + localDate.getYear() + " " +
                "     AND e.month = " + localDate.getMonthValue() + "; ").getResultList().stream().filter(Objects::nonNull).findAny().orElse(0.0)).doubleValue();
    }

    public List<EmployeeAvailabilityPerMonth> getEmployeeDataPerMonth(String useruuid, LocalDate fromdate, LocalDate todate) {
        return getEmployeeAvailabilityPerMonths(getEmployeeDataPerDay(useruuid, fromdate, todate));
    }

    @NotNull
    private ArrayList<CompanyAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(Company company, LocalDate startDate, LocalDate endDate, String sql) {
        //return new ArrayList<>(EmployeeAvailabilityPerDayAggregate.<EmployeeAvailabilityPerDayAggregate>list(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), company)
        return new ArrayList<>(BiDataPerDay.<BiDataPerDay>stream(sql, startDate.getYear(), startDate.getMonthValue(), endDate.getYear(), endDate.getMonthValue(), company)
                /*
                .map(biDataPerDay -> {
                    EmployeeAvailabilityPerDayAggregate employeeAvailabilityPerDayAggregate = new EmployeeAvailabilityPerDayAggregate();
                    employeeAvailabilityPerDayAggregate.setCompany(biDataPerDay.getCompany());
                    employeeAvailabilityPerDayAggregate.setDocumentDate(biDataPerDay.getDocumentDate());
                    employeeAvailabilityPerDayAggregate.setYear(biDataPerDay.getYear());
                    employeeAvailabilityPerDayAggregate.setMonth(biDataPerDay.getMonth());
                    employeeAvailabilityPerDayAggregate.setDay(biDataPerDay.getDay());
                    employeeAvailabilityPerDayAggregate.setUser(biDataPerDay.getUser());
                    employeeAvailabilityPerDayAggregate.setGrossAvailableHours(biDataPerDay.getGrossAvailableHours());
                    employeeAvailabilityPerDayAggregate.setUnavavailableHours(biDataPerDay.getUnavailableHours());
                    employeeAvailabilityPerDayAggregate.setVacationHours(biDataPerDay.getVacationHours());
                    employeeAvailabilityPerDayAggregate.setSickHours(biDataPerDay.getSickHours());
                    employeeAvailabilityPerDayAggregate.setMaternityLeaveHours(biDataPerDay.getMaternityLeaveHours());
                    employeeAvailabilityPerDayAggregate.setNonPaydLeaveHours(biDataPerDay.getNonPaydLeaveHours());
                    employeeAvailabilityPerDayAggregate.setPaidLeaveHours(biDataPerDay.getPaidLeaveHours());
                    employeeAvailabilityPerDayAggregate.setSalary(biDataPerDay.getSalary());
                    employeeAvailabilityPerDayAggregate.setConsultantType(
                            Optional.ofNullable(biDataPerDay.getConsultantType())
                                    .map(ConsultantType::valueOf)
                                    .orElse(ConsultantType.CONSULTANT) // or provide a default value here if needed
                    );
                    employeeAvailabilityPerDayAggregate.setStatusType(
                            Optional.ofNullable(biDataPerDay.getStatusType())
                                    .map(StatusType::valueOf)
                                    .orElse(StatusType.TERMINATED) // or provide a default value here if needed
                    );
                    employeeAvailabilityPerDayAggregate.setTwBonusEligible(biDataPerDay.isTwBonusEligible());
                    return employeeAvailabilityPerDayAggregate;
                })
                 */
                //.stream()
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

    @NotNull
    private List<EmployeeAvailabilityPerMonth> getEmployeeAvailabilityPerMonths(List<BiDataPerDay> aggregates) {
        return aggregates
                .stream()
                .collect(Collectors.groupingBy(
                        e -> new AbstractMap.SimpleEntry<>(new AbstractMap.SimpleEntry<>(e.getYear(), e.getMonth()), e.getUser()),
                        Collectors.collectingAndThen(Collectors.toList(), list -> {
                            // In this block, we process each group to create a CompanyBudgetPerMonth object

                            // Assuming year, month, client, company, and contract are the same for all entries in the group
                            BiDataPerDay example = list.get(0);
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
