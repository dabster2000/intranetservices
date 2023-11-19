package dk.trustworks.intranet.financeservice.remote;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
//@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1")
//@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01")
public interface EconomicsPagingAPI extends AutoCloseable {

    @GET
    @Path("/{date}/entries")
    Response getEntries(@PathParam("date") String date,
                        @QueryParam("pageSize") int pageSize,
                        @QueryParam("skippages") int skipPages);

    @GET
    Response getNextPage();

}
