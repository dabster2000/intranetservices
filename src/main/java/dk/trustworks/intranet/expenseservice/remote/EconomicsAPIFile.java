package dk.trustworks.intranet.expenseservice.remote;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@RegisterRestClient
@Produces("application/json")
@Path("/journals")
public interface EconomicsAPIFile extends AutoCloseable {

    @POST
    @Consumes("multipart/form-data; boundary=----------------------------240952202702610052022222")
    @Path("/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
    Response postFile(@PathParam("journalNumber") int journalNumber, @PathParam("accountingYear") String accountingYear, @PathParam("voucherNumber") int voucherNumber, @MultipartForm MultipartFormDataOutput data);
}
