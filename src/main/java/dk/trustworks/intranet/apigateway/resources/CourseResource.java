package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.CkoCourse;
import dk.trustworks.intranet.knowledgeservice.services.CourseService;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import java.util.List;

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

}
