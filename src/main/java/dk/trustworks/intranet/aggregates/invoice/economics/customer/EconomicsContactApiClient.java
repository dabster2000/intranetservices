package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
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
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
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

    /**
     * POST /Contacts returns {@code {"number": int}} (CreatedResult), NOT the
     * full contact. Callers needing the contact's other fields (objectVersion,
     * etc.) must GET /Contacts/{number} after this call.
     */
    @POST
    @Path("/Contacts")
    CreatedResult createContact(EconomicsContactDto body);

    /**
     * Updates a contact via {@code PUT /Contacts} (collection URL, operation
     * {@code UpdateContactById}). Unlike {@code /Customers/{number}}, the
     * item URL {@code /Contacts/{number}} accepts only GET and DELETE — a PUT
     * there is answered with 405 Method Not Allowed (observed in production
     * 2026-08-04). The contact identity travels in the body's {@code number}
     * field ({@link EconomicsContactDto#setCustomerContactNumber}).
     *
     * <p>PUT returns 204 with an EMPTY body. Declaring a non-void return
     * triggers RESTEasy deserialisation 500s. Callers needing the fresh
     * objectVersion must re-GET after the PUT.
     */
    @PUT
    @Path("/Contacts")
    void updateContact(EconomicsContactDto body);
}
