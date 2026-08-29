package dk.trustworks.intranet.financeservice.remote;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for the e-conomic NEW Booked Entries API
 * (base {@code https://apis.e-conomic.com/bookedentriesapi/v4.0.0}) and, for
 * the account endpoint, the classic REST API.
 *
 * <p>{@code /booked-entries} filters on flat properties with mongo-style
 * operators (e.g. {@code accountNumber$eq:8720$and:date$gte:2015-07-01}) and
 * pages with an opaque cursor — unlike the classic API it crosses
 * accounting-year boundaries in one query, which is what makes an all-time
 * bank-account export cheap (~14 pages for 11 years).</p>
 *
 * <p>Annotations mirror {@link EconomicsJournalEntriesAPI}; built per company
 * via {@code RestClientBuilder} with the agreement's tokens
 * ({@link EconomicsDynamicHeaderFilter}).</p>
 */
@RegisterRestClient
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
@Produces("application/json")
@Consumes("application/json")
public interface EconomicsBookedEntriesAPI extends AutoCloseable {

    @GET
    @Path("/booked-entries")
    Response getBookedEntries(
            @QueryParam("filter") String filter,
            @QueryParam("cursor") String cursor,
            @QueryParam("pagesize") int pagesize);

    /**
     * Classic REST account resource — returns the authoritative account
     * {@code balance}. Only valid when the client is built against the classic
     * base URL (the company's {@code url} integration key).
     */
    @GET
    @Path("/accounts/{accountNumber}")
    Response getAccount(@PathParam("accountNumber") int accountNumber);
}
