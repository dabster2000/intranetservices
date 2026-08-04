package dk.trustworks.intranet.cvtool.client;

import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeResponse;
import dk.trustworks.intranet.cvtool.dto.CvToolEmployeeSkinny;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;

import java.util.List;

/**
 * REST client for CV Tool API endpoints, served through Azure API Management.
 *
 * <p>Authentication is the {@code Ocp-Apim-Subscription-Key} header injected by
 * {@link CvToolHeadersFactory} — there is no login call.
 */
@Path("/cv")
@RegisterRestClient(configKey = "cvtool")
@RegisterClientHeaders(CvToolHeadersFactory.class)
@RegisterProvider(ResteasyJackson2Provider.class)
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
public interface CvToolClient {

    @GET
    @Path("/employees")
    @Produces(MediaType.APPLICATION_JSON)
    List<CvToolEmployeeSkinny> getAllEmployees();

    @GET
    @Path("/employee/{employeeId}")
    @Produces(MediaType.APPLICATION_JSON)
    CvToolEmployeeResponse getEmployee(@PathParam("employeeId") int employeeId);
}
