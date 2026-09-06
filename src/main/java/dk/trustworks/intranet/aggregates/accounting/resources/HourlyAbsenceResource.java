package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.accounting.dto.HourlyAbsenceDTO;
import dk.trustworks.intranet.aggregates.accounting.services.HourlyAbsenceService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * {@code GET /users/hourly-absence?month=yyyy-MM-dd[&companyuuid=]} — the sick-hours
 * worksheet for hourly-paid employees (JK Team 2.0 WP2, spec §4.2.3), gated exactly like the
 * rest of the salary-payment page ({@code salaries:read}). Read-only.
 */
@Tag(name = "Hourly Absence")
@JBossLog
@Path("/users")
@RequestScoped
@Produces(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"salaries:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class HourlyAbsenceResource {

    @Inject
    HourlyAbsenceService hourlyAbsenceService;

    @GET
    @Path("/hourly-absence")
    public List<HourlyAbsenceDTO> hourlyAbsence(@QueryParam("month") String month,
                                                @QueryParam("companyuuid") String companyuuid) {
        if (month == null || month.isBlank()) {
            throw new WebApplicationException("month is required (yyyy-MM-dd)", Response.Status.BAD_REQUEST);
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new WebApplicationException("month must be an ISO date (yyyy-MM-dd)", Response.Status.BAD_REQUEST);
        }
        String company = companyuuid == null || companyuuid.isBlank() ? null : companyuuid.trim();
        return hourlyAbsenceService.forMonth(parsed, company);
    }
}
