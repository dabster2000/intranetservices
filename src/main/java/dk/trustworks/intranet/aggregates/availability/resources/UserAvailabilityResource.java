package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@Tag(name = "User Availabilities")
@JBossLog
@Path("/users")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserAvailabilityResource {

    @Inject
    AvailabilityService availabilityService;

    @GET
    @Path("/availabilities")
    public List<EmployeeAvailabilityPerMonth> getAllUserAvailabilitiesByPeriod(@QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getAllEmployeeAvailabilityByPeriod(dateIt(periodFrom), dateIt(periodTo));
    }

    @GET
    @Path("/{useruuid}/availabilities")
    public List<EmployeeAvailabilityPerMonth> getBudgetsByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return availabilityService.getEmployeeDataPerMonth(useruuid, dateIt(periodFrom), dateIt(periodTo));
    }
/*
    @GET
    @Path("/{useruuid}/budgets/amount")
    public List<DateValueDTO> getBudgetRevenueByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("periodFrom") String periodFrom, @QueryParam("periodTo") String periodTo) {
        return budgetService.getBudgetAmountByPeriodAndSingleConsultant(useruuid, dateIt(periodFrom), dateIt(periodTo));
    }

    @GET
    @Path("/{useruuid}/budgets/months/{month}")
    public List<EmployeeBudgetPerMonth> getBudgetsBySingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        return budgetService.getConsultantBudgetDataByMonth(useruuid, dateIt(month));
    }

    @GET
    @Path("/{useruuid}/budgets/months/{month}/amount")
    public DateValueDTO getBudgetAmountForSingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        return budgetService.getBudgetAmountForSingleMonthAndSingleConsultant(useruuid, dateIt(month));
    }

    @GET
    @Path("/{useruuid}/budgets/months/{month}/hours")
    public DateValueDTO getBudgetHoursForSingleMonthAndSingleConsultant(@PathParam("useruuid") String useruuid, @PathParam("month") String month) {
        return new DateValueDTO(dateIt(month), budgetService.getConsultantBudgetHoursByMonth(useruuid, dateIt(month)));
    }

     */
}
