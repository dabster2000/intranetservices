package dk.trustworks.intranet.aggregates.utilization.resources;

import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.BudgetFulfillmentDTO;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
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

    /**
     * Retrieves budget fulfillment metrics for a consultant over a specified period.
     * The metrics include monthly aggregated data such as net available hours, budget hours,
     * registered billable hours, and utilization ratios (e.g., budget utilization, actual utilization,
     * and budget fulfillment percentage). This provides insights into how well the consultant
     * performed compared to their contracted targets.
     *
     * @param useruuid The UUID of the consultant to retrieve metrics for.
     * @param fromdate The start date of the period (inclusive) in YYYY-MM-DD format.
     * @param todate The end date of the period (exclusive) in YYYY-MM-DD format.
     * @return A list of BudgetFulfillmentDTO objects containing budget fulfillment metrics
     *         for each month within the specified period.
     */
    @GET
    @Path("/{useruuid}/utilization/fulfillment")
    @Operation(
            summary = "Get budget fulfillment metrics for a consultant",
            description = "Calculates comprehensive budget fulfillment metrics for a consultant over a specified period. " +
                    "Returns monthly aggregated data including net available hours, budget hours, registered billable hours, " +
                    "and calculated utilization ratios (budget utilization, actual utilization, and budget fulfillment percentage). " +
                    "Budget fulfillment shows how well the consultant delivered against their contracted targets."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Successfully retrieved budget fulfillment metrics",
                    content = @Content(
                            mediaType = APPLICATION_JSON,
                            schema = @Schema(implementation = BudgetFulfillmentDTO.class)
                    )
            ),
            @APIResponse(
                    responseCode = "401",
                    description = "Unauthorized - Invalid or missing JWT token"
            ),
            @APIResponse(
                    responseCode = "403",
                    description = "Forbidden - Insufficient permissions"
            )
    })
    public List<BudgetFulfillmentDTO> getBudgetFulfillmentByConsultant(
            @Parameter(
                    description = "UUID of the consultant to retrieve metrics for",
                    required = true,
                    example = "a0a7c53e-3f69-4c9a-a41c-ae30a4f1ccda"
            )
            @PathParam("useruuid") String useruuid,
            @Parameter(
                    description = "Start date of the period (inclusive) in YYYY-MM-DD format",
                    required = true,
                    example = "2024-07-01"
            )
            @QueryParam("fromdate") String fromdate,
            @Parameter(
                    description = "End date of the period (exclusive) in YYYY-MM-DD format",
                    required = true,
                    example = "2025-01-01"
            )
            @QueryParam("todate") String todate) {
        LocalDate fromDate = DateUtils.dateIt(fromdate);
        LocalDate toDate = DateUtils.dateIt(todate);

        return utilizationService.calculateBudgetFulfillmentByConsultant(useruuid, fromDate, toDate);
    }

}
