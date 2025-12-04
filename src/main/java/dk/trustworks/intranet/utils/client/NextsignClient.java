package dk.trustworks.intranet.utils.client;

import dk.trustworks.intranet.utils.dto.nextsign.CreateCaseRequest;
import dk.trustworks.intranet.utils.dto.nextsign.CreateCaseResponse;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

/**
 * MicroProfile REST Client for Nextsign API.
 * Provides methods to create and manage digital signing cases.
 *
 * @see <a href="https://www.nextsign.dk/api/v2">Nextsign API v2</a>
 */
@Path("/api/v2")
@RegisterRestClient(configKey = "nextsign")
@RegisterProvider(ResteasyJackson2Provider.class)
@RegisterProvider(NextsignResponseExceptionMapper.class)
@RegisterProvider(NextsignLoggingFilter.class)
public interface NextsignClient {

    /**
     * Creates a new signing case with documents and recipients.
     *
     * @param company Company identifier from Nextsign dashboard
     * @param bearerToken Authorization token (format: "Bearer {token}")
     * @param request Signing case request with documents and recipients
     * @return Response containing the case key for tracking
     */
    @POST
    @Path("/{company}/case/create")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CreateCaseResponse createCase(
        @PathParam("company") String company,
        @HeaderParam("Authorization") String bearerToken,
        CreateCaseRequest request
    );

    /**
     * Retrieves the status of an existing signing case.
     *
     * @param company Company identifier from Nextsign dashboard
     * @param bearerToken Authorization token (format: "Bearer {token}")
     * @param caseKey The NextSign case key returned from createCase
     * @return Response containing the case status and signer information
     */
    @GET
    @Path("/{company}/case/{caseKey}")
    @Produces(MediaType.APPLICATION_JSON)
    GetCaseStatusResponse getCaseStatus(
        @PathParam("company") String company,
        @HeaderParam("Authorization") String bearerToken,
        @PathParam("caseKey") String caseKey
    );
}
