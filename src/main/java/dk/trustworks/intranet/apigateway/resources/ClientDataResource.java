package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientDataService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "crm")
@Path("/clientdata")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class ClientDataResource {

    @Inject
    ClientDataService clientDataService;

    @GET
    public List<Clientdata> listAll() {
        throw new RuntimeException("NOT IMPLEMENTED!!");
    }

    @GET
    @Path("/{uuid}")
    public Clientdata findByUuid(@PathParam("uuid") String uuid) {
        return clientDataService.findByUuid(uuid);
    }

    @GET
    @Path("/{clientdatauuid}/projects")
    public List<Project> findProjectsByClientdataUuid(@PathParam("clientdatauuid") String clientdatauuid) {
        throw new RuntimeException("NOT IMPLEMENTED!!");
    }

    @POST
    public Clientdata save(Clientdata clientdata) {
        return clientDataService.save(clientdata);
    }

    @PUT
    public void update(Clientdata clientdata) {
        clientDataService.updateOne(clientdata);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        clientDataService.delete(uuid);
    }
}