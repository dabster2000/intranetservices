package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusAdjustment;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.dto.PaycheckOverview;
import dk.trustworks.intranet.aggregates.users.dto.SalaryPayment;
import dk.trustworks.intranet.aggregates.users.services.*;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.domain.user.entity.SalarySupplement;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.TransportationRegistration;
import dk.trustworks.intranet.userservice.model.VacationPool;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeResolution;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.utils.NumberUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.WORK_HOURS;
import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.NumberUtils.convertDoubleToInt;
import static dk.trustworks.intranet.utils.NumberUtils.formatCurrency;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@JBossLog
@RolesAllowed({"salaries:read"})
@SecurityRequirement(name = "jwt")
public class SalaryResource {

    @Inject
    SalaryService salaryService;

    @Inject
    VacationService vacationService;

    @Inject
    WorkService workService;

    @Inject
    TransportationRegistrationService transportationRegistrationService;

    @Inject
    ExpenseService expenseService;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    SalarySupplementService salarySupplementService;

    @Inject
    SalaryLumpSumService salaryLumpSumService;

    @Inject
    UserService userService;

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    AuthorizationService authorizationService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;


    @GET
    @Path("/{useruuid}/salaries")
    public List<Salary> listAll(@PathParam("useruuid") String useruuid) {
        String actor = actorOrNull();
        // No actor: batch job or API client — Phase 12 (see actorOrNull).
        // Self-read: your own salary history is yours whatever your grants resolve to,
        // and findByUseruuid(useruuid) is already bounded to exactly that one subject.
        if (actor == null || isSelfRead(actor, useruuid)) {
            return salaryService.findByUseruuid(useruuid);
        }
        ScopeResolution reach = authorizationService.resolveReach(
                actor, "salaries:read", LocalDate.now(), Set.of());
        if (reach.unbounded()) {
            return salaryService.findByUseruuid(useruuid);
        }
        if (!reach.permits(useruuid)) {
            throw salariesReadDenied(useruuid);
        }
        // 8.6: the resolved subject set reaches the WHERE clause, never a post-filter.
        return salaryService.findByUseruuid(useruuid, reach.subjects());
    }

