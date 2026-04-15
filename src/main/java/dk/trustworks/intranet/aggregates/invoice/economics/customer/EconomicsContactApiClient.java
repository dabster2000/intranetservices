package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * E-conomic Customers API v3.1.0 {@code /Contacts} REST client.
 *
 * <p>Auth tokens ({@code X-AppSecretToken}, {@code X-AgreementGrantToken}) are
 * injected per-agreement by the {@link EconomicsCustomersApiClientFactory}
 * via a {@link jakarta.ws.rs.client.ClientRequestFilter}, so method signatures
 * stay free of auth plumbing.
 *
 * <p>Base URL: {@code https://apis.e-conomic.com/customersapi/v3.1.0}
 *
 * <p>Phase G0 confirmed the {@code ?filter=customerNumber$eq:{n}} query shape
 * and that POST returns {@code {number: N}} where N is reusable as
 * {@code attentionNumber} on Q2C drafts.
 *
 * SPEC-INV-001 §3.3.2, §6.1, §6.4.
 */
@RegisterRestClient(configKey = "economics-customers-api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsContactApiClient {

    @GET
    @Path("/Contacts")
    EconomicsContactsPage listByCustomer(
            @QueryParam("filter") String filter,
            @QueryParam("pagesize") int pageSize,
            @QueryParam("skippages") int skip
    );

    @GET
    @Path("/Contacts/{number}")
    EconomicsContactDto getContact(@PathParam("number") int customerContactNumber);

    @POST
    @Path("/Contacts")
    EconomicsContactDto createContact(EconomicsContactDto body);

    @PUT
    @Path("/Contacts/{number}")
    EconomicsContactDto updateContact(
            @PathParam("number") int customerContactNumber,
            EconomicsContactDto body
    );
}
