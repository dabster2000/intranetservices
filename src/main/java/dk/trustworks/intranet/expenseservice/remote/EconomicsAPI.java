package dk.trustworks.intranet.expenseservice.remote;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@RegisterRestClient
@RegisterProvider(EconomicsErrorMapper.class)
@Produces("application/json")
@Path("/")
public interface EconomicsAPI extends AutoCloseable {

        @POST
        @Consumes("application/json")
        @Path("/journals/{journalNumber}/vouchers")
        Response postVoucher(@PathParam("journalNumber") int journalNumber, @HeaderParam("Idempotency-Key") String idempotencyKey, String voucher);

        @POST
        @Consumes("multipart/form-data")
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
        Response postFile(@PathParam("journalNumber") int journalNumber, @PathParam("accountingYear") String accountingYear, @PathParam("voucherNumber") int voucherNumber, @MultipartForm MultipartFormDataOutput data);

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

        @GET
        @Path("/journals/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment")
        Response getVoucherAttachmentMeta(@PathParam("journalNumber") int journalNumber,
                                          @PathParam("accountingYear") String accountingYear,
                                          @PathParam("voucherNumber") int voucherNumber);
}
