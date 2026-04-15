package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * Q2C API v5.1.0 REST client for draft invoice lifecycle operations.
 *
 * <p>Auth tokens are passed per-call via {@code X-AppSecretToken} and
 * {@code X-AgreementGrantToken} headers — required for multi-agreement support
 * where callers select the agreement at invocation time.
 *
 * <p>Base URL: {@code https://apis.e-conomic.com/q2capi/v5.1.0}
 *
 * SPEC-INV-001 §6.1.
 */
@RegisterRestClient(configKey = "economics-q2c-api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsDraftInvoiceApiClient {

    @POST
    @Path("/invoices/drafts")
    EconomicsDraftInvoice create(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @HeaderParam("Idempotency-Key") String idempotencyKey,
            EconomicsDraftInvoice body);

    @PUT
    @Path("/invoices/drafts/{draftInvoiceNumber}")
    EconomicsDraftInvoice update(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("draftInvoiceNumber") int draftInvoiceNumber,
            EconomicsDraftInvoice body);

    @DELETE
    @Path("/invoices/drafts/{draftInvoiceNumber}")
    void delete(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("draftInvoiceNumber") int draftInvoiceNumber);

    @POST
    @Path("/invoices/drafts/{documentId}/lines/bulk")
    List<EconomicsDraftLine> createLinesBulk(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("documentId") int documentId,
            List<EconomicsDraftLine> lines);

    @GET
    @Path("/invoices/drafts")
    EconomicsDraftPage listByFilter(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @QueryParam("filter") String filter,
            @QueryParam("pagesize") Integer pagesize);
}
