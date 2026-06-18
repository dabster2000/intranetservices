package dk.trustworks.intranet.aggregates.invoice.economics.supplier;

import dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto.SuppliersPage;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST client for e-conomic's legacy /suppliers endpoint.
 *
 * Reuses the existing {@code economics-legacy-rest} configKey which is already
 * mapped to {@code https://restapi.e-conomic.com} in application.yml.
 *
 * Used by {@link EconomicsSupplierResolver} to resolve an issuer company's
 * supplier number in a debtor company's e-conomic agreement by CVR.
 */
@RegisterRestClient(configKey = "economics-legacy-rest")
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsSuppliersApiClient {

    /**
     * Filter syntax: {@code corporateIdentificationNumber$eq:{cvr}}.
     */
    @GET
    @Path("/suppliers")
    SuppliersPage findByFilter(
            @HeaderParam("X-AppSecretToken") String appSecret,
            @HeaderParam("X-AgreementGrantToken") String agreementGrant,
            @QueryParam("filter") String filter);
}
