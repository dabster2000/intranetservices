package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.dto.SalaryPayment;
import dk.trustworks.intranet.aggregates.users.events.CreateSalaryEvent;
import dk.trustworks.intranet.aggregates.users.events.DeleteSalaryEvent;
import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.SalarySupplementService;
import dk.trustworks.intranet.aggregates.users.services.TransportationRegistrationService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.TransportationRegistration;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
        LocalDate date = dateIt(month).withDayOfMonth(1);

        EmployeeAvailabilityPerMonth availability = availabilityService.getEmployeeDataPerMonth(useruuid, date, date.plusMonths(1)).getFirst();

        Salary baseSalary = salaryService.getUserSalaryByMonth(useruuid, date);
        if(availability.getGrossAvailableHours().doubleValue()>availability.getSalaryAwardingHours())
            baseSalary.setSalary(convertDoubleToInt(
                    baseSalary.calculateMonthNormAdjustedSalary(availability.getGrossAvailableHours().doubleValue()/7.4, availability.getSalaryAwardingHours()/7.4)));
        payments.add(new SalaryPayment(date, "Base salary", formatCurrency(baseSalary.getSalary())));

        salarySupplementService.findByUseruuidAndMonth(useruuid, date).forEach(salarySupplement -> {
            payments.add(new SalaryPayment(date, salarySupplement.getDescription(), formatCurrency(salarySupplement.getValue())));
        });

        salaryLumpSumService.findByUseruuidAndMonth(useruuid, date).forEach(salaryLumpSum -> {
            payments.add(new SalaryPayment(date, salaryLumpSum.getDescription(), formatCurrency(salaryLumpSum.getLumpSum())));
        });

        double vacation = workService.calculateVacationByUserInMonth(useruuid, DateUtils.getTwentieth(date), DateUtils.getTwentieth(date.plusMonths(1)));
        payments.add(new SalaryPayment(date, "Vacation", vacation + " hours"));

        int kilometers = transportationRegistrationService.findByUseruuidAndUnpaid(useruuid).stream().mapToInt(TransportationRegistration::getKilometers).sum();
        if(kilometers>0) payments.add(new SalaryPayment(date, "Transportation", kilometers + " km"));

        double expenseSum = expenseService.findByUserAndUnpaid(useruuid).stream().mapToDouble(Expense::getAmount).sum();
        if(expenseSum>0) payments.add(new SalaryPayment(date, "Expenses", formatCurrency(expenseSum)));

        return payments;
    }

    @POST
    @Path("/{useruuid}/salaries")
    public void create(@PathParam("useruuid") String useruuid, Salary salary) {
        salary.setUseruuid(useruuid);
        salaryService.create(salary);
        CreateSalaryEvent createSalaryEvent = new CreateSalaryEvent(useruuid, salary);
        aggregateEventSender.handleEvent(createSalaryEvent);
    }

    @DELETE
    @Path("/{useruuid}/salaries/{salaryuuid}")
    public void delete(@PathParam("useruuid") String useruuid, @PathParam("salaryuuid") String salaryuuid) {
        salaryService.delete(salaryuuid);
        DeleteSalaryEvent deleteSalaryEvent = new DeleteSalaryEvent(useruuid, salaryuuid);
        aggregateEventSender.handleEvent(deleteSalaryEvent);
    }
}