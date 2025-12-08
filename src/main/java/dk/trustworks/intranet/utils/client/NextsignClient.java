package dk.trustworks.intranet.utils.client;

import dk.trustworks.intranet.utils.dto.nextsign.CreateCaseRequest;
import dk.trustworks.intranet.utils.dto.nextsign.CreateCaseResponse;
import dk.trustworks.intranet.utils.dto.nextsign.GetCaseStatusResponse;
import dk.trustworks.intranet.utils.dto.nextsign.ListCasesResponse;
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
     * @param caseId The NextSign MongoDB _id returned from createCase (NOT nextSignKey)
     * @return Response containing the case status and signer information
     */
    @GET
    @Path("/{company}/case/{caseId}/get")
    @Produces(MediaType.APPLICATION_JSON)
    GetCaseStatusResponse getCaseStatus(
        @PathParam("company") String company,
        @HeaderParam("Authorization") String bearerToken,
        @PathParam("caseId") String caseId
    );

    /**
     * Lists signing cases with optional filtering.
     * Supports pagination and filtering by status, folder, etc.
     *
     * @param company Company identifier from Nextsign dashboard
     * @param bearerToken Authorization token (format: "Bearer {token}")
     * @param status Filter by status (optional, e.g., "open", "completed")
     * @param folder Filter by folder (optional)
     * @param limit Page size (default: 50, max: 100)
     * @param index Page offset (default: 0)
     * @return Response containing list of case summaries with pagination info
     */
    @GET
    @Path("/{company}/cases/get")
    @Produces(MediaType.APPLICATION_JSON)
    ListCasesResponse listCases(
        @PathParam("company") String company,
        @HeaderParam("Authorization") String bearerToken,
        @QueryParam("status") String status,
        @QueryParam("folder") String folder,
        @QueryParam("limit") @DefaultValue("50") int limit,
        @QueryParam("index") @DefaultValue("0") int index
    );
}
