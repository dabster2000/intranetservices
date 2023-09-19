package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.cultureservice.model.Lesson;
import dk.trustworks.intranet.cultureservice.model.LessonRole;
import dk.trustworks.intranet.cultureservice.model.PerformanceGroups;
import dk.trustworks.intranet.cultureservice.resources.LessonResource;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import java.util.List;

@Tag(name = "culture")
@Path("/culture")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class CultureResource {

    @Inject
    LessonResource lessonFramedAPI;

    @GET
    @Path("/lessonframed/performancegroups")
    public List<PerformanceGroups> findAllPerformanceGroups() {
        return lessonFramedAPI.findAllPerformanceGroups();
    }

    @GET
    @Path("/lessonframed/performancegroups/active")
    public List<PerformanceGroups> findActivePerformanceGroups() {
        return lessonFramedAPI.findActivePerformanceGroups();
    }

    @GET
    @Path("/lessonframed/roles")
    public List<LessonRole> findAllRoles() {
        return lessonFramedAPI.findAllRoles();
    }

    @GET
    @Path("/lessonframed/lessons")
    public List<Lesson> findByUserAndProject(@QueryParam("useruuid") String useruuid, @QueryParam("projectuuid") String projectuuid) {
        return lessonFramedAPI.findByUserAndProject(useruuid, projectuuid);
    }

    @POST
    @Path("/lessonframed")
    public void saveLesson(Lesson lesson) {
        lessonFramedAPI.save(lesson);
    }
}