    @GET
    @Path("/{useruuid}/salaries/payments/{month}")
    public List<SalaryPayment> listPayments(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        enforceSalariesReadScope(useruuid);
        List<SalaryPayment> payments = new ArrayList<>();
        LocalDate date;
        try {
            date = dateIt(month).withDayOfMonth(1);
        } catch (DateTimeException e) {
            log.errorf("Invalid month format: %s", month);
            // You might want to throw a BadRequestException or similar here
            return payments;
        }

        // Fetch availability data
        Optional<EmployeeAvailabilityPerMonth> optionalAvailability = availabilityService.getEmployeeDataPerMonth(useruuid, date, date.plusMonths(1)).stream().findFirst();
        if (optionalAvailability.isEmpty()) {
            log.warnf("No availability data found for user: %s, month: %s", useruuid, date);
            // Depending on business logic, you might want to proceed or return early
        }
        EmployeeAvailabilityPerMonth availability = optionalAvailability.orElse(null);

        // Fetch base salary
        Salary baseSalary = salaryService.getUserSalaryByMonth(useruuid, date);
        if (baseSalary == null) {
            log.warnf("No base salary found for user: %s, month: %s", useruuid, date);
            return payments; // Early exit if no base salary
        }

        // Process base salary based on type
        try {
            SalaryType salaryType = baseSalary.getType();
            if (salaryType == null) {
                log.warnf("Salary has null type for user: %s, month: %s — skipping base salary payment", useruuid, date);
            } else if (salaryType == SalaryType.NORMAL) {
                if (availability != null && availability.getGrossAvailableHours().doubleValue() > availability.getSalaryAwardingHours()) {
                    double adjustedSalary = baseSalary.calculateMonthNormAdjustedSalary(
                            availability.getGrossAvailableHours().doubleValue() / 7.4,
                            availability.getSalaryAwardingHours() / 7.4
                    );
                    baseSalary.setSalary(convertDoubleToInt(adjustedSalary));
                }
                payments.add(new SalaryPayment(date, "Base salary", formatCurrency(baseSalary.getSalary())));
            } else if (salaryType == SalaryType.HOURLY) {
                List<Work> workHoursList = workService.findByUserAndUnpaidAndMonthAndTaskuuid(useruuid, WORK_HOURS, date).stream()
                        .filter(w -> w.getRegistered().isBefore(date.plusMonths(1).withDayOfMonth(1)))
                        .toList();
                double sum = workHoursList.stream().mapToDouble(work -> work.getWorkduration() * baseSalary.getSalary()).sum();
                payments.add(new SalaryPayment(date, "Hourly pay", formatCurrency(sum)));
            }
        } catch (Exception e) {
            log.errorf(e, "Error processing base salary for user: %s, month: %s", useruuid, date);
            // Handle or rethrow as needed
        }

        // Process salary supplements
        try {
            salarySupplementService.findByUseruuidAndMonth(useruuid, date).forEach(salarySupplement -> {
                if (salarySupplement != null) {
                    payments.add(new SalaryPayment(date, salarySupplement.getDescription(), formatCurrency(salarySupplement.getValue())));
                } else {
                    log.warnf("Null salary supplement found for user: %s, month: %s", useruuid, date);
                }
            });
        } catch (Exception e) {
            log.errorf(e, "Error processing salary supplements for user: %s, month: %s", useruuid, date);
        }

        // Process salary lump sums
        try {
            salaryLumpSumService.findByUseruuidAndMonth(useruuid, date).forEach(salaryLumpSum -> {
                if (salaryLumpSum != null) {
                    payments.add(new SalaryPayment(date, salaryLumpSum.getDescription(), formatCurrency(salaryLumpSum.getLumpSum())));
                } else {
                    log.warnf("Null salary lump sum found for user: %s, month: %s", useruuid, date);
                }
            });
        } catch (Exception e) {
            log.errorf(e, "Error processing salary lump sums for user: %s, month: %s", useruuid, date);
        }

        // Process vacation
        try {
            List<Work> vacationList = workService.findByUserAndPaidOutMonthAndTaskuuid(useruuid, VACATION, date).stream()
                    .filter(w -> w.getRegistered().isBefore(date.plusMonths(1).withDayOfMonth(1)))
                    .toList();
            double vacation = vacationList.stream().mapToDouble(Work::getWorkduration).sum();
            payments.add(new SalaryPayment(date, "Vacation", NumberUtils.formatDouble(vacation / 7.4) + " days"));
        } catch (Exception e) {
            log.errorf(e, "Error processing vacation for user: %s, month: %s", useruuid, date);
        }

        // Process available vacation days
        try {
            VacationPool vacationPool = null;// vacationService.calculateRemainingVacationDays(useruuid);
            if (vacationPool == null) {
                log.warnf("VacationPool is null for user: %s", useruuid);
            } else {
                vacationService.getActiveVacationPeriods().forEach(period -> {
                    try {
                        VacationPool pool = vacationPool.findPool(period.getKey());
                        double remainingVacationDays = pool != null ? pool.getRemainingVacation() : 0.0;
                        payments.add(new SalaryPayment(
                                date,
                                "Available vacation (test) " + period.getValue().getYear(),
                                NumberUtils.round(remainingVacationDays, 2) + " days"
                        ));
                    } catch (Exception ex) {
                        log.errorf(ex, "Error processing vacation pool for period: %s, user: %s", period.getKey(), useruuid);
                    }
                });
            }
        } catch (Exception e) {
            log.errorf(e, "Error calculating remaining vacation days for user: %s", useruuid);
        }

        // Process transportation
        try {
            int kilometers = transportationRegistrationService.findByUseruuidAndPaidOutMonth(useruuid, date).stream()
                    .mapToInt(TransportationRegistration::getKilometers)
                    .sum();
            if (kilometers > 0) {
                payments.add(new SalaryPayment(date, "Transportation", kilometers + " km"));
            }
        } catch (Exception e) {
            log.errorf(e, "Error processing transportation for user: %s, month: %s", useruuid, date);
        }

        // Process expenses
        try {
            double expenseSum = expenseService.findByUserAndPaidOutMonth(useruuid, date).stream()
                    .mapToDouble(Expense::getAmount)
                    .sum();
            if (expenseSum > 0) {
                payments.add(new SalaryPayment(date, "Expenses", formatCurrency(expenseSum)));
            }
        } catch (Exception e) {
            log.errorf(e, "Error processing expenses for user: %s, month: %s", useruuid, date);
        }

        return payments;
    }

