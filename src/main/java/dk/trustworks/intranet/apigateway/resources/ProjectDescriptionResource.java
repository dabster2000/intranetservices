package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import dk.trustworks.intranet.knowledgeservice.services.ProjectDescriptionService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.List;

@Tag(name = "Knowledge")
@JBossLog
@SecurityRequirement(name = "jwt")
@ClientHeaderParam(name="Authorization", value="{generateRequestId}")
@RolesAllowed({"SYSTEM", "USER"})
@Path("/knowledge/projects")
public class ProjectDescriptionResource {

    @Inject
    ProjectDescriptionService knowledgeProjectAPI;

    @GET
    public List<ProjectDescription> findAll() {
        return knowledgeProjectAPI.findAll();
    }

}
