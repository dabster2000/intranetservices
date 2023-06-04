package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.ItExpenseItem;
import dk.trustworks.intranet.services.ItExpenseService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

@JBossLog
@ApplicationScoped
//@Tag(name = "ItBudget")
@Path("/users/{useruuid}/itexpense")
@RolesAllowed({"SYSTEM", "USER"})
@SecurityRequirement(name = "jwt")
public class ItExpenseResource {

    @Inject
    ItExpenseService expenseService;

    @GET
    public List<ItExpenseItem> findAllByUseruuid(@PathParam("useruuid") String userUuid) {
        return expenseService.findExpensesByUseruuid(userUuid);
    }

    @POST
    public void saveExpense(@PathParam("useruuid") String userUuid, ItExpenseItem itExpenseItem) {
        expenseService.saveExpense(itExpenseItem);
    }

    @PUT
    public void updateExpense(@PathParam("useruuid") String userUuid, ItExpenseItem itExpenseItem) {
        expenseService.updateExpense(itExpenseItem);
    }

    @DELETE
    @Path("/{id}")
    public void deleteExpense(@PathParam("id") int id) {
        expenseService.deleteExpense(id);
    }
}
