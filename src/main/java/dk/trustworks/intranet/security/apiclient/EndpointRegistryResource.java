package dk.trustworks.intranet.security.apiclient;

import dk.trustworks.intranet.security.apiclient.dto.EndpointRegistryEntry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Read-only REST resource that exposes the cached endpoint registry.
 * Returns all discovered JAX-RS endpoints with their security annotations
 * and derived domain classification.
 */
@Path("/auth/endpoint-registry")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:*"})
@JBossLog
public class EndpointRegistryResource {

    @Inject
    EndpointRegistryService endpointRegistryService;

    @GET
    public Response getAll() {
        List<EndpointRegistryEntry> entries = endpointRegistryService.getEndpoints();
        return Response.ok(entries).build();
    }
}
