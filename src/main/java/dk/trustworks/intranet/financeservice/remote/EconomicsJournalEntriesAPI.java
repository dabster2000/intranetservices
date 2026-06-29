package dk.trustworks.intranet.financeservice.remote;

import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * REST client for the e-conomic <em>classic</em> REST API
 * {@code GET /journals/{journalNumber}/entries} (base {@code https://restapi.e-conomic.com}).
 *
 * <p>Returns the UNBOOKED draft entries in a daybook. Used to sync intercompany
 * supplier-invoice drafts (the "Kreditor Intern" journal) into {@code finance_details}
 * as {@code DRAFT} rows. Unlike the new Journals API ({@code /draft-entries}), this endpoint
 * exposes {@code contraAccount} (the cost leg) and {@code contraVatAccount} (the VAT rate),
 * both required to derive the net GL cost.
 *
 * <p>Annotations mirror {@link EconomicsPagingAPI} (the proven classic-REST client); built
 * per-company via {@code RestClientBuilder} with the agreement's tokens.
 */
@RegisterRestClient
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
@Produces("application/json")
@Consumes("application/json")
public interface EconomicsJournalEntriesAPI extends AutoCloseable {

    @GET
    @Path("/journals/{journalNumber}/entries")
    Response getJournalEntries(
            @PathParam("journalNumber") int journalNumber,
            @QueryParam("skippages") int skipPages,
            @QueryParam("pagesize") int pageSize);
}
