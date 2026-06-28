package dk.trustworks.intranet.financeservice.remote;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST client for the e-conomic <em>classic</em> REST API
 * {@code GET /journals/{journalNumber}/entries} (base {@code https://restapi.e-conomic.com}).
 *
 * <p>Returns the UNBOOKED draft entries in a daybook. Used to sync intercompany
 * supplier-invoice drafts (the "Kreditor Intern" journal) into {@code finance_details}
 * as {@code DRAFT} rows, so the Booked+Draft cost source sees intercompany cost that is
 * created in e-conomic but not yet booked. Unlike the new Journals API
 * ({@code /draft-entries}), this endpoint exposes {@code contraAccount} (the cost leg) and
 * {@code contraVatAccount} (the VAT rate), both required to derive the net GL cost.
 *
 * <p>Built per-company via {@code RestClientBuilder} with the agreement's tokens
 * (same pattern as {@code EconomicsPagingAPI}); not a CDI-injected client.
 */
@Produces(MediaType.APPLICATION_JSON)
public interface EconomicsJournalEntriesAPI extends AutoCloseable {

    @GET
    @Path("/journals/{journalNumber}/entries")
    @Produces(MediaType.APPLICATION_JSON)
    Response getJournalEntries(
            @PathParam("journalNumber") int journalNumber,
            @QueryParam("skippages") int skipPages,
            @QueryParam("pagesize") int pageSize);
}
