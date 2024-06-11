package dk.trustworks.intranet.expenseservice.remote;

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataOutput;

@RegisterRestClient
@Produces("application/json")
@Path("/journals")
public interface EconomicsAPIFile extends AutoCloseable {

    @POST
    @Consumes("multipart/form-data; boundary=----------------------------240952202702610052022222")
    @Path("/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
    @Blocking
    Response postFile(@PathParam("journalNumber") int journalNumber, @PathParam("accountingYear") String accountingYear, @PathParam("voucherNumber") int voucherNumber, @RestForm MultipartFormDataOutput data);
}
