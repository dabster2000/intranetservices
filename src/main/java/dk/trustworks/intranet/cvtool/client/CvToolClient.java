package dk.trustworks.intranet.cvtool.client;

import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;

import java.util.List;

/**
 * REST client for CV Tool API endpoints.
 * Authentication is via Cookie header (jwt_authorization=token).
 */
@Path("/cv")
@RegisterRestClient(configKey = "cvtool")
@RegisterProvider(ResteasyJackson2Provider.class)
public interface CvToolClient {

    @GET
    @Path("/employees")
    @Produces(MediaType.APPLICATION_JSON)
    List<CvToolEmployeeSkinny> getAllEmployees(
        @HeaderParam("Cookie") String cookieHeader
    );

    @GET
    @Path("/employee/{employeeId}")
    @Produces(MediaType.APPLICATION_JSON)
    CvToolEmployeeResponse getEmployee(
        @PathParam("employeeId") int employeeId,
        @HeaderParam("Cookie") String cookieHeader
    );
}
