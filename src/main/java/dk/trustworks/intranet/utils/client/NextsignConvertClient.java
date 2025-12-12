package dk.trustworks.intranet.utils.client;

import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertRequest;
import dk.trustworks.intranet.utils.dto.nextsign.DocumentConvertResponse;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider;

/**
 * MicroProfile REST Client for NextSign Document Convert API v1.
 * Converts Word documents (DOCX) to PDF format with tag replacement.
 *
 * <p>This client handles the /api/v1/document/convert endpoint which:
 * <ul>
 *   <li>Accepts DOCX documents with {{placeholder}} tags</li>
 *   <li>Replaces tags with provided values</li>
 *   <li>Converts the document to PDF format</li>
 *   <li>Returns URL to the converted PDF</li>
 * </ul>
 *
 * <p>Configuration in application.properties:
 * <pre>
 * quarkus.rest-client.nextsign-convert.url=https://www.nextsign.dk
 * quarkus.rest-client.nextsign-convert.connect-timeout=5000
 * quarkus.rest-client.nextsign-convert.read-timeout=90000
 * </pre>
 *
 * <p>Note: Document conversion can take up to 90 seconds for complex documents.
 * DOCX to PDF conversion typically takes ~5 seconds.
 *
 * @see DocumentConvertRequest
 * @see DocumentConvertResponse
 * @see <a href="https://www.nextsign.dk/api/v1/document/convert">NextSign Document Convert API</a>
 */
@Path("/api/v1/document")
@RegisterRestClient(configKey = "nextsign-convert")
@RegisterProvider(ResteasyJackson2Provider.class)
@RegisterProvider(NextsignResponseExceptionMapper.class)
@RegisterProvider(NextsignLoggingFilter.class)
public interface NextsignConvertClient {

    /**
     * Converts documents to PDF format with tag replacement.
     *
     * <p>The request must contain:
     * <ul>
     *   <li>documents: List of document URLs (DOCX or PDF files)</li>
     *   <li>tags: List of tag-value pairs for placeholder replacement</li>
     * </ul>
     *
     * <p>Important notes:
     * <ul>
     *   <li>Documents must be accessible via public URL or presigned URL</li>
     *   <li>Recommended to keep documents under 5 per request</li>
     *   <li>Conversion times: ~0.1s for PDF, ~5s for DOCX, max 90s per request</li>
     *   <li>Tag names in documents must match exactly (case-sensitive)</li>
     * </ul>
     *
     * @param bearerToken Authorization token (format: "Bearer {token}")
     * @param request Conversion request with documents and tags
     * @return Response containing URLs to converted PDF documents
     * @throws jakarta.ws.rs.WebApplicationException if conversion fails
     */
    @POST
    @Path("/convert")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    DocumentConvertResponse convert(
        @HeaderParam("Authorization") String bearerToken,
        DocumentConvertRequest request
    );
}
