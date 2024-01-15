package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.services.TaskService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.Collections;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "crm")
@Path("/tasks")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class TaskResource {

    @Inject
    TaskService taskAPI;

    @Inject
    WorkService workService;

    @GET
    public List<Task> listAll() {
        return taskAPI.listAll();
    }

    @GET
    @Path("/{uuid}")
    public Task findByUuid(@PathParam("uuid") String uuid) {
        return taskAPI.findByUuid(uuid);
    }

    @GET
    @Path("/{uuid}/work")
    public List<WorkFull> findWorkByTaskFilterByUseruuidAndRegistered(@PathParam("uuid") String taskuuid, @QueryParam("useruuids") String useruuid, @QueryParam("registered") String registered) {
        if(useruuid!=null && registered!=null)
            return Collections.singletonList(workService.findByRegisteredAndUseruuidAndTaskuuid(dateIt(registered), useruuid, taskuuid));
        else return workService.findByTask(taskuuid);
    }

    @POST
    public Task save(Task task) {
        return taskAPI.save(task);
    }

    @PUT
    public void updateOne(Task task) {
        taskAPI.updateOne(task);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        taskAPI.delete(uuid);
    }
}