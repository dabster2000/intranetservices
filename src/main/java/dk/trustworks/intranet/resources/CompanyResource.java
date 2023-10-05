package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.Company;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.util.List;

@JBossLog
@Path("/companies")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class CompanyResource {

    @GET
    public List<Company> findAllCompanies() {
        return Company.listAll();
    }
}
