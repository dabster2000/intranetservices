package dk.trustworks.intranet.aggregates.availability.resources;

import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.resources.CompanyResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@Tag(name = "Company Availabilities")
@Path("/companies")
@JBossLog
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class CompanyAvailabilityResource {

    @Inject
    AvailabilityService availabilityService;

    @Inject
    CompanyResource companyResource;

    @GET
    @Path("/availabilities")
    public List<DateValueDTO> getBudgetAmountsPerMonth(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {

        return null;
    }

    @GET
    @Path("/{companyuuid}/users/availabilities")
    public List<EmployeeAvailabilityPerMonth> getUserAvailabilitiesInCompany(@PathParam ("companyuuid") String companyuuid, @QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return availabilityService.getCompanyEmployeeAvailabilityByPeriod(Company.findById(companyuuid), dateIt(fromdate), dateIt(todate));
    }

}