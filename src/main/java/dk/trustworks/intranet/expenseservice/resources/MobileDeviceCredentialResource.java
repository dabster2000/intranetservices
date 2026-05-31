package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.CreateDeviceCredentialRequest;
import dk.trustworks.intranet.expenseservice.dto.DeviceCredentialDTO;
import dk.trustworks.intranet.expenseservice.dto.UpdateCounterRequest;
import dk.trustworks.intranet.expenseservice.services.MobileDeviceCredentialService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/expenses/mobile/device-credentials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MobileDeviceCredentialResource {

    @Inject
    MobileDeviceCredentialService service;

    @GET
    @RolesAllowed({"expenses:read"})
    public Map<String, List<DeviceCredentialDTO>> list(@QueryParam("userUuid") String userUuid) {
        return Map.of("credentials", service.listActiveForUser(userUuid));
    }

    @GET
    @Path("/{credentialId}")
    @RolesAllowed({"expenses:read"})
    public Response fetch(@PathParam("credentialId") String credentialId) {
        return service.findByCredentialId(credentialId)
            .map(dto -> Response.ok(dto).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @RolesAllowed({"expenses:write"})
    public Response create(@Valid CreateDeviceCredentialRequest req) {
        return Response.status(Response.Status.CREATED).entity(service.create(req)).build();
    }

    @PUT
    @Path("/{credentialId}/counter")
    @RolesAllowed({"expenses:write"})
    public Response updateCounter(@PathParam("credentialId") String credentialId,
                                  @Valid UpdateCounterRequest req) {
        return service.updateCounter(credentialId, req.signCount())
            .map(dto -> Response.ok(dto).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @DELETE
    @Path("/{credentialId}")
    @RolesAllowed({"expenses:write"})
    public Response revoke(@PathParam("credentialId") String credentialId) {
        return service.revoke(credentialId)
            ? Response.noContent().build()
            : Response.status(Response.Status.NOT_FOUND).build();
    }
}
