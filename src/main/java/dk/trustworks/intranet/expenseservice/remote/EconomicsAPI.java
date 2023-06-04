package dk.trustworks.intranet.expenseservice.remote;

import dk.trustworks.intranet.expenseservice.remote.dto.economics.Voucher;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@Path("/journals")
@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
// GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1
//@ClientHeaderParam(name="X-AppSecretToken", value = "${xAppSecretToken}") //value="{xAppSecretToken}")
//B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01
//@ClientHeaderParam(name="X-AgreementGrantToken", value = "${xAgreementGrantToken}") //value="{xAgreementGrantToken}")
@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1") //value="{xAppSecretToken}")
@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01") //value="{xAgreementGrantToken}")
public interface EconomicsAPI {

        @POST
        @Path("/{journalNumber}/vouchers")
        Response postVoucher(@PathParam("journalNumber") int journalNumber, Voucher voucher);

}
