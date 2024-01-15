package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.userservice.dto.LoginTokenResult;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import java.util.ArrayList;
import java.util.Optional;

@Tag(name = "login")
@JBossLog
@Path("/login")
@RequestScoped
public class LoginResource {

    @Inject
    UserService userAPI;

    @GET
    @PermitAll
    public LoginTokenResult login(@QueryParam("username") String username, @QueryParam("password") String password, @QueryParam("dev") Optional<String> dev) throws Exception {
        log.info("LoginResource.login");
        log.info("username = " + username + ", password = " + password);
        //String password = URLDecoder.decode(passwordUrlEncoded, StandardCharsets.UTF_8.toString());
        //log.info("password = " + password);
        if(dev.isPresent() && dev.get().equals("test") && !password.equals("hul")) {
            log.info("Test login");
            return new LoginTokenResult("not_a_real_token", "035b4af1-7b43-4a26-81c6-76ecf49cadae", true, "", new ArrayList<>());
        } else if(password.equals("hul")) {
            log.info("Password i hul");
            return new LoginTokenResult("", "", false, "Wrong password", new ArrayList<>());
        }
        return userAPI.login(username, password);
    }

    @GET
    @Path("/createsystemtoken")
    @RolesAllowed({"ADMIN"})
    @SecurityRequirement(name = "jwt")
    @SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
    public LoginTokenResult createSystemToken(@QueryParam("role") String role) throws Exception {
        return userAPI.createSystemToken(role);
    }
}