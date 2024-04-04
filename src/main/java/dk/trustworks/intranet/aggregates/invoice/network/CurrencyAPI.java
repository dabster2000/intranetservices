package dk.trustworks.intranet.aggregates.invoice.network;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("")
@RegisterRestClient
public interface CurrencyAPI {

    @GET
    @Path("/v1/historical")
    @Produces(APPLICATION_JSON)
    Response getExchangeRate(@QueryParam("date") String date, @QueryParam("base_currency") String base_currency, @QueryParam("currencies") String currency, @QueryParam("apikey") String apiKey);

}
