package dk.trustworks.intranet.utils.client;

import dk.trustworks.intranet.utils.dto.addo.AddoLoginRequest;
import dk.trustworks.intranet.utils.dto.addo.AddoLoginResponse;
import dk.trustworks.intranet.utils.dto.addo.InitiateSigningRequest;
import dk.trustworks.intranet.utils.dto.addo.InitiateSigningResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

/**
 * MicroProfile REST Client for ADDO Sign API.
 * Provides methods to authenticate and initiate digital signing workflows.
 *
 * @see <a href="https://addosign.net/WebService/v2.0/RestSigningService.svc">ADDO API</a>
 */
@Path("/")
@RegisterRestClient(configKey = "addo")
@RegisterProvider(ResteasyJackson2Provider.class)
public interface AddoClient {

    /**
     * Authenticates with ADDO API and returns a session token.
     *
     * @param request Login credentials (email and SHA-512 hashed password)
     * @return Login response containing authentication token
     */
    @POST
    @Path("/Login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    AddoLoginResponse login(AddoLoginRequest request);

    /**
     * Initiates a new digital signing workflow for a document.
     *
     * @param request Signing request containing document, recipients, and configuration
     * @return Signing response containing signing token for tracking
     */
    @POST
    @Path("/InitiateSigning")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    InitiateSigningResponse initiateSigning(InitiateSigningRequest request);
}
