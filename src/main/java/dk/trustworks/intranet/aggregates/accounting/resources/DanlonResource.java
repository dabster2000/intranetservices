package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.accounting.model.DanlonChanges;
import dk.trustworks.intranet.aggregates.accounting.model.DanlonEmployee;
import dk.trustworks.intranet.aggregates.accounting.model.DanlonSalarySupplements;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerDayAggregate;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.users.services.*;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.*;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.NumberUtils;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.SystemException;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.WORK_HOURS;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;
import static dk.trustworks.intranet.utils.DateUtils.*;
import static dk.trustworks.intranet.utils.NumberUtils.formatCurrency;
import static dk.trustworks.intranet.utils.NumberUtils.formatDouble;
import static jakarta.transaction.Transactional.TxType.REQUIRED;

@Tag(name = "danlon")
@Path("/company/{companyuuid}/danlon")
@RequestScoped
@JBossLog
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class DanlonResource {

    @Inject
    TransactionManager tm;
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

        LocalDate previousMonth = month.minusMonths(1).withDayOfMonth(1);
        LocalDate previousEndOfMonth = month.withDayOfMonth(1).minusDays(1);

        LocalDate nextMonth = month.plusMonths(1).withDayOfMonth(1);
        LocalDate nextEndOfMonth = nextMonth.plusMonths(1).minusDays(1);

        List<DanlonChanges> result = new ArrayList<>();

        // Look for changes to a user
        List<User> userList = getActiveUsersExcludingPreboardingAndTerminated(endOfMonth);


        for (User user : userList) {
            String danlonNumber = user.getUserAccount()!=null?user.getUserAccount().getDanlon():"";
            String name = user.getFullname();
            StringBuilder salaryNotes = new StringBuilder("Normal løn. ");
            List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDayPreviousMonth = availabilityService.getEmployeeDataPerDay(user.uuid, previousMonth, previousMonth.plusMonths(1));
            List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDayThisMonth = availabilityService.getEmployeeDataPerDay(user.uuid, month, month.plusMonths(1));
            //List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDayNextMonth = availabilityService.getEmployeeDataPerDay(user.uuid, nextMonth, nextMonth.plusMonths(1));

            Salary baseSalary = salaryService.getUserSalaryByMonth(user.getUuid(), endOfMonth);

            if(user.getHireDate().withDayOfMonth(1).isEqual(month)) {
                salaryNotes.append("Ny medarbejder: ").append(baseSalary.getSalary());
            }

            // Check for salary changes
            if(salaryService.isSalaryChanged(user.getUuid(), month)) { // If salary has changed
                Salary lastSalary = salaryService.getUserSalaryByMonth(user.getUuid(), month.minusMonths(1));// Get the previous salary
                if(baseSalary.getSalary() > lastSalary.getSalary())
                    salaryNotes = new StringBuilder("Ny løn: ").append(formatCurrency(baseSalary.getSalary())).append(". ");
                else if (baseSalary.isLunch()!=lastSalary.isLunch())
                    salaryNotes = new StringBuilder("Tilmeldt frokostordning: ").append(baseSalary.isLunch() ? "Ja" : "Nej" + ". ");
                else if (baseSalary.isPhone()!=lastSalary.isPhone())
                    salaryNotes = new StringBuilder("Fri telefon: ").append(baseSalary.isPhone() ? "Ja" : "Nej" + " ");
                else if (baseSalary.isPrayerDay()!=lastSalary.isPrayerDay())
                    salaryNotes = new StringBuilder("Storbededagstillæg: ").append(baseSalary.isPrayerDay() ? "Ja" : "Nej" + ". ");
            }

            String statusType = "";

            // Check for userstatus changes
            boolean isTerminated = isUserTerminatedInCurrentMonth(user, nextEndOfMonth, nextMonth);
            if(isTerminated) {
                salaryNotes = new StringBuilder("Sidste løn, medarbejder opsagt. ");
                statusType = TERMINATED.getDanlonState();
            }

            boolean hasAnyTypeOfLeave = hasAnyTypeOfLeave(employeeDataPerDayThisMonth);
            if(hasAnyTypeOfLeave) salaryNotes.append("Ingen frokostordning. ");
            else if(hasAnyTypeOfLeave(employeeDataPerDayPreviousMonth)) salaryNotes.append("Frokostordning tiltrådt igen. ");

            boolean hasOnlyNonPayLeave = hasOnlyNonPayLeave(employeeDataPerDayThisMonth);
            if(hasOnlyNonPayLeave) {
                salaryNotes = new StringBuilder("Ingen løn. ");
                statusType = NON_PAY_LEAVE.getDanlonState();
            }

            boolean hasUserJustReturnedFromNonPayLeave = hasUserJustReturnedFromNonPayLeave(user, month, employeeDataPerDayThisMonth);
            if(hasUserJustReturnedFromNonPayLeave) {
                statusType = StatusType.ACTIVE.getDanlonState();
            }

            boolean anyNonPayDays = anyNonPayDays(employeeDataPerDayThisMonth);
            if(anyNonPayDays && !hasOnlyNonPayLeave) {
                EmployeeAvailabilityPerMonth availability = availabilityService.getEmployeeDataPerMonth(user.uuid, month, month.plusMonths(1)).getFirst();
                salaryNotes.append("Norm: 160.33 timer, ");
                salaryNotes.append("Fakt: ").append(NumberUtils.round(baseSalary.calculateActualWorkHours(getWeekdaysInPeriod(month, month.plusMonths(1)), availability.getSalaryAwardingHours() / 7.4), 2)).append(" timer, ");
                salaryNotes.append("Justeret løn: ").append(formatCurrency(baseSalary.calculateMonthNormAdjustedSalary(getWeekdaysInPeriod(month, month.plusMonths(1)), availability.getSalaryAwardingHours() / 7.4)));
                salaryNotes.append("). ");
            } else if (anyNonPayDays(employeeDataPerDayPreviousMonth) && !hasOnlyNonPayLeave(employeeDataPerDayPreviousMonth)) {
                salaryNotes.append("Check at løn er reguleret tilbage til normalt niveau: ").append(formatCurrency(baseSalary.getSalary())).append(". ");
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

        List<User> userList = getUsersWithSpecificCriteria(endOfMonth, month);

        return userList.stream()
                .filter(user -> !user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED))
                .map(user -> new DanlonEmployee(
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
                !user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED) ? "x" : "",
                user.getSalary(endOfMonth).getType()
        )).filter(de -> de.getType()!=null).toList();
    }

    @PUT
    @Path("/employees/salarysupplements/create")
    @Transactional(REQUIRED)
    @CacheInvalidateAll(cacheName = "work-cache")
    public void createTimeStampSalarySupplements(@QueryParam("month") String strMonth) throws SystemException {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);

        for (User user : getActiveConsultantsAndStaffForEndOfMonth(endOfMonth)) {
            if(user.getUserAccount()==null || user.getUserAccount().getDanlon()==null) continue;

            List<Work> vacationList = workService.findByUserAndUnpaidAndMonthAndTaskuuid(user.getUuid(), VACATION, month).stream().filter(w -> w.getRegistered().isBefore(month.plusMonths(1).withDayOfMonth(1))).toList();;
            List<Work> workHoursList = workService.findByUserAndUnpaidAndMonthAndTaskuuid(user.getUuid(), WORK_HOURS, month).stream().filter(w -> w.getRegistered().isBefore(month.plusMonths(1).withDayOfMonth(1))).toList();
            List<TransportationRegistration> unPaidRegistrations = transportationRegistrationService.findByUseruuidAndUnpaidAndMonth(user.getUuid(), month);
            List<Expense> unPaidExpenses = expenseService.findByUserAndUnpaidAndMonth(user.getUuid(), month);

            boolean isAnyPaidOut = false;

            if(vacationList.stream().anyMatch(Work::isPaidOut)) isAnyPaidOut = true;
            if(workHoursList.stream().anyMatch(Work::isPaidOut)) isAnyPaidOut = true;
            if(unPaidRegistrations.stream().anyMatch(TransportationRegistration::isPaidOut)) isAnyPaidOut = true;
            if(unPaidExpenses.stream().anyMatch(Expense::isPaidOut)) isAnyPaidOut = true;

            if(isAnyPaidOut) {
                tm.setRollbackOnly();
                System.out.println("isAnyPaidOut is true for user " + user.getUsername());
                return;
            }

            vacationList.forEach(workService::setPaidAndUpdate);
            workHoursList.forEach(workService::setPaidAndUpdate);
            unPaidRegistrations.forEach(transportationRegistrationService::setPaidAndUpdate);
            unPaidExpenses.forEach(expenseService::setPaidAndUpdate);
        }
    }

    @PUT
    @Path("/employees/salarysupplements/reset")
    @Transactional(REQUIRED)
    @CacheInvalidateAll(cacheName = "work-cache")
    public void resetTimeStampSalarySupplements(@QueryParam("month") String strMonth) throws SystemException {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);

        for (User user : getActiveConsultantsAndStaffForEndOfMonth(endOfMonth)) {
            if(user.getUserAccount()==null || user.getUserAccount().getDanlon()==null) continue;

            List<Work> paidVacationList = workService.findByUserAndPaidOutMonthAndTaskuuid(user.getUuid(), VACATION, month).stream().filter(w -> w.getRegistered().isBefore(month.plusMonths(1).withDayOfMonth(1))).toList();;
            List<Work> paidWorkHoursList = workService.findByUserAndPaidOutMonthAndTaskuuid(user.getUuid(), WORK_HOURS, month).stream().filter(w -> w.getRegistered().isBefore(month.plusMonths(1).withDayOfMonth(1))).toList();
            List<TransportationRegistration> paidRegistrations = transportationRegistrationService.findByUseruuidAndPaidOutMonth(user.getUuid(), month);
            List<Expense> paidExpenses = expenseService.findByUserAndPaidOutMonth(user.getUuid(), month);

            paidVacationList.forEach(workService::clearPaidAndUpdate);
            paidWorkHoursList.forEach(workService::clearPaidAndUpdate);
            paidRegistrations.forEach(transportationRegistrationService::clearPaidAndUpdate);
            paidExpenses.forEach(expenseService::clearPaidAndUpdate);
        }
    }

    @GET
    @Path("/employees/salarysupplements")
    @Transactional(REQUIRED)
    @CacheInvalidateAll(cacheName = "work-cache")
    public List<DanlonSalarySupplements> findSalarySupplements(@QueryParam("month") String strMonth) {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);
        Company company = Company.findById(companyuuid);

        List<DanlonSalarySupplements> result = new ArrayList<>();

        for (User user : getActiveConsultantsAndStaffForEndOfMonth(endOfMonth)) {
            if(user.getUserAccount()==null || user.getUserAccount().getDanlon()==null) continue;
            double bonus = 0;

            bonus += salarySupplementService.findByUseruuidAndMonth(user.getUuid(), month).stream().mapToDouble(SalarySupplement::getValue).sum();
            bonus += salaryLumpSumService.findByUseruuidAndMonth(user.getUuid(), month).stream().mapToDouble(SalaryLumpSum::getLumpSum).sum();

            if(bonus>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "41", "Bonus", "", "", formatDouble(bonus)));

            List<Work> vacationList = workService.findByUserAndPaidOutMonthAndTaskuuid(user.getUuid(), VACATION, month).stream().filter(w -> w.getRegistered().isBefore(month.plusMonths(1).withDayOfMonth(1))).toList();;
            double vacation = vacationList.stream().mapToDouble(Work::getWorkduration).sum();
            if(vacation > 0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "60", "Vacation", formatDouble(vacation/7.4), "", ""));

            List<TransportationRegistration> unPaidRegistrations = transportationRegistrationService.findByUseruuidAndPaidOutMonth(user.getUuid(), month);
            int kilometers = unPaidRegistrations.stream().mapToInt(TransportationRegistration::getKilometers).sum();
            if(kilometers>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "39", "Transportation", formatDouble(kilometers), "", ""));

            List<Expense> unPaidExpenses = expenseService.findByUserAndPaidOutMonth(user.getUuid(), month);
            double expenseSum = unPaidExpenses.stream().mapToDouble(Expense::getAmount).sum();
            if(expenseSum>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "40", "Expenses", "", "", formatDouble(expenseSum)));
            if(expenseSum<0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "20", "Expenses", "", "", formatDouble(expenseSum)));

            List<Work> workHoursList = workService.findByUserAndPaidOutMonthAndTaskuuid(user.getUuid(), WORK_HOURS, month).stream().filter(w -> w.getRegistered().isBefore(month.plusMonths(1).withDayOfMonth(1))).toList();
            double hourSalary = workHoursList.stream().mapToDouble(Work::getWorkduration).sum();
            if(hourSalary>0) result.add(new DanlonSalarySupplements(company.getCvr(), user.getUserAccount().getDanlon(), user.getFullname(), "1", "Hourly salary", formatDouble(hourSalary), "", ""));
        }

        return result;
    }

    private @NotNull List<User> getActiveUsersExcludingPreboardingAndTerminated(LocalDate endOfMonth) {
        return userService.listAll(false).stream()
                .filter(user -> // If user is terminated before this month, ignore
                        !((user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED) || user.getUserStatus(endOfMonth).getStatus().equals(PREBOARDING)) &&
                                user.getUserStatus(endOfMonth).getStatusdate().isBefore(endOfMonth)))
                .filter(user -> // If user is not in the company, ignore
                        user.getUserStatus(endOfMonth).getCompany() != null &&
                                user.getUserStatus(endOfMonth).getCompany().getUuid().equals(companyuuid))
                .filter(user -> // If user is not a consultant or staff, ignore
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.CONSULTANT) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STAFF) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STUDENT))
                .toList();
    }

    private @NotNull List<User> getUsersWithSpecificCriteria(LocalDate endOfMonth, LocalDate month) {
        return userService.listAll(false).stream()
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
    }

    private @NotNull List<User> getActiveConsultantsAndStaffForEndOfMonth(LocalDate endOfMonth) {
        return userService.listAll(false).stream()
                .filter(user ->
                        !(user.getUserStatus(endOfMonth).getStatus().equals(TERMINATED) ||
                        user.getUserStatus(endOfMonth).getStatus().equals(PREBOARDING)))
                .filter(user ->
                        user.getUserStatus(endOfMonth).getCompany() != null &&
                                user.getUserStatus(endOfMonth).getCompany().getUuid().equals(companyuuid))
                .filter(user ->
                        user.getUserStatus(endOfMonth).getType().equals(ConsultantType.CONSULTANT) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STAFF) ||
                                user.getUserStatus(endOfMonth).getType().equals(ConsultantType.STUDENT))
                .toList();
    }

    private static boolean anyNonPayDays(List<EmployeeAvailabilityPerDayAggregate> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) || e.getStatusType().equals(PREBOARDING) ||
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
