package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.accounting.model.DanlonChanges;
import dk.trustworks.intranet.aggregates.accounting.model.DanlonEmployee;
import dk.trustworks.intranet.aggregates.accounting.model.DanlonSalarySupplements;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.users.services.*;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.*;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.NumberUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.userservice.model.enums.StatusType.NON_PAY_LEAVE;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.TERMINATED;
import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static dk.trustworks.intranet.utils.NumberUtils.formatCurrency;
import static dk.trustworks.intranet.utils.NumberUtils.formatDouble;

@Tag(name = "danlon")
@Path("/company/{companyuuid}/danlon")
@RequestScoped
@JBossLog
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class DanlonResource {

    @Inject
    UserService userService;

    @Inject
    SalaryService salaryService;

    @Inject
    SalarySupplementService salarySupplementService;

    @Inject
    SalaryLumpSumService salaryLumpSumService;

    @Inject
    WorkService workService;

    @Inject
    TransportationRegistrationService transportationRegistrationService;

    @Inject
    ExpenseService expenseService;

    @Inject
    AvailabilityService availabilityService;

    @PathParam("companyuuid")
    private String companyuuid;

    @GET
    @Path("/employees/changed")
    public List<DanlonChanges> findChangedUsers(@QueryParam("month") String strMonth) {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);
        List<DanlonChanges> result = new ArrayList<>();

        // Look for changes to a user
        List<User> userList = userService.listAll(false).stream()
                .filter(user -> // If user is terminated before this month, ignore
                        !(user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED) &&
                        user.getUserStatus(endOfMonth).getStatusdate().isBefore(endOfMonth)))
                .filter(user -> // If user is not in the company, ignore
                        user.getUserStatus(endOfMonth).getCompany() != null &&
                        user.getUserStatus(endOfMonth).getCompany().getUuid().equals(companyuuid))
                .filter(user -> // If user is not a consultant or staff, ignore
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.CONSULTANT) ||
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STAFF) ||
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STUDENT))
                .filter(user -> // If user is not hired this month, ignore
                        !user.getHireDate().withDayOfMonth(1).isEqual(endOfMonth.withDayOfMonth(1)))
                .toList();


        for (User user : userList) {
            // Check for new employee
            //if(user.getHireDate().withDayOfMonth(1).isEqual(month)) continue; // This is not a changed user, see other endpoint
            //System.out.println("user.getUsername() = " + user.getUsername());

            String danlonNumber = user.getUserAccount()!=null?user.getUserAccount().getDanlon():"";
            String name = user.getFullname();
            StringBuilder salaryNotes = new StringBuilder("Normal løn. ");
            List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDay = availabilityService.getEmployeeDataPerDay(user.uuid, month, month.plusMonths(1));

            // Check for salary changes
            Salary baseSalary = salaryService.getUserSalaryByMonth(user.getUuid(), month);
            if(salaryService.isSalaryChanged(user.getUuid(), month)) { // This will update the salary if it has changed (and return true if it has changed)
                salaryNotes = new StringBuilder("Ny løn: ").append(formatCurrency(baseSalary.getSalary())+ " ");
            }

            String statusType = "";

            // Check for userstatus changes
            boolean isTerminated = isUserTerminatedInCurrentMonth(user, endOfMonth, month);
            if(isTerminated) {
                salaryNotes = new StringBuilder("Ingen løn. ");
                statusType = TERMINATED.getDanlonState();
            }

            boolean hasAnyTypeOfLeave = hasAnyTypeOfLeave(employeeDataPerDay);
            if(hasAnyTypeOfLeave) salaryNotes.append("Ingen frokostordning. ");

            boolean hasOnlyNonPayLeave = hasOnlyNonPayLeave(employeeDataPerDay);
            if(hasOnlyNonPayLeave) {
                salaryNotes = new StringBuilder("Ingen løn. ");
                statusType = NON_PAY_LEAVE.getDanlonState();
            }

            boolean hasUserJustReturnedFromNonPayLeave = hasUserJustReturnedFromNonPayLeave(user, month, employeeDataPerDay);
            if(hasUserJustReturnedFromNonPayLeave) {
                statusType = StatusType.ACTIVE.getDanlonState();
            }

            boolean anyNonPayDays = anyNonPayDays(employeeDataPerDay);
            if(anyNonPayDays && !hasOnlyNonPayLeave) {
                EmployeeAvailabilityPerMonth availability = availabilityService.getEmployeeDataPerMonth(user.uuid, month, month.plusMonths(1)).getFirst();
                salaryNotes.append("Norm: 160.33 timer, ");
                salaryNotes.append("Fakt: "+ NumberUtils.round(baseSalary.calculateActualWorkHours(availability.getGrossAvailableHours().doubleValue()/7.4, availability.getSalaryAwardingHours()/7.4), 2)+" timer, ");
                salaryNotes.append("Justeret løn: " + formatCurrency(baseSalary.calculateMonthNormAdjustedSalary(availability.getGrossAvailableHours().doubleValue()/7.4, availability.getSalaryAwardingHours()/7.4)));
                salaryNotes.append("). ");
            }


            result.add(new DanlonChanges(danlonNumber, name, statusType, salaryNotes.toString()));
        }
        return result;
    }

    @GET
    @Path("/employees/new")
    public List<DanlonEmployee> createDanlonEmployeeList(@QueryParam("month") String strMonth) {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);

        List<User> userList = userService.listAll(false).stream()
                .filter(user ->
                        user.getUserStatus(endOfMonth).getCompany() != null &&
                                user.getUserStatus(endOfMonth).getCompany().getUuid().equals(companyuuid))
                .filter(user ->
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.CONSULTANT) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STAFF) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STUDENT))
                .filter(user ->
                        user.getHireDate().withDayOfMonth(1).isEqual(endOfMonth.withDayOfMonth(1)) ||
                                isUserTerminatedInCurrentMonth(user, endOfMonth, month) ||
                                user.getUserBankInfos().stream().anyMatch(userBankInfo -> userBankInfo.getActiveDate().withDayOfMonth(1).isEqual(month)) ||
                                hasUserJustReturnedFromNonPayLeave(user, month, availabilityService.getEmployeeDataPerDay(user.uuid, month, month.plusMonths(1))))
                .toList();

        return userList.stream().map(user -> new DanlonEmployee(
                user.getUserAccount()!=null?user.getUserAccount().getDanlon():"",
                user.getFirstname() + " " + user.getLastname(),
                user.getUserContactinfo()!=null?user.getUserContactinfo().getStreetname():"",
                "",
                user.getUserContactinfo()!=null?user.getUserContactinfo().getPostalcode():"",
                "",
                "DK",
                user.getEmail(),
                "",
                user.getPhone(),
                user.getCpr(),
                stringIt(user.getHireDate(), "dd-MM-yyyy"),
                "",
                user.getUserBankInfo(endOfMonth).getRegnr()+"-"+user.getUserBankInfo(endOfMonth).getAccountNr(),
                "",
                !user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED) ? "x" : ""
        )).toList();
    }

    @GET
    @Path("/employees/salarysupplements")
    @Transactional
    public List<DanlonSalarySupplements> createSalarySupplements(@QueryParam("month") String strMonth, @QueryParam("test") String test) {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);
        Company company = Company.findById(companyuuid);
        boolean isTest = test != null && test.equals("true");

        List<DanlonSalarySupplements> result = new ArrayList<>();

        List<User> userList = userService.listAll(false).stream()
                .filter(user ->
                        !user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED))
                .filter(user ->
                        user.getUserStatus(endOfMonth).getCompany() != null &&
                                user.getUserStatus(endOfMonth).getCompany().getUuid().equals(companyuuid))
                .filter(user ->
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.CONSULTANT) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STAFF) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STUDENT))
                .toList();

        for (User user : userList) {
            if(user.getUserAccount()==null || user.getUserAccount().getDanlon()==null) continue;
            double bonus = 0;

            bonus += salarySupplementService.findByUseruuidAndMonth(user.getUuid(), month).stream().mapToDouble(SalarySupplement::getValue).sum();
            bonus += salaryLumpSumService.findByUseruuidAndMonth(user.getUuid(), month).stream().mapToDouble(SalaryLumpSum::getLumpSum).sum();

            if(bonus>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "41", "Bonus", "", "", formatDouble(bonus)));

            double vacation = workService.calculateVacationByUserInMonth(user.getUuid(), DateUtils.getTwentieth(month), DateUtils.getTwentieth(month.plusMonths(1)));
            if(vacation > 0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "60", "Vacation", formatDouble(vacation/7.4), "", ""));

            List<TransportationRegistration> unPaidRegistrations = transportationRegistrationService.findByUseruuidAndUnpaid(user.getUuid());
            int kilometers = unPaidRegistrations.stream().mapToInt(TransportationRegistration::getKilometers).sum();
            if(!isTest) {
                unPaidRegistrations.forEach(transportationRegistration -> {
                    transportationRegistration.setPaid(true);
                    transportationRegistration.persist();
                });
            }
            if(kilometers>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "39", "Transportation", kilometers+"", "", ""));

            List<Expense> unPaidExpenses = expenseService.findByUserAndUnpaid(user.getUuid());
            double expenseSum = unPaidExpenses.stream().mapToDouble(Expense::getAmount).sum();
            if(!isTest) {
                unPaidExpenses.forEach(expense -> {
                    expense.setPaid(true);
                    expense.persist();
                });
            }
            if(expenseSum>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "40", "Expenses", "", "", formatCurrency(expenseSum)));
        }

        return result;
    }

    private static boolean anyNonPayDays(List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) ||
                        e.getStatusType().equals(TERMINATED));
    }

    private static boolean hasUserJustReturnedFromNonPayLeave(User user, LocalDate month, List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDay) {
        return
                (
                        user.getUserStatus(month.minusDays(1)).getStatus().equals(NON_PAY_LEAVE) &&
                                user.getUserStatus(month).getStatus().equals(StatusType.ACTIVE)
                ) || (user.getUserStatus(month).getStatus().equals(NON_PAY_LEAVE) && !hasOnlyNonPayLeave(employeeDataPerDay));
    }

    private static boolean hasOnlyNonPayLeave(List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDay) {
        return employeeDataPerDay.stream().allMatch(e -> e.getStatusType().equals(NON_PAY_LEAVE));
    }

    private static boolean hasAnyTypeOfLeave(List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) ||
                        e.getStatusType().equals(StatusType.PAID_LEAVE) ||
                        e.getStatusType().equals(StatusType.MATERNITY_LEAVE));
    }

    private static boolean isUserTerminatedInCurrentMonth(User user, LocalDate endOfMonth, LocalDate month) {
        UserStatus status = user.getUserStatus(endOfMonth);
        return status.getStatus().equals(TERMINATED) && status.getStatusdate().withDayOfMonth(1).isEqual(month);
    }

}
