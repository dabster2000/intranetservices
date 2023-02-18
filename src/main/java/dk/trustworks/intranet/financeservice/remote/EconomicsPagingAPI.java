package dk.trustworks.intranet.financeservice.remote;

import dk.trustworks.intranet.financeservice.remote.dto.economics.EconomicsInvoice;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.GET;
import javax.ws.rs.Produces;

@RegisterRestClient
@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1") //value="{xAppSecretToken}")
@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01") //value="{xAgreementGrantToken}")
public interface EconomicsPagingAPI extends AutoCloseable {

    @GET
    @Produces("application/json")
    EconomicsInvoice getNextPage();

}
