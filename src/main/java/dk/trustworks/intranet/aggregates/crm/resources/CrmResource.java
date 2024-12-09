package dk.trustworks.intranet.aggregates.crm.resources;

import dk.trustworks.intranet.aggregates.crm.model.ConsultantContract;
import dk.trustworks.intranet.aggregates.crm.services.CrmService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Crm Insights")
@JBossLog
@Path("/company/{companyuuid}/crm")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class CrmResource {

    @PathParam("companyuuid")
    private String companyuuid;

    @Inject
    CrmService crmService;

    @GET
    @Path("")
    public List<ConsultantContract> findContractsOverTime() {
        return crmService.getContractsOverTime(companyuuid);
    }

}
