package dk.trustworks.intranet.financeservice.remote;

import dk.trustworks.intranet.financeservice.remote.dto.economics.EconomicsInvoice;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.*;

@Path("/accounting-years")
@RegisterRestClient
@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1") //value="{xAppSecretToken}")
@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01") //value="{xAgreementGrantToken}")
public interface EconomicsAPI {

    @GET
    @Path("/{date}/entries")
    @Produces("application/json")
    EconomicsInvoice getEntries(@PathParam("date") String date,
                                @QueryParam("pagesize") int pageSize,
                                @QueryParam("skippages") int skipPages);

    @GET
    EconomicsInvoice getNextPage();

}
