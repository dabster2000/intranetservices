package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.dto.LoginTokenResult;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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

    @GET
    @Path("/validate")
    @PermitAll
    @Operation(
            summary = "Validate JWT token",
            description = "Validates a JWT token and returns information about its validity and associated user"
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Token validation result",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = LoginTokenResult.class)
                    )
            )
    })
    public LoginTokenResult validateToken(
            @Parameter(
                    description = "JWT token to validate",
                    required = true,
                    example = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJodHRwczovL3RydXN0d29ya3MuZGsiLCJ1cG4iOiJ1c2VybmFtZSIsImlhdCI6MTYwMDAwMDAwMCwiZXhwIjoxNjAwMDM2MDAwLCJncm91cHMiOlsiVVNFUiJdfQ.signature"
            )
            @QueryParam("token") String token
    ) {
        log.info("LoginResource.validateToken");
        try {
            return userAPI.validateToken(token);
        } catch (Exception e) {
            log.error("Token validation failed", e);
            return new LoginTokenResult("", "", false, "Token validation failed: " + e.getMessage(), new ArrayList<>());
        }
    }
}