package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.MediaType.MULTIPART_FORM_DATA;

@RegisterRestClient
@RegisterProvider(EconomicsErrorMapper.class)
@Produces("application/json")
@Path("/")
public interface EconomicsAPI extends AutoCloseable {

        @POST
        @Consumes("application/json")
        @Path("/journals/{journalNumber}/vouchers")
        Response postVoucher(@PathParam("journalNumber") int journalNumber, @HeaderParam("Idempotency-Key") String idempotencyKey, String voucher);

        @Consumes(MULTIPART_FORM_DATA)
        @Produces(APPLICATION_JSON)
        @POST
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
        Response postFile(@PathParam("journalNumber") int journalNumber,
                          @PathParam("accountingYear") String accountingYear,
                          @PathParam("voucherNumber") int voucherNumber,
                          org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput form);

    @Consumes(MULTIPART_FORM_DATA)
    @Produces(APPLICATION_JSON)
    @POST
    @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
    Response postExpenseFile(@PathParam("journalNumber") int journalNumber,
                      @PathParam("accountingYear") String accountingYear,
                      @PathParam("voucherNumber") int voucherNumber,
                      @HeaderParam("Idempotency-Key") String idempotencyKey,
                      MultipartFormDataOutput form);

        @GET
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}")
        Response getVoucher(@PathParam("journalNumber") int journalNumber,
                            @PathParam("accountingYear") String accountingYear,
                            @PathParam("voucherNumber") int voucherNumber);

        @GET
        @Path("/journals/{journalNumber}/entries")
        Response getJournalEntries(@PathParam("journalNumber") int journalNumber,
                                   @QueryParam("filter") String filter,
                                   @QueryParam("pagesize") @DefaultValue("1000") int pagesize);

        @GET
        @Path("/accounting-years/{accountingYear}/entries")
        Response getYearEntries(@PathParam("accountingYear") String accountingYear,
                                @QueryParam("filter") String filter,
                                @QueryParam("pagesize") @DefaultValue("1000") int pagesize);

        /**
         * Lists accounts (chart of accounts) for the current tenant. Used by
         * {@code EconomicRevenueImportService} (PR 2 of external-invoice-import)
         * to discover revenue accounts before filtering by tenant-specific
         * deny-lists (1040 for TECH/CYBER; 2101 for A/S; 2102/2103 VMS).
         *
         * @param filter   e-conomic filter expression, e.g. {@code "accountType$eq:revenue"}
         * @param pagesize page size (default 1000 — chart of accounts is small)
         */
        @GET
        @Path("/accounts")
        Response getAccounts(@QueryParam("filter") String filter,
                             @QueryParam("pagesize") @DefaultValue("1000") int pagesize);

        @GET
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment")
        Response getVoucherAttachmentMeta(@PathParam("journalNumber") int journalNumber,
                                          @PathParam("accountingYear") String accountingYear,
                                          @PathParam("voucherNumber") int voucherNumber);

        @GET
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment")
        Response getAttachment(@PathParam("journalNumber") int journalNumber,
                               @PathParam("accountingYear") String accountingYear,
                               @PathParam("voucherNumber") int voucherNumber);

        @PATCH
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
        Response patchFile(@PathParam("journalNumber") int journalNumber,
                           @PathParam("accountingYear") String accountingYear,
                           @PathParam("voucherNumber") int voucherNumber,
                           @HeaderParam("Idempotency-Key") String idempotencyKey,
                           MultipartFormDataOutput file);

        // NOTE: DELETE endpoint removed - not supported by OLD REST API (returns HTTP 405)
        // Use EconomicsJournalsAPI.deleteDraftEntry() instead (NEW Journals API)
}
