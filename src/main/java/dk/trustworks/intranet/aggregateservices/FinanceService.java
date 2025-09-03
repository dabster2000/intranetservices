package dk.trustworks.intranet.aggregateservices;

import dk.trustworks.intranet.dto.AvailabilityDocument;
import dk.trustworks.intranet.dto.FinanceDocument;
import dk.trustworks.intranet.dto.UserFinanceDocument;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.userservice.services.TeamService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class FinanceService {

    @Inject
    dk.trustworks.intranet.financeservice.services.FinanceService financeAPI;

    @Inject
    UserService userService;

    @Inject
    SalaryService salaryService;

    @Inject
    TeamService teamService;

    @Inject
    AvailabilityService availabilityService;

    private List<UserFinanceDocument> cachedUserFinanceData = new ArrayList<>();

    public List<UserFinanceDocument> getUserFinanceData() {
        if(cachedUserFinanceData.isEmpty()) cachedUserFinanceData = createUserFinanceDataV2();
        return cachedUserFinanceData;
    }

    private List<FinanceDocument> cachedFinanceData = new ArrayList<>();

    public List<FinanceDocument> getFinanceData() {
        if(cachedFinanceData.isEmpty()) cachedFinanceData = createFinanceData();
        if(cachedUserFinanceData.isEmpty()) cachedUserFinanceData = createUserFinanceDataV2();
        return cachedFinanceData;
    }

    private List<UserFinanceDocument> createUserFinanceDataV2() {
        log.info("FinanceService.createUserFinanceDataV2");
        List<UserFinanceDocument> userFinanceDocumentList = new ArrayList<>();
        LocalDate startDate = LocalDate.of(2014, 7, 1);

        do {
            LocalDate finalStartDate = startDate.withDayOfMonth(1);
            userFinanceDocumentList.addAll(getFinanceDataForSingleMonth(finalStartDate));
            startDate = startDate.plusMonths(1);
        } while (startDate.isBefore(LocalDate.now().withDayOfMonth(1)));

        return userFinanceDocumentList;
    }

    /**
     *
     * @param finalStartDate
     * @return Non cached list
     */
    public List<UserFinanceDocument> getFinanceDataForSingleMonth(LocalDate finalStartDate) {
        List<UserFinanceDocument> userFinanceDocumentList = new ArrayList<>();
        List<User> owners = teamService.getOwnersByMonth(finalStartDate);
        List<User> teamOps = teamService.getTeamOpsByMonth(finalStartDate);
        List<User> consultants = teamService.getTeammembersByTeamleadBonusEnabledByMonth(finalStartDate);

        List<User> allEmployees = userService.findEmployedUsersByDate(finalStartDate, true, ConsultantType.CONSULTANT);

        final double totalExpenses = financeAPI.findByMonth(finalStartDate.withDayOfMonth(1)).stream().mapToDouble(Finance::getAmount).sum();

        double sumConsultantsSalary = 0.0;
        double consultantsCount = 0.0;

        double sumOwnerSalary = 0.0;
        double sumTeamOpsSalary = 0.0;

        for (User employee : allEmployees) {
            if(consultants.stream().anyMatch(consultant -> consultant.getUuid().equals(employee.getUuid()))) {
                consultantsCount++;
                sumConsultantsSalary += (salaryService.getUserSalaryByMonth(employee.getUuid(), finalStartDate).getSalary() * 1.02);
            }
            if(owners.stream().anyMatch(owner -> owner.getUuid().equals(employee.getUuid()))) {
                sumOwnerSalary += salaryService.getUserSalaryByMonth(employee.getUuid(), finalStartDate).getSalary();
            }
            if(teamOps.stream().anyMatch(o -> o.getUuid().equals(employee.getUuid()))) {
                sumTeamOpsSalary += salaryService.getUserSalaryByMonth(employee.getUuid(), finalStartDate).getSalary();
            }
        }
        double sumOtherExpenses = totalExpenses - sumConsultantsSalary - sumTeamOpsSalary - sumOwnerSalary;
        if(sumOtherExpenses < 0) {
            sumOtherExpenses = 0;
        }

        double avgOtherExpenses = (sumOtherExpenses / consultantsCount);

        for (User employee : allEmployees) {
            if(consultants.stream().anyMatch(consultant -> consultant.getUuid().equals(employee.getUuid()))) {
                AvailabilityDocument availability = availabilityService.getConsultantAvailabilityByMonth(employee.getUuid(), finalStartDate);
                if (availability == null || availability.getGrossAvailableHours() <= 0.0) continue;

                double salary = salaryService.getUserSalaryByMonth(employee.getUuid(), finalStartDate).getSalary() * 1.02;
                UserFinanceDocument userFinanceDocument = new UserFinanceDocument(finalStartDate, employee, avgOtherExpenses, salary, 0, 0);
                userFinanceDocumentList.add(userFinanceDocument);
            }
        }
        return userFinanceDocumentList;
    }

    private List<UserFinanceDocument> createUserFinanceData() {
        List<UserFinanceDocument> userFinanceDocumentList = new ArrayList<>();
        LocalDate startDate = LocalDate.of(2014, 7, 1);
        do {
            LocalDate finalStartDate = startDate;
            int consultantNetSalaries = userService.calcMonthSalaries(finalStartDate, ConsultantType.CONSULTANT.toString());
            int staffNetSalaries = userService.calcMonthSalaries(finalStartDate, ConsultantType.STAFF.toString());
            final List<Finance> expenseList = financeAPI.findByMonth(finalStartDate.withDayOfMonth(1));
            final double expenseSalaries = expenseList.stream()
                    .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.LØNNINGER))
                    .mapToDouble(Finance::getAmount)
                    .sum();

            final long consultantCount = availabilityService.countUsersByMonthAndStatusTypeAndConsultantType(finalStartDate, StatusType.ACTIVE, ConsultantType.CONSULTANT);

            double totalSalaries = consultantNetSalaries + staffNetSalaries;

            double forholdstal = expenseSalaries / totalSalaries;

            final double staffSalaries = (staffNetSalaries * forholdstal) / consultantCount;//(expenseSalaries - consultantSalaries) / consultantCount;

            final double consultantSalaries = (consultantNetSalaries * forholdstal) / consultantCount;

            final double sharedExpense = expenseList.stream().filter(expense1 -> !expense1.getExpensetype().equals(ExcelFinanceType.LØNNINGER) && !expense1.getExpensetype().equals(ExcelFinanceType.PERSONALE)).mapToDouble(Finance::getAmount).sum() / consultantCount + consultantSalaries;

            final double personaleExpenses = expenseList.stream().filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.PERSONALE)).mapToDouble(Finance::getAmount).sum() / consultantCount;

            if(expenseSalaries <= 0) {
                startDate = startDate.plusMonths(1);
                continue;
            }

            for (User user : userService.listAll(true)) {
                UserStatus userStatus = userService.getUserStatus(user, finalStartDate);
                if(userStatus.getType().equals(ConsultantType.CONSULTANT) && userStatus.getStatus().equals(StatusType.ACTIVE)) {
                    AvailabilityDocument availability = availabilityService.getConsultantAvailabilityByMonth(user.getUuid(), finalStartDate);
                    if (availability == null || availability.getGrossAvailableHours() <= 0.0) continue;
                    int salary = salaryService.getUserSalaryByMonth(user.getUuid(), finalStartDate).getSalary();
                    UserFinanceDocument userFinanceDocument = new UserFinanceDocument(finalStartDate, user, sharedExpense, salary, staffSalaries, personaleExpenses);
                    userFinanceDocumentList.add(userFinanceDocument);
                }
            }
            startDate = startDate.plusMonths(1);
        } while (startDate.isBefore(LocalDate.now().withDayOfMonth(1)));

        return userFinanceDocumentList;
    }

    private List<FinanceDocument> createFinanceData() {
        List<FinanceDocument> financeDocumentList = new ArrayList<>();
        LocalDate startDate = LocalDate.of(2014, 7, 1);
        do {
            LocalDate finalStartDate = startDate;
            FinanceDocument financeDocument = getFinanceDocument(finalStartDate);
            financeDocumentList.add(financeDocument);

            startDate = startDate.plusMonths(1);
        } while (startDate.isBefore(LocalDate.now().plusMonths(1).withDayOfMonth(1)));

        return financeDocumentList;
    }

    public FinanceDocument getFinanceDocument(LocalDate lookupDate) {
        final List<Finance> companyExpenseList = financeAPI.findByMonth(lookupDate.withDayOfMonth(1));
        final double expenseSalaries = companyExpenseList.stream()
                .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.LØNNINGER))
                .mapToDouble(Finance::getAmount)
                .sum();
        final double expensePersonale = companyExpenseList.stream()
                .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.PERSONALE))
                .mapToDouble(Finance::getAmount)
                .sum();
        final double expenseAdministration = companyExpenseList.stream()
                .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.ADMINISTRATION))
                .mapToDouble(Finance::getAmount)
                .sum();
        final double expenseLokale = companyExpenseList.stream()
                .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.LOKALE))
                .mapToDouble(Finance::getAmount)
                .sum();
        final double expenseProduktion = companyExpenseList.stream()
                .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.PRODUKTION))
                .mapToDouble(Finance::getAmount)
                .sum();
        final double expenseSalg = companyExpenseList.stream()
                .filter(expense1 -> expense1.getExpensetype().equals(ExcelFinanceType.SALG))
                .mapToDouble(Finance::getAmount)
                .sum();

        return new FinanceDocument(lookupDate, expenseSalaries, expensePersonale, expenseLokale, expenseSalg, expenseAdministration, expenseProduktion);
    }

    public double calcAllUserExpensesByMonth(LocalDate month) {
        List<UserFinanceDocument> userExpenseData = getUserFinanceData();
        return userExpenseData.stream()
                .filter(expenseDocument -> expenseDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(UserFinanceDocument::getExpenseSum).sum();
    }

    public List<FinanceDocument> getAllExpensesByMonth(LocalDate month) {
        List<FinanceDocument> expenseData = getFinanceData();
        return expenseData.stream()
                .filter(expenseDocument -> expenseDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .collect(Collectors.toList());
    }

    public List<FinanceDocument> getAllExpensesByPeriod(LocalDate fromdate, LocalDate todate) {
        List<FinanceDocument> expenseData = getFinanceData();
        return expenseData.stream()
                .filter(expenseDocument -> DateUtils.isBetweenBothIncluded(expenseDocument.getMonth(), fromdate, todate))
                .collect(Collectors.toList());
    }

    public double getSumOfExpensesForSingleMonth(LocalDate month) {
        List<FinanceDocument> expenseData = getFinanceData();
        return expenseData.stream()
                .filter(expenseDocument -> expenseDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(FinanceDocument::sum).sum();
    }

    public double getSharedExpensesAndStaffSalariesByMonth(LocalDate month) {
        List<UserFinanceDocument> expenseData = getUserFinanceData();
        return expenseData.stream()
                .filter(expenseDocument -> expenseDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .mapToDouble(expenseDocument1 -> (expenseDocument1.getSharedExpense()+expenseDocument1.getStaffSalaries())).sum();
    }

    public UserFinanceDocument getConsultantExpensesByMonth(User user, LocalDate month) {
        List<UserFinanceDocument> expenceData = getUserFinanceData();
        return expenceData.stream()
                .filter(
                        expenseDocument -> expenseDocument.getUser().getUuid().equals(user.getUuid())
                                && expenseDocument.getMonth().isEqual(month.withDayOfMonth(1)))
                .findAny().orElse(new UserFinanceDocument(month, user, 0.0, 0.0, 0.0, 0.0));
    }

    public List<UserFinanceDocument> getConsultantsExpensesByMonth(LocalDate month) {
        List<UserFinanceDocument> expenceData = getUserFinanceData();
        return expenceData.stream()
                .filter(expenseDocument -> expenseDocument.getMonth().isEqual(month.withDayOfMonth(1))).collect(Collectors.toList());
    }

    public List<Finance> findByAccountAndPeriod(ExcelFinanceType excelFinanceType, LocalDate periodStart, LocalDate periodEnd) {
        return financeAPI.findByAccountAndPeriod(excelFinanceType.toString(), periodStart, periodEnd);
    }
}
