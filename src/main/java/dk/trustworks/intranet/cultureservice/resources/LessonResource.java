package dk.trustworks.intranet.cultureservice.resources;

import dk.trustworks.intranet.cultureservice.model.Lesson;
import dk.trustworks.intranet.cultureservice.model.LessonRole;
import dk.trustworks.intranet.cultureservice.model.PerformanceGroups;
import lombok.extern.jbosslog.JBossLog;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.util.List;
import java.util.UUID;

@JBossLog
@Path("/lessonframed")
@RequestScoped
@RolesAllowed({"SYSTEM"})
public class LessonResource {

    @GET
    @Path("/performancegroups")
    public List<PerformanceGroups> findAllPerformanceGroups() {
        return PerformanceGroups.findAll().list();
    }

    @GET
    @Path("/performancegroups/active")
    public List<PerformanceGroups> findActivePerformanceGroups() {
        return PerformanceGroups.find("active is true").list();
    }

    @GET
    @Path("/roles")
    public List<LessonRole> findAllRoles() {
        return LessonRole.findAll().list();
    }

    @GET
    @Path("/search/findByUserAndProject")
    public List<Lesson> findByUserAndProject(@QueryParam("useruuid") String useruuid, @QueryParam("projectuuid") String projectuuid) {
        return Lesson.find("useruuid like ?1 and projectuuid like ?2", useruuid, projectuuid).list();
    }

    @POST
    @Transactional
    public void save(Lesson lesson) {
        if(lesson.getUuid()==null || lesson.getUuid().isEmpty()) lesson.setUuid(UUID.randomUUID().toString());
        Lesson.persist(lesson);
    }

    @PUT
    @Transactional
    public void update(Lesson lesson) {
        if(lesson.getUuid()==null || lesson.getUuid().isEmpty()) throw new EntityNotFoundException("Missing uuid");
        Lesson.update("note = ?1, " +
                        "startdate = ?2, " +
                        "enddate = ?3 " +
                        "WHERE uuid like ?4 ",
                lesson.getNote(),
                lesson.getStartdate(),
                lesson.getEnddate(),
                lesson.getUuid());
    }

    @DELETE
    @Transactional
    @Path("/{lessonuuid}")
    public void delete(@PathParam("lessonuuid") String uuid) {
        if(uuid==null || uuid.isEmpty()) throw new EntityNotFoundException("Missing uuid");
        Lesson.deleteById(uuid);
    }
}
