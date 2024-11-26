package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.CkoCourse;
import dk.trustworks.intranet.knowledgeservice.model.CkoCourseParticipant;
import dk.trustworks.intranet.knowledgeservice.services.CourseService;
import dk.trustworks.intranet.userservice.model.User;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Tag(name = "course")
@Path("/knowledge/courses")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class CourseResource {


    private final CourseService service;

    @Inject
    public CourseResource(CourseService service) {
        this.service = service;
    }

    @GET
    public List<CkoCourse> findAll() {
        return service.findAll();
    }

    @GET
    @Path("/search/findByType")
    public List<CkoCourse> findAllByCourseTypeAndActiveTrue(@QueryParam("type") String type) {
        return service.findAllByCourseTypeAndActiveTrue(type);
    }

    @POST
    @Transactional
    public void create(CkoCourse course) {
        service.create(course);
    }

    @PUT
    @Transactional
    public void update(CkoCourse course) {
        service.update(course);
    }

    @GET
    @Path("/{courseuuid}/participants")
    public List<CkoCourseParticipant> findAllParticipants(@PathParam("courseuuid") String courseuuid) {
        Optional<CkoCourse> course = CkoCourse.findByIdOptional(courseuuid);
        if (course.isPresent()) {
            return service.findAllSignedUpUsers(course.get());
        } else {
            return new ArrayList<>();
        }
    }

    @POST
    @Path("/{courseuuid}/participants/user/{useruuid}")
    @Transactional
    public void signupForCourse(@PathParam("courseuuid") String courseuuid, @PathParam("useruuid") String useruuid) {
        CkoCourse.<CkoCourse>findByIdOptional(courseuuid).ifPresent(ckoCourse -> service.addParticipants(ckoCourse, User.findById(useruuid)));
    }

    @DELETE
    @Path("/{courseuuid}/participants/{useruuid}")
    @Transactional
    public void removeParticipants(@PathParam("courseuuid") String courseuuid, @PathParam("useruuid") String useruuid) {
        service.removeParticipant(useruuid);
    }

}
