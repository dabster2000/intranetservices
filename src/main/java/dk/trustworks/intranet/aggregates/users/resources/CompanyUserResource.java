package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
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
import java.util.Optional;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@Tag(name = "user")
@Path("/company/{companyuuid}/users")
@JBossLog
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class CompanyUserResource {

    @PathParam("companyuuid")
    private String companyuuid;

    @Inject
    UserService userService;

    @GET
    public List<User> listAll(@QueryParam("shallow") Optional<String> shallow) {
        boolean shallowValue = Boolean.getBoolean(shallow.orElse("false"));
        return userService.clearSalaries(userService.listAllByCompany(companyuuid, shallowValue));
    }

    @GET
    @Path("/search/findUsersByDateAndStatusListAndTypes")
    public List<User> findUsersByDateAndStatusListAndTypes(@QueryParam("date") String date, @QueryParam("consultantStatusList") String statusList, @QueryParam("consultantTypes") String consultantTypes, @QueryParam("shallow") Optional<String> shallow) {
        boolean shallowValue = Boolean.getBoolean(shallow.orElse("false"));
        return userService.clearSalaries(userService.findUsersByDateAndStatusListAndTypesAndCompany(companyuuid, dateIt(date), statusList, consultantTypes, shallowValue));
    }
}
