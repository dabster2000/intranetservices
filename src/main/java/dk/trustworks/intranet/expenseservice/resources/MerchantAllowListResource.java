package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.AddAllowListEntryRequest;
import dk.trustworks.intranet.expenseservice.dto.MerchantAllowListEntryDTO;
import dk.trustworks.intranet.expenseservice.services.MerchantAllowListService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/admin/merchant-allow-list")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:write"})
public class MerchantAllowListResource {

    @Inject
    MerchantAllowListService service;

    @GET
    public Map<String, List<MerchantAllowListEntryDTO>> list() {
        return Map.of("entries", service.list());
    }

    @POST
    public Response add(@Valid AddAllowListEntryRequest req) {
        return Response.status(Response.Status.CREATED).entity(service.add(req)).build();
    }

    @DELETE
    @Path("/{uuid}")
    public Response delete(@PathParam("uuid") String uuid) {
        return service.delete(uuid)
            ? Response.noContent().build()
            : Response.status(Response.Status.NOT_FOUND).build();
    }
}
