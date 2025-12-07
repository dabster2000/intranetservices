package dk.trustworks.intranet.utils.client;

import dk.trustworks.intranet.utils.dto.nextsign.GetPresignedUrlRequest;
import dk.trustworks.intranet.utils.dto.nextsign.GetPresignedUrlResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

/**
 * MicroProfile REST Client for NextSign Document API v3.
 * Handles document retrieval and presigned URL generation.
 *
 * <p>This client uses a different base URL than the v2 API:
 * <ul>
 *   <li>v2 API (case management): https://www.nextsign.dk/api/v2</li>
 *   <li>v3 API (document retrieval): https://api.nextsign.dk/v3</li>
 * </ul>
 *
 * @see NextsignClient for v2 case management API
 * @see <a href="https://api.nextsign.dk">NextSign API v3</a>
 */
@Path("/v3/company")
@RegisterRestClient(configKey = "nextsign-document")
@RegisterProvider(ResteasyJackson2Provider.class)
@RegisterProvider(NextsignResponseExceptionMapper.class)
@RegisterProvider(NextsignLoggingFilter.class)
public interface NextsignDocumentClient {

    /**
     * Gets a pre-signed URL for downloading a signed document.
     *
     * <p>The presigned URL allows temporary access (1 hour) to download
     * the signed document without requiring authentication. This is used
     * to retrieve completed signed documents for storage or display.
     *
     * @param company Company identifier from NextSign dashboard
     * @param bearerToken Authorization bearer token (format: "Bearer {token}")
     * @param request Document URL from signedDocuments[].document_id
     * @return Response containing presigned download URL
     */
    @POST
    @Path("/{company}/file/view-presigned-url")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    GetPresignedUrlResponse getPresignedUrl(
        @PathParam("company") String company,
        @HeaderParam("Authorization") String bearerToken,
        GetPresignedUrlRequest request
    );
}
