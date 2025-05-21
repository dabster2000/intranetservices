package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.dto.SalaryPayment;
import dk.trustworks.intranet.aggregates.users.services.*;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.TransportationRegistration;
import dk.trustworks.intranet.userservice.model.VacationPool;
import dk.trustworks.intranet.userservice.model.enums.SalaryType;
import dk.trustworks.intranet.utils.NumberUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.WORK_HOURS;
import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.NumberUtils.convertDoubleToInt;
import static dk.trustworks.intranet.utils.NumberUtils.formatCurrency;

@Tag(name = "user")
@Path("/users")
@RequestScoped
@JBossLog
@RolesAllowed({"SYSTEM"})
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
    AggregateEventSender aggregateEventSender;


    @GET
    @Path("/{useruuid}/salaries")
    public List<Salary> listAll(@PathParam("useruuid") String useruuid) {
        return salaryService.findByUseruuid(useruuid);
    }

    @GET
    @Path("/{useruuid}/salaries/payments/{month}")
    public List<SalaryPayment> listPayments(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
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
            if (baseSalary.getType().equals(SalaryType.NORMAL)) {
                if (availability != null && availability.getGrossAvailableHours().doubleValue() > availability.getSalaryAwardingHours()) {
                    double adjustedSalary = baseSalary.calculateMonthNormAdjustedSalary(
                            availability.getGrossAvailableHours().doubleValue() / 7.4,
                            availability.getSalaryAwardingHours() / 7.4
                    );
                    baseSalary.setSalary(convertDoubleToInt(adjustedSalary));
                }
                payments.add(new SalaryPayment(date, "Base salary", formatCurrency(baseSalary.getSalary())));
            } else if (baseSalary.getType().equals(SalaryType.HOURLY)) {
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
            VacationPool vacationPool = vacationService.calculateRemainingVacationDays(useruuid);
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

    @POST
    @Path("/{useruuid}/salaries")
    public void create(@PathParam("useruuid") String useruuid, Salary salary) {
        salary.setUseruuid(useruuid);
        salaryService.create(salary);
    }

    @DELETE
    @Path("/{useruuid}/salaries/{salaryuuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("salaryuuid") String salaryuuid) {
        salaryService.delete(salaryuuid);
    }
}