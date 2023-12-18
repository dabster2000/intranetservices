package dk.trustworks.intranet.financeservice.remote;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@RegisterRestClient
@Path("/accounting-years")
@Produces("application/json")
@Consumes("application/json")
public interface EconomicsPagingAPI extends AutoCloseable {

    @GET
    @Path("/{date}/entries")
    Response getEntries(@PathParam("date") String date,
                        @QueryParam("pageSize") int pageSize,
                        @QueryParam("skippages") int skipPages);

    @GET
    Response getNextPage();

}
