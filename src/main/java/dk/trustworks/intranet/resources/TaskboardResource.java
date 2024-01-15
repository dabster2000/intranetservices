package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.TaskboardItem;
import dk.trustworks.intranet.services.TaskboardService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import java.util.List;
import java.util.Set;

@JBossLog
@Path("/taskboards")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class TaskboardResource {

    @Inject
    TaskboardService taskboardService;

    @GET
    @Path("/{taskboarduuid}/items")
    public List<TaskboardItem> findAll(@PathParam("taskboarduuid") String taskboarduuid) {
        return taskboardService.findAll();
    }

    @GET
    @Path("/{taskboarduuid}/badges")
    public Set<String> findAllBadges() {
        return taskboardService.findAllBadges();
    }

    @POST
    @Path("/{taskboarduuid}/items")
    public void persistTaskboardItem(TaskboardItem item, @PathParam("taskboarduuid") String taskboarduuid) {
        taskboardService.persistTaskboardItem(item);
    }
}
