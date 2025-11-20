package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client for e-conomic NEW Journals API.
 * Base URL: https://apis.e-conomic.com/journalsapi/v13.0.1
 *
 * This API provides modern endpoints for journal operations including
 * deleting draft entries (which the OLD REST API does not support).
 *
 * @see <a href="https://apis.e-conomic.com/journalsapi/redoc.html">e-conomic Journals API Documentation</a>
 */
@RegisterRestClient(configKey = "economics-journals-api")
@RegisterProvider(EconomicsErrorMapper.class)
@Produces(MediaType.APPLICATION_JSON)
@Path("/")
public interface EconomicsJournalsAPI extends AutoCloseable {

    /**
     * Get draft entries from journals (UNBOOKED vouchers).
     * Used to obtain entryNumber and objectVersion required for deletion.
     *
     * Filter must include both journal number and voucher number:
     * "journalNumber$eq:16$and:voucherNumber$eq:123456"
     *
     * @param filter   OData filter expression combining journal and voucher
     * @param cursor   Cursor for pagination (optional, recommended for large datasets)
     * @param pagesize Maximum number of entries to return (default 1000, max 1000)
     * @return Response containing JournalEntryResponse with draft entries
     */
    @GET
    @Path("/draft-entries")
    @Produces(MediaType.APPLICATION_JSON)
    JournalEntryResponse getDraftEntries(
            @QueryParam("filter") String filter,
            @QueryParam("cursor") String cursor,
            @QueryParam("pagesize") @DefaultValue("1000") int pagesize
    );

    /**
     * Delete a draft entry from a journal.
     * Requires entryNumber and objectVersion obtained from GET request.
     *
     * @param request The delete request with journal, voucher, entry details
     * @return Response with status 204 (No Content) on success
     */
    @DELETE
    @Path("/draft-entries")
    @Consumes(MediaType.APPLICATION_JSON)
    Response deleteDraftEntry(DraftEntryDeleteRequest request);

    /**
     * Delete ALL draft entries in a journal (batch operation).
     * Use with caution - clears entire journal.
     *
     * @param journalNumber The journal to clear
     * @return Response with status 204 (No Content) on success
     */
    @DELETE
    @Path("/draft-entries/delete/{journalNumber}")
    Response deleteAllDraftEntries(@PathParam("journalNumber") int journalNumber);
}
