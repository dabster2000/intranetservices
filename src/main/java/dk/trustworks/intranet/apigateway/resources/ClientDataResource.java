package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.dao.crm.services.ClientDataService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;
import java.util.Objects;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

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

    @Inject
    ClientActivityLogService activityLogService;

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
        Clientdata saved = clientDataService.save(clientdata);

        // Log activity
        if (clientdata.getClientuuid() != null) {
            activityLogService.logCreated(clientdata.getClientuuid(),
                    ClientActivityLog.TYPE_CLIENTDATA, saved.getUuid(), clientdata.getClientname());
        }

        return saved;
    }

    @PUT
    public void update(Clientdata clientdata) {
        // Load old state for change logging
        Clientdata oldData = clientDataService.findByUuid(clientdata.getUuid());

        clientDataService.updateOne(clientdata);

        // Log field-level changes
        if (oldData != null && oldData.getClientuuid() != null) {
            String clientUuid = oldData.getClientuuid();
            String entityUuid = clientdata.getUuid();
            String entityName = oldData.getClientname();

            if (!Objects.equals(oldData.getClientname(), clientdata.getClientname())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENTDATA, entityUuid, entityName,
                        "clientname", oldData.getClientname(), clientdata.getClientname());
            }
            if (!Objects.equals(oldData.getContactperson(), clientdata.getContactperson())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENTDATA, entityUuid, entityName,
                        "contactperson", oldData.getContactperson(), clientdata.getContactperson());
            }
            if (!Objects.equals(oldData.getCvr(), clientdata.getCvr())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENTDATA, entityUuid, entityName,
                        "cvr", oldData.getCvr(), clientdata.getCvr());
            }
            if (!Objects.equals(oldData.getEan(), clientdata.getEan())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENTDATA, entityUuid, entityName,
                        "ean", oldData.getEan(), clientdata.getEan());
            }
            if (!Objects.equals(oldData.getStreetnamenumber(), clientdata.getStreetnamenumber())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENTDATA, entityUuid, entityName,
                        "streetnamenumber", oldData.getStreetnamenumber(), clientdata.getStreetnamenumber());
            }
            if (!Objects.equals(oldData.getCity(), clientdata.getCity())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENTDATA, entityUuid, entityName,
                        "city", oldData.getCity(), clientdata.getCity());
            }
        }
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        clientDataService.delete(uuid);
    }
}