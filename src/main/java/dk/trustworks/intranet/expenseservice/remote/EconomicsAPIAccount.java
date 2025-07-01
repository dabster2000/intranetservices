package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import dk.trustworks.intranet.expenseservice.remote.EconomicsErrorMapper;

@Path("/accounts")
@RegisterRestClient
@RegisterProvider(EconomicsErrorMapper.class)
@Produces("application/json")
@Consumes("application/json")
public interface EconomicsAPIAccount extends AutoCloseable {

    @GET
    @Path("/{account}")
    Response getAccount(@PathParam("account") int account);
}