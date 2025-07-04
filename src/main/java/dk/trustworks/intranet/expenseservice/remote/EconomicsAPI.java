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
@Path("/journals")
public interface EconomicsAPI extends AutoCloseable {

        @POST
        @Consumes("application/json")
        @Path("/{journalNumber}/vouchers")
        Response postVoucher(@PathParam("journalNumber") int journalNumber, String voucher);

        @POST
        @Consumes("multipart/form-data; boundary=----------------------------240952202702610052022222")
        @Path("/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
        Response postFile(@PathParam("journalNumber") int journalNumber, @PathParam("accountingYear") String accountingYear, @PathParam("voucherNumber") int voucherNumber, @MultipartForm MultipartFormDataOutput data);

}
