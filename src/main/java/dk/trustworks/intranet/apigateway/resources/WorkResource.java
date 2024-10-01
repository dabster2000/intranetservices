package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.work.events.UpdateWorkEvent;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

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

    @Inject
    AggregateEventSender sender;

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
        return workAPI.findByPeriodAndUserAndTasks(dateIt(fromdate), dateIt(todate), useruuid, taskuuids);
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
    public KeyValueDTO sumWorkdurationByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("taskuuids") String taskuuids) {
        return new KeyValueDTO("", Double.toString(workAPI.countByUserAndTasks(useruuid, taskuuids)));
    }

    @GET
    @Path("/work/billable/sum")
    public double sumBillableByUserAndTasks(@QueryParam("useruuid") String useruuid, @QueryParam("month") String month) {
        return workAPI.sumBillableByUserAndTasks(useruuid, DateUtils.dateIt(month));
    }

    @POST
    @Path("/work")
    public void save(Work work) {
        workAPI.persistOrUpdate(work);

        sender.handleEvent(new UpdateWorkEvent(work.getUseruuid(), work));
        if(work.getWorkas()!=null && !work.getWorkas().isEmpty())
            sender.handleEvent(new UpdateWorkEvent(work.getWorkas(), work));
    }
/*
    @GET
    @Path("/tasks/{uuid}/work")
    public List<WorkFull> getWorkByTask(@PathParam("uuid") String taskuuid) {
        return workAPI.findByTask(taskuuid);
    }

 */

}
