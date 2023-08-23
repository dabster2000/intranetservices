package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.ws.rs.*;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "bonus")
@Path("/bonus/yourpartoftrustworks")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"PARTNER"})
@SecurityRequirement(name = "jwt")
public class YourPartOfTrustworksResource {

    @GET
    public List<EmployeeBonusEligibility> findByFiscalStartYear(@QueryParam("fiscalstartyear") int year) {
        return EmployeeBonusEligibility.list("year = ?1", year);
    }
}