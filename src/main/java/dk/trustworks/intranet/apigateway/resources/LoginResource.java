package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.userservice.dto.LoginTokenResult;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;

@Tag(name = "login")
@JBossLog
@Path("/login")
@RequestScoped
public class LoginResource {

    @Inject
    UserService userAPI;

    /**
     * Kill switch for the legacy username/password login endpoint ({@code GET /login}).
     *
     * <p>The endpoint is {@code @PermitAll} and takes credentials as query parameters, which means
     * every call writes the plaintext password into the HTTP access log. It is disabled by default.
     *
     * <p>To re-enable: flip {@code defaultValue} to {@code "true"} here, or set
     * {@code login.legacy-endpoint.enabled=true} (env {@code LOGIN_LEGACY_ENDPOINT_ENABLED}).
     * While disabled, the endpoint answers {@code 404} so it is indistinguishable from a route
     * that does not exist.
     */
    @ConfigProperty(name = "login.legacy-endpoint.enabled", defaultValue = "false")
    boolean legacyLoginEnabled;

    @GET
    @PermitAll
    public LoginTokenResult login(@QueryParam("username") String username, @QueryParam("password") String password) throws Exception {
        if (!legacyLoginEnabled) {
            throw new NotFoundException();
        }
        log.info("LoginResource.login");
        log.info("username = " + username + ", password = " + password);
        return userAPI.login(username, password);
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