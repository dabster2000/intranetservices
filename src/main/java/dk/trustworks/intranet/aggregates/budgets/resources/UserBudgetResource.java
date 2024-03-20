package dk.trustworks.intranet.aggregates.budgets.resources;

import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerMonth;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.dto.DateValueDTO;
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

@Tag(name = "User Budgets")
@JBossLog
@Path("/users")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserBudgetResource {

    @Inject
    BudgetService budgetService;

    @GET
    @Path("/budgets")
    public List<EmployeeBudgetPerMonth> getAllUserBudgetsByPeriod(@QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return budgetService.getBudgetDataByPeriod(dateIt(periodFrom), dateIt(periodTo));
    }

    @GET
    @Path("/{useruuid}/budgets")
    public List<EmployeeBudgetPerMonth> getBudgetsByPeriodAndSingleConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String periodFrom, @QueryParam("todate") String periodTo) {
        return budgetService.getBudgetDataByUserAndPeriod(useruuid, dateIt(periodFrom), dateIt(periodTo));
    }

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
}
