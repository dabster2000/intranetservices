package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.knowledgeservice.model.Certification;
import dk.trustworks.intranet.knowledgeservice.model.UserCertification;
import dk.trustworks.intranet.knowledgeservice.services.CertificationService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.List;

@JBossLog
@Tag(name = "Knowledge")
@Path("/knowledge/certifications")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class KnowledgeResource {

    @Inject
    CertificationService certificationService;

    @GET
    @Path("/types")
    public List<Certification> findAllCertificationTypes() {
        return certificationService.findAll();
    }

    @GET
    public List<UserCertification> findAllCertifications() {
        return certificationService.findAllUserCertifications();
    }

}