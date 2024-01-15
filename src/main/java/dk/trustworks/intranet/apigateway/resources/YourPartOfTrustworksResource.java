package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.v2.AvailabilityCalculatingExecutor;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "bonus")
@Path("/bonus/yourpartoftrustworks")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class YourPartOfTrustworksResource {

    @Inject
    AvailabilityCalculatingExecutor availabilityCalculatingExecutor;

    @GET
    public List<EmployeeBonusEligibility> findByFiscalStartYear(@QueryParam("fiscalstartyear") int year) {
        return EmployeeBonusEligibility.list("year = ?1", year);
    }

    @GET
    @Path("/reload")
    public void reload() {
        availabilityCalculatingExecutor.recalculateAvailability();
    }
}