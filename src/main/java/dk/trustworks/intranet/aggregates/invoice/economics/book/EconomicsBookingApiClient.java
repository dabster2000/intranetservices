package dk.trustworks.intranet.aggregates.invoice.economics.book;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.io.InputStream;

@RegisterRestClient(configKey = "economics-legacy-rest")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsBookingApiClient {

    @POST @Path("/invoices/booked")
    EconomicsBookedInvoice book(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @HeaderParam("Idempotency-Key") String idempotencyKey,
            EconomicsBookingRequest body);

    @GET @Path("/invoices/booked/{bookedInvoiceNumber}")
    EconomicsBookedInvoice getBooked(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("bookedInvoiceNumber") int bookedInvoiceNumber);

    @GET @Path("/invoices/booked/{bookedInvoiceNumber}/pdf")
    @Produces("application/pdf")
    InputStream getBookedPdf(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("bookedInvoiceNumber") int bookedInvoiceNumber);

    @GET @Path("/invoices/drafts/{draftInvoiceNumber}/pdf")
    @Produces("application/pdf")
    InputStream getDraftPdf(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @PathParam("draftInvoiceNumber") int draftInvoiceNumber);

    @GET @Path("/self")
    EconomicsAgreementSelf getSelf(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant);
}
