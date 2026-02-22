package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.Company;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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

    @GET
    @Path("/{uuid}")
    public Company findByUuid(@PathParam("uuid") String uuid) {
        return Company.findById(uuid);
    }
}
