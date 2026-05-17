package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseDecisionsResponseDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseDecisionsService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalDate;
import java.util.List;

@Path("/admin/expense-decisions")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:write"})
public class ExpenseDecisionsResource {

    private final ExpenseDecisionsService service;

    public ExpenseDecisionsResource(ExpenseDecisionsService service) {
        this.service = service;
    }

    @GET
    public ExpenseDecisionsResponseDTO list(
            @QueryParam("from")     LocalDate from,
            @QueryParam("to")       LocalDate to,
            @QueryParam("outcome")  List<String> outcomes,
            @QueryParam("employee") String employeeUuid,
            @QueryParam("limit")    @DefaultValue("50")  int limit,
            @QueryParam("offset")   @DefaultValue("0")   int offset) {
        if (limit > 200) limit = 200;
        if (from == null) from = LocalDate.now().minusDays(7);
        if (to == null)   to   = LocalDate.now();
        return service.list(from, to, outcomes, employeeUuid, limit, offset);
    }
}
