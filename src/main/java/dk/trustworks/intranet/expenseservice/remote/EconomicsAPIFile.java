package dk.trustworks.intranet.expenseservice.remote;

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.resteasy.annotations.providers.multipart.MultipartForm;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@Path("/journals")
@RegisterRestClient
@Produces("application/json")
@Consumes("multipart/form-data; boundary=----------------------------240952202702610052022222")
@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1") //value="{xAppSecretToken}")
@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01") //value="{xAgreementGrantToken}")
public interface EconomicsAPIFile {

    // /journals/:journalNumber/vouchers/:accountingYear-voucherNumber/attachment/file
    @POST
    @Path("/{journalNumber}/vouchers/{accountingYear}-{voucherNumber}/attachment/file")
    Response postFile(@PathParam("journalNumber") int journalNumber, @PathParam("accountingYear") String accountingYear, @PathParam("voucherNumber") int voucherNumber, @MultipartForm MultipartFormDataOutput data);
}