    /**
     * Paycheck composition for the profile "Paycheck" tab: the last six locked payroll
     * runs plus the open "next paycheck" bucket. Historical months mirror exactly what
     * HR reported to Danløn (paid_out-stamped rows per pay month); the open bucket shows
     * what the next "Create Timestamp" sweep on the salary-payment page would lock in.
     */
    @GET
    @Path("/{useruuid}/salaries/paycheck-overview")
    public PaycheckOverview getPaycheckOverview(@PathParam("useruuid") String useruuid) {
        enforceSalariesReadScope(useruuid);
        LocalDate nextPayMonth = resolveNextPayMonth(useruuid);
        List<PaycheckOverview.PaycheckMonth> months = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            months.add(buildPaycheckMonth(useruuid, nextPayMonth.minusMonths(i), false));
        }
        PaycheckOverview.PaycheckMonth next = buildPaycheckMonth(useruuid, nextPayMonth, true);
        return new PaycheckOverview(nextPayMonth, months, next);
    }

    /**
     * The next open pay month is the current calendar month until the payroll sweep for
     * the user's company has stamped items this month; after that it is the month after.
     * Detection is company-scoped because DanlonResource stamps per company.
     */
    private LocalDate resolveNextPayMonth(String useruuid) {
        LocalDate currentMonth = LocalDate.now().withDayOfMonth(1);
        LocalDateTime monthStart = currentMonth.atStartOfDay();
        LocalDateTime nextMonthStart = currentMonth.plusMonths(1).atStartOfDay();

        List<String> companyUserUuids = List.of(useruuid);
        try {
            User user = userService.findById(useruuid, false);
            Company company = user != null ? user.getUserStatus(currentMonth.plusMonths(1).minusDays(1)).getCompany() : null;
            if (company != null) {
                List<String> uuids = userService.listAllByCompany(company.getUuid(), true).stream()
                        .map(User::getUuid).toList();
                if (!uuids.isEmpty()) companyUserUuids = uuids;
            }
        } catch (Exception e) {
            log.warnf(e, "Could not resolve company users for paycheck sweep detection, falling back to user %s", useruuid);
        }

        boolean swept = Work.count("taskuuid in ?1 and paidOut >= ?2 and paidOut < ?3 and useruuid in ?4",
                List.of(VACATION, WORK_HOURS), monthStart, nextMonthStart, companyUserUuids) > 0
                || Expense.count("paidOut >= ?1 and paidOut < ?2 and useruuid in ?3",
                monthStart, nextMonthStart, companyUserUuids) > 0
                || TransportationRegistration.count("paidOut >= ?1 and paidOut < ?2 and useruuid in ?3",
                monthStart, nextMonthStart, companyUserUuids) > 0;

        return swept ? currentMonth.plusMonths(1) : currentMonth;
    }

    private PaycheckOverview.PaycheckMonth buildPaycheckMonth(String useruuid, LocalDate month, boolean open) {
        LocalDate endOfMonth = month.plusMonths(1).minusDays(1);
        LocalDate firstOfNextMonth = month.plusMonths(1);

        PaycheckOverview.PaycheckMonth result = new PaycheckOverview.PaycheckMonth();
        result.setMonth(month);
        result.setLocked(!open);

        // Base salary (leave-adjusted for NORMAL employees, mirrors listPayments)
        Salary salary = null;
        try {
            salary = salaryService.getUserSalaryByMonth(useruuid, endOfMonth);
        } catch (Exception e) {
            log.warnf(e, "No salary record for user %s month %s", useruuid, month);
        }
        SalaryType salaryType = salary != null ? salary.getType() : null;
        result.setSalaryType(salaryType != null ? salaryType.name() : null);
        if (salaryType == SalaryType.NORMAL) {
            double base = salary.getSalary();
            result.setBaseSalary(base);
            try {
                Optional<EmployeeAvailabilityPerMonth> optionalAvailability =
                        availabilityService.getEmployeeDataPerMonth(useruuid, month, firstOfNextMonth).stream().findFirst();
                if (optionalAvailability.isPresent()) {
                    EmployeeAvailabilityPerMonth availability = optionalAvailability.get();
                    if (availability.getGrossAvailableHours().doubleValue() > availability.getSalaryAwardingHours()) {
                        double adjusted = salary.calculateMonthNormAdjustedSalary(
                                availability.getGrossAvailableHours().doubleValue() / 7.4,
                                availability.getSalaryAwardingHours() / 7.4);
                        result.setBaseSalary(NumberUtils.round(adjusted, 2));
                        result.setSalaryAdjusted(true);
                        result.setUnadjustedSalary(base);
                    }
                }
            } catch (Exception e) {
                log.warnf(e, "Availability adjustment failed for user %s month %s", useruuid, month);
            }
        }

        // Committed monthly individual bonus for this pay month (snapshot-driven, like the
        // Danløn export). Source references are collected so the mirroring lump-sum rows
        // are not double counted below.
        Set<String> bonusSourceReferences = new HashSet<>();
        double bonus = 0;
        for (IndividualBonusPayout payout : IndividualBonusPayout.<IndividualBonusPayout>find(
                "snapshotVersion = 2 and payMonth = ?1 and materializationStatus = 'COMMITTED' and userUuid = ?2",
                month, useruuid).list()) {
            if (payout.getAmount() == null || payout.getAmount().signum() <= 0) continue;
            bonusSourceReferences.add(payout.getSourceReference());
            bonus += payout.getAmount().doubleValue();
        }
        for (IndividualBonusAdjustment adjustment : IndividualBonusAdjustment.<IndividualBonusAdjustment>find(
                "payMonth = ?1 and state = 'ADJUSTMENT_COMMITTED' and userUuid = ?2",
                month, useruuid).list()) {
            if (adjustment.getDeltaAmount() == null || adjustment.getDeltaAmount().signum() <= 0) continue;
            bonusSourceReferences.add(adjustment.getAdjustmentSourceReference());
            bonus += adjustment.getDeltaAmount().doubleValue();
        }
        result.setBonus(NumberUtils.round(bonus, 2));

        // Supplements and lump sums for the pay month
        for (SalarySupplement supplement : salarySupplementService.findByUseruuidAndMonth(useruuid, month)) {
            result.getSupplements().add(new PaycheckOverview.PaycheckLine(
                    supplement.getDescription(), supplement.getValue() != null ? supplement.getValue() : 0,
                    supplement.getWithPension()));
        }
        result.setSupplementsTotal(result.getSupplements().stream().mapToDouble(PaycheckOverview.PaycheckLine::getAmount).sum());
        for (SalaryLumpSum lumpSum : salaryLumpSumService.findByUseruuidAndMonth(useruuid, month)) {
            if (lumpSum.getSourceReference() != null && bonusSourceReferences.contains(lumpSum.getSourceReference())) continue;
            result.getLumpSums().add(new PaycheckOverview.PaycheckLine(
                    lumpSum.getDescription(), lumpSum.getLumpSum() != null ? lumpSum.getLumpSum() : 0,
                    lumpSum.getPension()));
        }
        result.setLumpSumsTotal(result.getLumpSums().stream().mapToDouble(PaycheckOverview.PaycheckLine::getAmount).sum());

        // Vacation registrations swept into this pay month (or, when open, awaiting the sweep)
        List<Work> vacationRows;
        if (open) {
            List<Work> unpaid = workService.findByUserAndUnpaidAndMonthAndTaskuuid(useruuid, VACATION, month).stream()
                    .filter(w -> !w.isPaidOut()).toList();
            vacationRows = unpaid.stream().filter(w -> w.getRegistered().isBefore(firstOfNextMonth)).toList();
            unpaid.stream().filter(w -> !w.getRegistered().isBefore(firstOfNextMonth))
                    .sorted(Comparator.comparing(Work::getRegistered))
                    .forEach(w -> result.getLaterVacation().add(toWorkItem(w)));
        } else {
            vacationRows = workService.findByUserAndPaidOutMonthAndTaskuuid(useruuid, VACATION, month).stream()
                    .filter(w -> w.getRegistered().isBefore(firstOfNextMonth)).toList();
        }
        vacationRows.stream().sorted(Comparator.comparing(Work::getRegistered))
                .forEach(w -> result.getVacation().add(toWorkItem(w)));
        result.setVacationDays(NumberUtils.round(vacationRows.stream().mapToDouble(Work::getWorkduration).sum() / 7.4, 2));

        // Hourly-wage registrations (HOURLY employees)
        List<Work> hourlyRows;
        if (open) {
            hourlyRows = workService.findByUserAndUnpaidAndMonthAndTaskuuid(useruuid, WORK_HOURS, month).stream()
                    .filter(w -> !w.isPaidOut())
                    .filter(w -> w.getRegistered().isBefore(firstOfNextMonth)).toList();
        } else {
            hourlyRows = workService.findByUserAndPaidOutMonthAndTaskuuid(useruuid, WORK_HOURS, month).stream()
                    .filter(w -> w.getRegistered().isBefore(firstOfNextMonth)).toList();
        }
        double hourlyRate = 0;
        if (!hourlyRows.isEmpty()) {
            if (salaryType == SalaryType.HOURLY) {
                hourlyRate = salary.getSalary();
            } else {
                // Type changed since (e.g. HOURLY -> NORMAL): use the latest hourly rate on record
                hourlyRate = salaryService.findByUseruuid(useruuid).stream()
                        .filter(s -> s.getType() == SalaryType.HOURLY && !s.getActivefrom().isAfter(endOfMonth))
                        .max(Comparator.comparing(Salary::getActivefrom))
                        .map(s -> (double) s.getSalary()).orElse(0.0);
            }
        }
        hourlyRows.stream().sorted(Comparator.comparing(Work::getRegistered))
                .forEach(w -> result.getHourlyWork().add(toWorkItem(w)));
        double hourlyHours = hourlyRows.stream().mapToDouble(Work::getWorkduration).sum();
        result.setHourlyHours(NumberUtils.round(hourlyHours, 2));
        result.setHourlyRate(hourlyRate);
        result.setHourlyAmount(NumberUtils.round(hourlyHours * hourlyRate, 2));

        // Transportation registrations
        List<TransportationRegistration> transportRows = open
                ? transportationRegistrationService.findByUseruuidAndUnpaidAndMonth(useruuid, month).stream()
                        .filter(t -> !t.isPaidOut()).toList()
                : transportationRegistrationService.findByUseruuidAndPaidOutMonth(useruuid, month);
        transportRows.stream().sorted(Comparator.comparing(TransportationRegistration::getDate))
                .forEach(t -> result.getTransportation().add(new PaycheckOverview.PaycheckTransportItem(
                        t.getDate(), t.getPurpose(), t.getDestination(),
                        t.getKilometers() != null ? t.getKilometers() : 0, t.getPaidOut())));
        result.setTransportationKm(result.getTransportation().stream()
                .mapToInt(PaycheckOverview.PaycheckTransportItem::getKilometers).sum());

        // Expense reimbursements
        List<Expense> expenseRows = open
                ? expenseService.findByUserAndUnpaidAndMonth(useruuid, month).stream()
                        .filter(e -> !e.isPaidOut()).toList()
                : expenseService.findByUserAndPaidOutMonth(useruuid, month);
        expenseRows.stream().sorted(Comparator.comparing(Expense::getExpensedate))
                .forEach(e -> result.getExpenses().add(toExpenseItem(e)));
        result.setExpensesTotal(NumberUtils.round(expenseRows.stream()
                .mapToDouble(e -> e.getAmount() != null ? e.getAmount() : 0).sum(), 2));

        // Open bucket only: unpaid expenses whose status keeps them out of the sweep
        if (open) {
            List<Expense> notReady = Expense.<Expense>find(
                    "useruuid = ?1 and paidOut is null and status not in ?2 and expensedate >= ?3",
                    useruuid,
                    List.of(ExpenseService.STATUS_UPLOADED, ExpenseService.STATUS_VERIFIED_UNBOOKED,
                            ExpenseService.STATUS_VERIFIED_BOOKED, ExpenseService.STATUS_DELETED),
                    month.minusMonths(12)).list();
            notReady.stream().sorted(Comparator.comparing(Expense::getExpensedate))
                    .forEach(e -> result.getExpensesNotReady().add(toExpenseItem(e)));
            result.setExpensesNotReadyTotal(NumberUtils.round(notReady.stream()
                    .mapToDouble(e -> e.getAmount() != null ? e.getAmount() : 0).sum(), 2));
        }

        // Informational: sick days registered in this calendar month (never swept by payroll)
        List<Work> sicknessRows = workService.findByUserAndTaskuuidAndRegisteredBetween(useruuid, SICKNESS, month, firstOfNextMonth);
        sicknessRows.stream().sorted(Comparator.comparing(Work::getRegistered))
                .forEach(w -> result.getSickness().add(toWorkItem(w)));
        result.setSicknessDays(NumberUtils.round(sicknessRows.stream().mapToDouble(Work::getWorkduration).sum() / 7.4, 2));

        // Lock stamp: the latest paid_out among the month's swept items
        LocalDateTime lockedAt = null;
        for (Work w : vacationRows) lockedAt = maxDateTime(lockedAt, w.getPaidOut());
        for (Work w : hourlyRows) lockedAt = maxDateTime(lockedAt, w.getPaidOut());
        for (TransportationRegistration t : transportRows) lockedAt = maxDateTime(lockedAt, t.getPaidOut());
        for (Expense e : expenseRows) lockedAt = maxDateTime(lockedAt, e.getPaidOut());
        result.setLockedAt(lockedAt);

        result.setTotal(NumberUtils.round(result.getBaseSalary() + result.getSupplementsTotal()
                + result.getLumpSumsTotal() + result.getBonus() + result.getHourlyAmount()
                + result.getExpensesTotal(), 2));
        return result;
    }

    // ------------------------------------------------------------------
    // Phase 8 (tasks 8.4 + 8.6): row-level reach of salaries:read
    // ------------------------------------------------------------------

    /**
     * 403 unless the acting human (when present) may reach the target's salary data.
     *
     * <p>A self-read is never denied here: {@code decideSubjectAccess} allows an actor
     * their own subject at scope OWN without consulting a grant, which is what keeps an
     * employee's own Paycheck tab working when the implicit {@code USER} role leaves
     * V470's {@code salaries:read @ OWN} grant with no row to resolve from.
     */
    private void enforceSalariesReadScope(String targetUseruuid) {
        String actor = actorOrNull();
        if (actor == null) {
            return; // non-BFF caller — Phase 12
        }
        AuthorizationService.AccessDecision decision = authorizationService.decideSubjectAccess(
                actor, "salaries:read", targetUseruuid, LocalDate.now(), Set.of());
        if (!decision.allowed()) {
            throw salariesReadDenied(targetUseruuid);
        }
    }

    /** Whether the acting human is asking about their own salary records. */
    private static boolean isSelfRead(String actor, String targetUseruuid) {
        return actor != null && targetUseruuid != null && actor.trim().equals(targetUseruuid.trim());
    }

    /**
     * The acting human, or {@code null} when the caller carries no
     * {@code X-Requested-By} actor. The BFF always sends the header, so BFF traffic is
     * scoped; batch jobs and API clients keep pre-Phase-8 behaviour until Phase 12 — a
     * deliberate, findings-recorded fail-open. Anonymous flows never reach this
     * resource: no session, no actor header, and no salaries:read-scoped client
     * credential.
     */
    private String actorOrNull() {
        try {
            String actor = requestHeaderHolder.getUserUuid();
            return (actor == null || actor.isBlank()) ? null : actor;
        } catch (ContextNotActiveException e) {
            return null;
        }
    }

    private ForbiddenException salariesReadDenied(String targetUseruuid) {
        log.infof("salaries:read scope denied — target %s outside the acting user's reach", targetUseruuid);
        return new ForbiddenException("This employee's salary data is outside your reach");
    }

    private static PaycheckOverview.PaycheckWorkItem toWorkItem(Work work) {
        return new PaycheckOverview.PaycheckWorkItem(work.getRegistered(), work.getWorkduration(), work.getPaidOut());
    }

    private static PaycheckOverview.PaycheckExpenseItem toExpenseItem(Expense expense) {
        return new PaycheckOverview.PaycheckExpenseItem(expense.getUuid(), expense.getExpensedate(),
                expense.getDescription(), expense.getAmount() != null ? expense.getAmount() : 0,
                expense.getStatus(), expense.getPaidOut());
    }

    private static LocalDateTime maxDateTime(LocalDateTime current, LocalDateTime candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.isAfter(current)) return candidate;
        return current;
    }

    @POST
    @Path("/{useruuid}/salaries")
    @RolesAllowed({"salaries:write"})
    public void create(@PathParam("useruuid") String useruuid, Salary salary) {
        salaryService.createForUser(useruuid, salary);
    }

    @DELETE
    @Path("/{useruuid}/salaries/{salaryuuid}")
    @RolesAllowed({"salaries:write"})
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("salaryuuid") String salaryuuid) {
        salaryService.deleteForUser(useruuid, salaryuuid);
    }
}