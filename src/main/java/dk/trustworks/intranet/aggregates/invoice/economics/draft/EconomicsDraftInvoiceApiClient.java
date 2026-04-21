package dk.trustworks.intranet.aggregates.invoice.economics.draft;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
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

    /**
     * POST /invoices/drafts returns the Q2C {@code CreatedResult} envelope
     * ({@code {"number": int}}), NOT the full draft. Callers that need other
     * fields must GET {@code /invoices/drafts/{number}}.
     */
    @POST
    @Path("/invoices/drafts")
    CreatedResult create(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @HeaderParam("Idempotency-Key") String idempotencyKey,
            EconomicsDraftInvoice body);

    /**
     * GET /invoices/drafts/{number} returns the full {@link EconomicsDraftInvoice},
     * including {@code draftInvoiceNumber} — the number displayed in the UI and
     * required by the legacy REST booking endpoint. {@link CreatedResult#getNumber()}
     * returned by POST is the Q2C internal identifier, which is NOT the same as
     * {@code draftInvoiceNumber} for a given draft; callers that need to book the
     * draft MUST resolve {@code number → draftInvoiceNumber} via this endpoint.
     */
    @GET
    @Path("/invoices/drafts/{number}")
    EconomicsDraftInvoice getByNumber(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("number") int number);

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

    /**
     * POST /invoices/drafts/{id}/lines/bulk requires a wrapping object
     * ({@code {"lines": [...]}}); a bare array yields 400 InvalidInput.
     * Returns {@code {"numbers": [int, ...]}}, NOT the full lines.
     * We don't currently use the line numbers — declared as {@code void}.
     */
    @POST
    @Path("/invoices/drafts/{documentId}/lines/bulk")
    void createLinesBulk(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("documentId") int documentId,
            DraftInvoiceLineBatchRequest body);

    @GET
    @Path("/invoices/drafts")
    EconomicsDraftPage listByFilter(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @QueryParam("filter") String filter,
            @QueryParam("pagesize") Integer pagesize);
}
