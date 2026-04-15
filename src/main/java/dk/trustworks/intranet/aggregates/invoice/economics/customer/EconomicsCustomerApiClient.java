package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * E-conomic Customers API v3.1.0 REST client.
 *
 * <p>Auth tokens ({@code X-AppSecretToken}, {@code X-AgreementGrantToken}) are
 * injected per-agreement by the {@link EconomicsCustomersApiClientFactory}
 * via a {@link jakarta.ws.rs.client.ClientRequestFilter}, so method signatures
 * stay free of auth plumbing.
 *
 * <p>Base URL: {@code https://apis.e-conomic.com/customersapi/v3.1.0}
 *
 * SPEC-INV-001 §6.1, §6.3.
 */
@RegisterRestClient(configKey = "economics-customers-api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsCustomerApiClient {

    @GET
    @Path("/customers")
    EconomicsCustomersPage listCustomers(
            @QueryParam("pagesize") int pageSize,
            @QueryParam("skippages") int skip
    );

    @GET
    @Path("/customers/{customerNumber}")
    EconomicsCustomerDto getCustomer(@PathParam("customerNumber") int customerNumber);

    @POST
    @Path("/customers")
    EconomicsCustomerDto createCustomer(EconomicsCustomerDto body);

    /**
     * PUT returns HTTP 200 with an EMPTY body (content-length: 0, no
     * content-type) on success — verified 2026-04-15. Declaring a non-void
     * return type would trigger RESTEasy
     * "Unable to find a MessageBodyReader of content-type application/
     * octet-stream" 500s. Callers that need the fresh objectVersion must
     * re-GET after the PUT.
     */
    @PUT
    @Path("/customers/{customerNumber}")
    void updateCustomer(
            @PathParam("customerNumber") int customerNumber,
            EconomicsCustomerDto body
    );
}
