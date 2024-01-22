package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/accounts")
@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
public interface EconomicsAPIAccount extends AutoCloseable {

    @GET
    @Path("/{account}")
    Response getAccount(@PathParam("account") int account);
}