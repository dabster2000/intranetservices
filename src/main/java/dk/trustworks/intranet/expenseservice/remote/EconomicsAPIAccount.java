package dk.trustworks.intranet.expenseservice.remote;

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Path("/accounts")
@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1")
@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01")
public interface EconomicsAPIAccount extends AutoCloseable {

    @GET
    @Path("/{account}")
    Response getAccount(@PathParam("account") int account);
}