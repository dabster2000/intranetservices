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
                                @QueryParam("pagesize") @DefaultValue("1000") int pagesize,
                                @QueryParam("skippages") @DefaultValue("0") int skippages);

        /**
         * Lists accounts (chart of accounts) for the current tenant. Used by
         * {@code EconomicRevenueImportService} (PR 2 of external-invoice-import)
         * to build an accountNumber→name lookup map for the clientname fallback.
         *
         * @param filter   e-conomic filter expression, e.g. {@code "accountType$eq:profitAndLoss"}
         * @param pagesize page size (default 1000 — chart of accounts is small)
         */
        @GET
        @Path("/accounts")
        Response getAccounts(@QueryParam("filter") String filter,
                             @QueryParam("pagesize") @DefaultValue("1000") int pagesize);

        /**
         * Lists all accounting years for the current tenant. Used by
         * {@code EconomicRevenueImportService} to discover the URL-encoded
         * year codes dynamically — tenants can use non-standard names like
         * {@code 2025_6_2026a} (suffix variants) so a hardcoded
         * {@code year + "_6_" + (year+1)} pattern is unreliable.
         */
        @GET
        @Path("/accounting-years")
        Response getAccountingYears(@QueryParam("pagesize") @DefaultValue("50") int pagesize);

        /**
         * Lists entries on a specific account in a specific accounting year.
         * Used by {@code EconomicRevenueImportService} for the
         * {@code financeVoucher@2180} fetch path (the entries endpoint at
         * {@code /accounting-years/{y}/entries} does not allow filtering by
         * {@code account.accountNumber}, so we scope by URL instead).
         */
        @GET
        @Path("/accounts/{accountNumber}/accounting-years/{accountingYear}/entries")
        Response getAccountEntries(@PathParam("accountNumber") int accountNumber,
                                   @PathParam("accountingYear") String accountingYear,
                                   @QueryParam("filter") String filter,
                                   @QueryParam("pagesize") @DefaultValue("1000") int pagesize,
                                   @QueryParam("skippages") @DefaultValue("0") int skippages);

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
