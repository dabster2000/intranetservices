package dk.trustworks.intranet.apigateway.resources.gate;


import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import java.util.List;

import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.*;

@JBossLog
@Path("/public/stats/")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"USER", "APPLICATION"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class PublicStatisticsResource {

    @Inject
    UserService userService;

    @GET
    @Path("/employees/headcount/{date}")
    public KeyValueDTO countEmployees(@PathParam("date") @Parameter(name = "date", description = "The date on which to count employees", required = true, example = "2024-01-01") String date) {
        List<User> users = userService.findEmployedUsersByDate(DateUtils.dateIt(date), true, CONSULTANT, STAFF, STUDENT);
        return new KeyValueDTO("headcount", ""+users.size());
    }
}
