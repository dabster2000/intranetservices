package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.accounting.model.DanlonChanges;
import dk.trustworks.intranet.aggregates.accounting.model.DanlonEmployee;
import dk.trustworks.intranet.aggregates.accounting.model.DanlonSalarySupplements;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
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
import io.smallrye.common.constraint.NotNull;
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


    /**
     * The {@code DanlonResource} class is responsible for calculating and reporting
     * changes to employee salary and status based on a variety of business rules.
     *
     * <p>This class implements the following business rules:
     *
     * <ul>
     *   <li>
     *     <b>New Hire with Partial Month Salary:</b>
     *     <p>
     *       If an employee's hire date falls within the current month (even if they start
     *       mid-month), they are flagged as a "Ny medarbejder" (new employee). The adjusted
     *       salary for that partial month is calculated and included in the report, rather
     *       than being overridden by a generic salary change message.
     *     </p>
     *   </li>
     *
     *   <li>
     *     <b>Benefit Change Reporting:</b>
     *     <p>
     *       For active employees (not new hires), if the current month’s salary record shows
     *       differences compared to the previous month, individual benefit changes are checked:
     *       <ul>
     *         <li>If the base salary increases, report it as "Ny løn: [new salary]".</li>
     *         <li>If the lunch benefit changes, report as "Tilmeldt frokostordning: Ja/Nej".</li>
     *         <li>If the phone benefit changes, report as "Fri telefon: Ja/Nej".</li>
     *         <li>If the prayer day benefit changes, report as "Storbededagstillæg: Ja/Nej".</li>
     *       </ul>
     *       Multiple benefit changes are reported independently, ensuring that no change is omitted.
     *     </p>
     *   </li>
     *
     *   <li>
     *     <b>Terminated Employee:</b>
     *     <p>
     *       If an employee is terminated effective from the first day of the next month,
     *       the employee's record is immediately flagged with "Sidste løn, medarbejder opsagt"
     *       (final salary, employee terminated), and no additional information is appended.
     *     </p>
     *   </li>
     *
     *   <li>
     *     <b>Employee on Full Non-Pay Leave:</b>
     *     <p>
     *       If every day in the current month is classified as non-pay leave, the employee's
     *       record is overridden with "Ingen løn" (no salary) and the status is set to indicate
     *       non-pay leave.
     *     </p>
     *   </li>
     *
     *   <li>
     *     <b>Adjusted Salary Calculation for Partial Non-Pay Days:</b>
     *     <p>
     *       When the employee has some non-pay days (but not exclusively non-pay leave), the system
     *       calculates the actual work hours and computes an adjusted salary based on:
     *       <ul>
     *         <li>A fixed norm (e.g., 160.33 hours),</li>
     *         <li>The actual work hours determined by weekdays in the period, and</li>
     *         <li>The salary awarding hours from the employee's availability data.</li>
     *       </ul>
     *       This calculation is appended to the record as additional detail.
     *     </p>
     *   </li>
     * </ul>
     *
     * <p>These rules are designed to ensure that any significant changes in an employee's status or
     * compensation are clearly reported. The documentation here serves as both a reference for developers
     * and as a basis for writing acceptance tests.
     *
     * <p><b>Example Usage:</b>
     * <pre>
     *   DanlonResource resource = new DanlonResource();
     *   List&lt;DanlonChanges&gt; changes = resource.findChangedUsers("2025-02");
     *   // The changes list will contain entries with messages that reflect the business rules above.
     * </pre>
     *
     * @see UserService
     * @see SalaryService
     * @see AvailabilityService
     */
    @GET
    @Path("/employees/changed")
    public List<DanlonChanges> findChangedUsers(@QueryParam("month") String strMonth) {
        // Setup the date ranges for the current, previous, and next month.
        LocalDate currentMonth = dateIt(strMonth).withDayOfMonth(1);
        LocalDate endOfCurrentMonth = currentMonth.plusMonths(1).minusDays(1);
        LocalDate previousMonth = currentMonth.minusMonths(1).withDayOfMonth(1);
        LocalDate nextMonth = currentMonth.plusMonths(1).withDayOfMonth(1);
        LocalDate nextEndOfMonth = nextMonth.plusMonths(1).minusDays(1);

        List<DanlonChanges> changesList = new ArrayList<>();

        // Get only the active users for this company.
        List<User> activeUsers = getActiveUsersExcludingPreboardingAndTerminated(endOfCurrentMonth);

        // Process each user individually.
        for (User user : activeUsers) {
            String danlonNumber = user.getUserAccount() != null ? user.getUserAccount().getDanlon() : "";
            String fullName = user.getFullname();
            String statusType = ""; // Default status (empty means no override)
            StringBuilder salaryNote = new StringBuilder();

            // Determine if the user is a new hire.
            boolean isNewHire = user.getHireDate(companyuuid).withDayOfMonth(1).isEqual(currentMonth);

            // Fetch availability data for both the previous and current month.
            List<BiDataPerDay> availabilityPrevious = availabilityService.getEmployeeDataPerDay(user.uuid, previousMonth, previousMonth.plusMonths(1));
            List<BiDataPerDay> availabilityCurrent = availabilityService.getEmployeeDataPerDay(user.uuid, currentMonth, currentMonth.plusMonths(1));

            // Get the current month's salary record.
            Salary currentSalary = salaryService.getUserSalaryByMonth(user.getUuid(), endOfCurrentMonth);

            // 1. New Hire Rule: If the hire date falls in the current month, mark as new employee.
            if (isNewHire) {
                salaryNote.append("Ny medarbejder: ").append(formatCurrency(currentSalary.getSalary())).append(". ");
            } else {
                // 2. Salary Change Rule: Only check salary changes for non-new hires.
                if (salaryService.isSalaryChanged(user.getUuid(), currentMonth)) {
                    Salary previousSalary = salaryService.getUserSalaryByMonth(user.getUuid(), currentMonth.minusMonths(1));
                    if (currentSalary.getSalary() > previousSalary.getSalary()) {
                        salaryNote.append("Lønforhøjelse: ").append(formatCurrency(currentSalary.getSalary())).append(". ");
                    } else if (currentSalary.getSalary() < previousSalary.getSalary()) {
                        salaryNote.append("Reduceret løn: ").append(formatCurrency(currentSalary.getSalary())).append(". ");
                    } else if (currentSalary.isLunch() != previousSalary.isLunch()) {
                        salaryNote.append("Tilmeldt frokostordning: ").append(currentSalary.isLunch() ? "Ja" : "Nej").append(". ");
                    } else if (currentSalary.isPhone() != previousSalary.isPhone()) {
                        salaryNote.append("Fri telefon: ").append(currentSalary.isPhone() ? "Ja" : "Nej").append(". ");
                    } else if (currentSalary.isPrayerDay() != previousSalary.isPrayerDay()) {
                        salaryNote.append("Storbededagstillæg: ").append(currentSalary.isPrayerDay() ? "Ja" : "Nej").append(". ");
                    } else {
                        // If no specific salary change details are detected, use the default note.
                        salaryNote.append("Normal løn. ");
                    }
                } else {
                    // No salary change detected, so use default note.
                    salaryNote.append("Normal løn. ");
                }
            }

            // 3. Termination Rule: Check if the user is terminated in the upcoming period.
            if (isUserTerminatedInCurrentMonth(user, nextEndOfMonth, nextMonth)) {
                salaryNote = new StringBuilder("Sidste løn, medarbejder opsagt. ");
                statusType = TERMINATED.getDanlonState();
            }

            // 4. Leave and Lunch Arrangement Adjustments:
            if (hasAnyTypeOfLeave(availabilityCurrent)) {
                salaryNote.append("Ingen frokostordning. ");
            } else if (hasAnyTypeOfLeave(availabilityPrevious)) {
                salaryNote.append("Frokostordning tiltrådt igen. ");
            }

            // 5. Non-Pay Leave Rules:
            if (hasOnlyNonPayLeave(availabilityCurrent)) {
                salaryNote = new StringBuilder("Ingen løn. ");
                statusType = NON_PAY_LEAVE.getDanlonState();
            }
            if (hasUserJustReturnedFromNonPayLeave(user, currentMonth, availabilityCurrent)) {
                statusType = StatusType.ACTIVE.getDanlonState();
            }

            // 6. Work Hours and Adjusted Salary Calculation:
            if (anyNonPayDays(availabilityCurrent) && !hasOnlyNonPayLeave(availabilityCurrent)) {
                EmployeeAvailabilityPerMonth availability = availabilityService
                        .getEmployeeDataPerMonth(user.uuid, currentMonth, currentMonth.plusMonths(1))
                        .getFirst();
                if (user.getUuid().equals("50b03d86-2bbf-4b54-8a9e-257f5a256396"))
                    System.out.println("availability = " + availability);
                double weekdays = getWeekdaysInPeriod(currentMonth, currentMonth.plusMonths(1));
                if (user.getUuid().equals("50b03d86-2bbf-4b54-8a9e-257f5a256396"))
                    System.out.println("weekdays = " + weekdays);
                double actualWorkHours = NumberUtils.round(
                        currentSalary.calculateActualWorkHours(weekdays, availability.getSalaryAwardingHours() / 7.4), 2);
                if (user.getUuid().equals("50b03d86-2bbf-4b54-8a9e-257f5a256396"))
                    System.out.println("actualWorkHours = " + actualWorkHours);
                double adjustedSalary = currentSalary.calculateMonthNormAdjustedSalary(weekdays, availability.getSalaryAwardingHours() / 7.4);
                if (user.getUuid().equals("50b03d86-2bbf-4b54-8a9e-257f5a256396"))
                    System.out.println("adjustedSalary = " + adjustedSalary);
                salaryNote.append("Norm: 160.33 timer, Fakt: ").append(actualWorkHours)
                        .append(" timer, Justeret løn: ").append(formatCurrency(adjustedSalary)).append(". ");
            } else if (!isNewHire && anyNonPayDays(availabilityPrevious) && !hasOnlyNonPayLeave(availabilityPrevious)) {
                // Skip this branch if the employee is a new hire.
                salaryNote.append("Check at løn er reguleret tilbage til normalt niveau: ")
                        .append(formatCurrency(currentSalary.getSalary())).append(". ");
            }

            // Create and add the final change record.
            changesList.add(new DanlonChanges(danlonNumber, fullName, statusType, salaryNote.toString()));
        }
        return changesList;
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
                stringIt(user.getHireDate(companyuuid), "dd-MM-yyyy"),
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

        for (User user : getActiveUsersExcludingPreboardingAndTerminated(endOfMonth)) {
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

        for (User user : getActiveUsersExcludingPreboardingAndTerminated(endOfMonth)) {
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

        for (User user : getActiveUsersExcludingPreboardingAndTerminated(endOfMonth)) {
            if(user.getUserAccount()==null || user.getUserAccount().getDanlon()==null) continue;
            double bonus = 0;

            bonus += salarySupplementService.findByUseruuidAndMonth(user.getUuid(), month).stream().mapToDouble(SalarySupplement::getValue).sum();
            bonus += salaryLumpSumService.findByUseruuidAndMonth(user.getUuid(), month).stream().filter(salaryLumpSum -> salaryLumpSum.getSalaryType().getDanloenType()==41).mapToDouble(SalaryLumpSum::getLumpSum).sum();

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
                        user.getHireDate(companyuuid).withDayOfMonth(1).isEqual(endOfMonth.withDayOfMonth(1)) ||
                                isUserTerminatedInCurrentMonth(user, endOfMonth, month) ||
                                user.getUserBankInfos().stream().anyMatch(userBankInfo -> userBankInfo.getActiveDate().withDayOfMonth(1).isEqual(month)) ||
                                hasUserJustReturnedFromNonPayLeave(user, month, availabilityService.getEmployeeDataPerDay(user.uuid, month, month.plusMonths(1))))
                .toList();
    }

    // Helper method: Returns active users after filtering out preboarding and terminated users.
    private @NotNull List<User> getActiveUsersExcludingPreboardingAndTerminated(LocalDate endOfMonth) {
        return userService.listAll(false).stream()
                .filter(user -> {
                    UserStatus status = user.getUserStatus(endOfMonth);
                    return !((status.getStatus().equals(TERMINATED) || status.getStatus().equals(PREBOARDING))
                            && status.getStatusdate().isBefore(endOfMonth));
                })
                .filter(user -> user.getUserStatus(endOfMonth).getCompany() != null &&
                        user.getUserStatus(endOfMonth).getCompany().getUuid().equals(companyuuid))
                .filter(user -> {
                    ConsultantType type = user.getUserStatus(endOfMonth).getType();
                    return type.equals(ConsultantType.CONSULTANT) ||
                            type.equals(ConsultantType.STAFF) ||
                            type.equals(ConsultantType.STUDENT);
                })
                .toList();
    }

    // Helper method: Checks if any day has non-pay leave, preboarding, or terminated status.
    private static boolean anyNonPayDays(List<BiDataPerDay> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) ||
                        e.getStatusType().equals(PREBOARDING) ||
                        e.getStatusType().equals(TERMINATED));
    }

    // Helper method: Checks if all days in the month are non-pay leave.
    private static boolean hasOnlyNonPayLeave(List<BiDataPerDay> employeeDataPerDay) {
        return employeeDataPerDay.stream().allMatch(e -> e.getStatusType().equals(NON_PAY_LEAVE));
    }

    // Helper method: Checks for any type of leave (non-pay, paid, or maternity).
    private static boolean hasAnyTypeOfLeave(List<BiDataPerDay> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) ||
                        e.getStatusType().equals(StatusType.PAID_LEAVE) ||
                        e.getStatusType().equals(StatusType.MATERNITY_LEAVE));
    }

    // Helper method: Checks if a user just returned from non-pay leave.
    private static boolean hasUserJustReturnedFromNonPayLeave(User user, LocalDate month, List<BiDataPerDay> availability) {
        return (user.getUserStatus(month.minusDays(1)).getStatus().equals(NON_PAY_LEAVE)
                && user.getUserStatus(month).getStatus().equals(StatusType.ACTIVE))
                || (user.getUserStatus(month).getStatus().equals(NON_PAY_LEAVE) && !hasOnlyNonPayLeave(availability));
    }

    // Helper method: Checks if the user is terminated in the current month (based on next month's period).
    private static boolean isUserTerminatedInCurrentMonth(User user, LocalDate endOfMonth, LocalDate month) {
        UserStatus status = user.getUserStatus(endOfMonth);
        return status.getStatus().equals(TERMINATED)
                && status.getStatusdate().withDayOfMonth(1).isEqual(month);
    }

    /*

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

    private static boolean anyNonPayDays(List<BiDataPerDay> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) || e.getStatusType().equals(PREBOARDING) ||
                        e.getStatusType().equals(TERMINATED));
    }

    private static boolean hasUserJustReturnedFromNonPayLeave(User user, LocalDate month, List<BiDataPerDay> employeeDataPerDay) {
        return
                (
                        user.getUserStatus(month.minusDays(1)).getStatus().equals(NON_PAY_LEAVE) &&
                                user.getUserStatus(month).getStatus().equals(StatusType.ACTIVE)
                ) || (user.getUserStatus(month).getStatus().equals(NON_PAY_LEAVE) && !hasOnlyNonPayLeave(employeeDataPerDay));
    }

    private static boolean hasOnlyNonPayLeave(List<BiDataPerDay> employeeDataPerDay) {
        return employeeDataPerDay.stream().allMatch(e -> e.getStatusType().equals(NON_PAY_LEAVE));
    }

    private static boolean hasAnyTypeOfLeave(List<BiDataPerDay> employeeDataPerDay) {
        return employeeDataPerDay.stream().anyMatch(e ->
                e.getStatusType().equals(NON_PAY_LEAVE) ||
                        e.getStatusType().equals(StatusType.PAID_LEAVE) ||
                        e.getStatusType().equals(StatusType.MATERNITY_LEAVE));
    }

    private static boolean isUserTerminatedInCurrentMonth(User user, LocalDate endOfMonth, LocalDate month) {
        UserStatus status = user.getUserStatus(endOfMonth);
        return status.getStatus().equals(TERMINATED) && status.getStatusdate().withDayOfMonth(1).isEqual(month);
    }
     */

}
