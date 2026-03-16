package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import dk.trustworks.intranet.knowledgeservice.services.ProjectDescriptionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

import java.util.List;

@Tag(name = "Knowledge")
@JBossLog
@SecurityRequirement(name = "jwt")
@ClientHeaderParam(name="Authorization", value="{generateRequestId}")
@RolesAllowed({"knowledge:read"})
@Path("/knowledge/projects")
public class ProjectDescriptionResource {

    @Inject
    ProjectDescriptionService knowledgeProjectAPI;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public List<ProjectDescription> findAll() {
        return knowledgeProjectAPI.findAll();
    }

    @POST
    @RolesAllowed({"knowledge:write"})
    public void create(ProjectDescription projectDescription) {
        log.infof("ProjectDescription create requested by user=%s, name=%s",
                requestHeaderHolder.getUserUuid(), projectDescription.getName());
        knowledgeProjectAPI.create(projectDescription);
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"knowledge:write"})
    public void update(@PathParam("uuid") String uuid, ProjectDescription projectDescription) {
        log.infof("ProjectDescription update requested: uuid=%s by user=%s",
                uuid, requestHeaderHolder.getUserUuid());
        knowledgeProjectAPI.update(uuid, projectDescription);
    }

    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"knowledge:write"})
    public void delete(@PathParam("uuid") String uuid) {
        log.infof("ProjectDescription delete requested: uuid=%s by user=%s",
                uuid, requestHeaderHolder.getUserUuid());
        knowledgeProjectAPI.delete(uuid);
    }

    @GET
    @Path("/{projectdesc_uuid}/consultants/{useruuid}")
    public void addProjectDescriptionUser(@PathParam("projectdesc_uuid") String uuid, @PathParam("useruuid") String useruuid) {
        log.infof("ProjectDescription add consultant: projectDescUuid=%s, consultantUuid=%s by user=%s",
                uuid, useruuid, requestHeaderHolder.getUserUuid());
        knowledgeProjectAPI.addProjectDescriptionUser(uuid, useruuid);
    }

    @DELETE
    @Path("/{projectdesc_uuid}/consultants/{useruuid}")
    @RolesAllowed({"knowledge:write"})
    public void removeProjectDescriptionUser(@PathParam("projectdesc_uuid") String uuid, @PathParam("useruuid") String useruuid) {
        log.infof("ProjectDescription remove consultant: projectDescUuid=%s, consultantUuid=%s by user=%s",
                uuid, useruuid, requestHeaderHolder.getUserUuid());
        knowledgeProjectAPI.removeProjectDescriptionUser(uuid, useruuid);
    }

    @DELETE
    @Path("/{projectdesc_uuid}/consultants")
    @RolesAllowed({"knowledge:write"})
    public void removeProjectDescriptionUsers(@PathParam("projectdesc_uuid") String uuid) {
        log.infof("ProjectDescription remove all consultants: projectDescUuid=%s by user=%s",
                uuid, requestHeaderHolder.getUserUuid());
        knowledgeProjectAPI.removeProjectDescriptionUsers(uuid);
    }

}
