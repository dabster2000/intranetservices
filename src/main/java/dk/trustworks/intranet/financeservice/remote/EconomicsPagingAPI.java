package dk.trustworks.intranet.financeservice.remote;

import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1")
@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01")
public interface EconomicsPagingAPI extends AutoCloseable {

    @GET
    Response getNextPage();

}
