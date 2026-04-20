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
 * MicroProfile REST Client for the Danish CVR registry via Virkdata
 * (virkdata.dk).
 *
 * <p>Virkdata exposes a single endpoint — the {@code search} parameter
 * accepts either a CVR number or a company name; the API resolves both.
 * Authentication is a per-account API key injected by
 * {@link VirkdataHeadersFactory} as the {@code Authorization} header.
 *
 * @see <a href="https://virkdata.dk">Virkdata documentation</a>
 */
@Path("/api")
@RegisterRestClient(configKey = "virkdata")
@RegisterClientHeaders(VirkdataHeadersFactory.class)
public interface CvrApiClient {

    /**
     * Queries the Virkdata API. The {@code search} value may be an 8-digit
     * CVR number or a company name; Virkdata resolves both.
     *
     * @param search  the search term (CVR number or company name)
     * @param country country code ({@code dk} only for our subscription)
     * @param format  response format; always {@code json}
     * @return parsed response — may represent success or a soft error
     */
    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    VirkdataResponse search(@QueryParam("search") String search,
                            @QueryParam("country") @DefaultValue("dk") String country,
                            @QueryParam("format") @DefaultValue("json") String format);
}
