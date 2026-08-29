package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.dto.itbudget.CreateItExpenseRequest;
import dk.trustworks.intranet.dto.itbudget.ItExpenseItemDTO;
import dk.trustworks.intranet.dto.itbudget.UpdateItExpenseRequest;
import dk.trustworks.intranet.services.ItExpenseService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * A user's registered IT equipment ({@code itbudget}). The equipment belongs to
 * the user in the path, and that path is the only thing that decides ownership:
 * the mutation bodies are record DTOs carrying no {@code useruuid} and no id, so
 * neither is reachable from the wire. Update and delete address a single row
 * through {@code /{id}} and the service refuses one that belongs to somebody
 * else.
 */
@JBossLog
@ApplicationScoped
@Path("/users/{useruuid}/itexpense")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"devices:read"})
@SecurityRequirement(name = "jwt")
public class ItExpenseResource {

    @Inject
    ItExpenseService expenseService;

    @GET
    public List<ItExpenseItemDTO> findAllByUseruuid(@PathParam("useruuid") String useruuid) {
        return expenseService.findExpensesByUseruuid(useruuid);
    }

    @POST
    @RolesAllowed({"devices:write"})
    public Response saveExpense(@PathParam("useruuid") String useruuid, CreateItExpenseRequest request) {
        ItExpenseItemDTO created = expenseService.createExpense(useruuid, request);
        return Response
                .created(URI.create("/users/" + useruuid + "/itexpense/" + created.id()))
                .entity(created)
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"devices:write"})
    public ItExpenseItemDTO updateExpense(@PathParam("useruuid") String useruuid,
                                          @PathParam("id") int id,
                                          UpdateItExpenseRequest request) {
        return expenseService.updateExpense(useruuid, id, request);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed({"devices:write"})
    public void deleteExpense(@PathParam("useruuid") String useruuid, @PathParam("id") int id) {
        expenseService.deleteExpense(useruuid, id);
    }
}
