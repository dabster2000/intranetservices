package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
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
    WorkService workService;

    @Inject
    UtilizationService utilizationService;

    @GET
    @Path("/{useruuid}/utilization/budgets")
    public List<DateValueDTO> getBudgetUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = DateUtils.dateIt(fromdate);
        LocalDate toDate = DateUtils.dateIt(todate);

        return utilizationService.calculateActualUtilizationPerMonthByConsultant(useruuid, fromDate, toDate, budgetService.getBudgetHoursByPeriodAndSingleConsultant(useruuid, fromDate, toDate));
    }


    @GET
    @Path("/{useruuid}/utilization/actuals")
    public List<DateValueDTO> getActualUtilizationPerMonthByConsultant(@PathParam("useruuid") String useruuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        LocalDate fromDate = DateUtils.dateIt(fromdate);
        LocalDate toDate = DateUtils.dateIt(todate);

        return utilizationService.calculateActualUtilizationPerMonthByConsultant(useruuid, fromDate, toDate, workService.findWorkHoursByUserAndPeriod(useruuid, fromDate, toDate));
    }



}
