package dk.trustworks.intranet.dao.crm.client;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * MicroProfile REST Client for the Danish CVR API (cvrapi.dk).
 *
 * <p>Provides company lookup by CVR number and company name search.
 * The API is public (no authentication), but requires a custom User-Agent
 * header set by {@link CvrApiHeadersFactory}.
 *
 * <p>Free tier: 50 lookups/day per IP. Responses are cached in
 * {@link dk.trustworks.intranet.dao.crm.services.CvrLookupService} to
 * minimize API calls.
 *
 * @see <a href="https://cvrapi.dk/documentation">CVR API Documentation</a>
 */
@Path("/api")
@RegisterRestClient(configKey = "cvr-api")
@RegisterClientHeaders(CvrApiHeadersFactory.class)
public interface CvrApiClient {

    /**
     * Looks up a company by its CVR number (VAT number).
     *
     * @param vat     the 8-digit CVR number
     * @param country the country code (default: "dk")
     * @return company data or an error response
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    CvrApiResponse lookupByVat(@QueryParam("vat") String vat,
                                @QueryParam("country") @DefaultValue("dk") String country);

    /**
     * Searches for a company by name.
     *
     * @param name    the company name to search for
     * @param country the country code (default: "dk")
     * @return company data or an error response
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    CvrApiResponse searchByName(@QueryParam("name") String name,
                                 @QueryParam("country") @DefaultValue("dk") String country);
}
