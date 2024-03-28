package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "Consultant Utilization")
@JBossLog
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserUtilizationResource {

    @Inject
    BudgetService budgetService;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    WorkService workService;

    @Inject
    UtilizationService utilizationService;

    @Inject
    EntityManager em;

    @GET
    @Path("/{useruuid}/utilization/budgets")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = DateUtils.dateIt(fromdate);
        LocalDate toDate = DateUtils.dateIt(todate);

        List<EmployeeAvailabilityPerMonth> availabilityPerMonths = availabilityService.getEmployeeDataPerMonth(useruuid, fromDate, toDate);
        List<DateValueDTO> budgets = budgetService.getBudgetHoursByPeriodAndSingleConsultant(useruuid, fromDate, toDate);

        List<DateValueDTO> results = new ArrayList<>();

        do {
            LocalDate finalFromDate = fromDate;
            budgets.stream().filter(b -> b.getDate().getYear() == finalFromDate.getYear() && b.getDate().getMonthValue() == finalFromDate.getMonthValue()).findFirst().ifPresentOrElse(results::add, () -> results.add(new DateValueDTO(finalFromDate, 0.0)));
            fromDate = fromDate.plusMonths(1);
        } while (!fromDate.isAfter(toDate));

        for (DateValueDTO value : results) {
            availabilityPerMonths.stream().filter(a -> a.getYear() == value.getDate().getYear() && a.getMonth() == value.getDate().getMonthValue()).findFirst().ifPresentOrElse(a -> value.setValue(value.getValue() / a.getNetAvailableHours()), () -> value.setValue(0.0));
        }
        return results;
    }


    @GET
    @Path("/{useruuid}/utilization/actuals")
    public List<DateValueDTO> getActualUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = DateUtils.dateIt(fromdate);
        LocalDate toDate = DateUtils.dateIt(todate);

        List<EmployeeAvailabilityPerMonth> availabilityPerMonths = availabilityService.getEmployeeDataPerMonth(useruuid, fromDate, toDate);
        List<DateValueDTO> hoursPerMonth = workService.findWorkHoursByUserAndPeriod(useruuid, fromDate, toDate);


        List<DateValueDTO> results = new ArrayList<>();

        do {
            LocalDate finalFromDate = fromDate;
            hoursPerMonth.stream().filter(b -> b.getDate().getYear() == finalFromDate.getYear() && b.getDate().getMonthValue() == finalFromDate.getMonthValue()).findFirst().ifPresentOrElse(results::add, () -> results.add(new DateValueDTO(finalFromDate, 0.0)));
            fromDate = fromDate.plusMonths(1);
        } while (!fromDate.isAfter(toDate));

        for (DateValueDTO value : results) {
            availabilityPerMonths.stream().filter(a -> a.getYear() == value.getDate().getYear() && a.getMonth() == value.getDate().getMonthValue()).findFirst().ifPresentOrElse(a -> value.setValue(value.getValue() / a.getNetAvailableHours()), () -> value.setValue(0.0));
        }
        return results;
    }

}
