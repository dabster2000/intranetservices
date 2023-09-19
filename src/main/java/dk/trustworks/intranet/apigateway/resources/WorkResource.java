package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class WorkResource {

    @Inject
    WorkService workAPI;

    @GET
    @Path("/work")
    public List<WorkFull> listAll(@QueryParam("page") Integer page) {
        log.debug("WorkResource.listAll");
        log.debug("page = " + page);
        return workAPI.listAll(page);
    }

    @GET
    @Path("/work/search/findByPeriodAndUserAndTasks")
    public List<WorkFull> findByPeriodAndUserAndTasks(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate, @QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return workAPI.findByPeriodAndUserAndTasks(fromdate, todate, useruuid, taskuuids);
    }

    @GET
    @Path("/work/search/findByUserAndTasks")
    public List<WorkFull> findByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return workAPI.findByUserAndTasks(useruuid, taskuuids);
    }

    @GET
    @Path("/work/search/findByTasks")
    public List<WorkFull> findByTasks(@QueryParam("taskuuids") List<String> taskuuids) {
        return workAPI.findByTasks(taskuuids);
    }

    @GET
    @Path("/work/search/findByPeriod")
    public List<WorkFull> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return workAPI.findByPeriod(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/work/workduration/sum")
    public double sumWorkdurationByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return workAPI.countByUserAndTasks(useruuid, taskuuids);
    }

    @POST
    @Path("/work")
    public void save(Work work) {
        workAPI.saveWork(work);
    }

    @GET
    //@Path("/tasks/{uuid}/work")
    public List<WorkFull> getWorkByTask(@PathParam("uuid") String taskuuid) {
        return workAPI.findByTask(taskuuid);
    }

}
