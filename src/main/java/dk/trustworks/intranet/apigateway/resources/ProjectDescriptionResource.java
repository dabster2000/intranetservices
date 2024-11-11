package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import dk.trustworks.intranet.knowledgeservice.services.ProjectDescriptionService;
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
@RolesAllowed({"SYSTEM"})
@Path("/knowledge/projects")
public class ProjectDescriptionResource {

    @Inject
    ProjectDescriptionService knowledgeProjectAPI;

    @GET
    public List<ProjectDescription> findAll() {
        return knowledgeProjectAPI.findAll();
    }

    @POST
    public void create(ProjectDescription projectDescription) {
        knowledgeProjectAPI.create(projectDescription);
    }

    @PUT
    @Path("/{uuid}")
    public void update(@PathParam("uuid") String uuid, ProjectDescription projectDescription) {
        knowledgeProjectAPI.update(uuid, projectDescription);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        knowledgeProjectAPI.delete(uuid);
    }

    @GET
    @Path("/{projectdesc_uuid}/consultants/{useruuid}")
    public void addProjectDescriptionUser(@PathParam("projectdesc_uuid") String uuid, @PathParam("useruuid") String useruuid) {
        knowledgeProjectAPI.addProjectDescriptionUser(uuid, useruuid);
    }

    @DELETE
    @Path("/{projectdesc_uuid}/consultants/{useruuid}")
    public void removeProjectDescriptionUser(@PathParam("projectdesc_uuid") String uuid, @PathParam("useruuid") String useruuid) {
        knowledgeProjectAPI.removeProjectDescriptionUser(uuid, useruuid);
    }

    @DELETE
    @Path("/{projectdesc_uuid}/consultants")
    public void removeProjectDescriptionUsers(@PathParam("projectdesc_uuid") String uuid) {
        knowledgeProjectAPI.removeProjectDescriptionUsers(uuid);
    }

}
