package dk.trustworks.intranet.apigateway.resources.gate;

import dk.trustworks.intranet.knowledgeservice.model.ProjectDescription;
import dk.trustworks.intranet.knowledgeservice.services.ProjectDescriptionService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import java.util.List;

@Tag(name = "Knowledge")
@JBossLog
@SecurityRequirement(name = "jwt")
@ClientHeaderParam(name="Authorization", value="{generateRequestId}")
@RolesAllowed({"SYSTEM", "APPLICATION"})
@Path("/public/knowledge/projects")
public class PublicProjectDescriptionResource {

    @Inject
    ProjectDescriptionService knowledgeProjectAPI;

    @GET
    public List<ProjectDescription> findAll() {
        return knowledgeProjectAPI.findAll();
    }

}
