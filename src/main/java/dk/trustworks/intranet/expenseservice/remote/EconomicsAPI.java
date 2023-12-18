package dk.trustworks.intranet.expenseservice.remote;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
@Path("/journals")
//@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1")
//@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01")
public interface EconomicsAPI extends AutoCloseable {

        @POST
        @Path("/{journalNumber}/vouchers")
        Response postVoucher(@PathParam("journalNumber") int journalNumber, String voucher);

}
