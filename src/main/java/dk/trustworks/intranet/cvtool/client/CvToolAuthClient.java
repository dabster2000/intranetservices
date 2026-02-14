package dk.trustworks.intranet.cvtool.client;

import dk.trustworks.intranet.cvtool.dto.CvToolTokenResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;

/**
 * REST client for CV Tool authentication.
 * Uses form-encoded login to get a JWT token.
 */
@Path("/auth")
@RegisterRestClient(configKey = "cvtool")
@RegisterProvider(ResteasyJackson2Provider.class)
public interface CvToolAuthClient {

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    CvToolTokenResponse login(
        @FormParam("username") String username,
        @FormParam("password") String password
    );
}
