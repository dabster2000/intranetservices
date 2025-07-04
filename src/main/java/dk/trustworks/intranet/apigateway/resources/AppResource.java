package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.appplatform.model.App;
import dk.trustworks.intranet.appplatform.services.AppService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@JBossLog
@Tag(name = "apps")
@Path("/apps")
@RequestScoped
@RolesAllowed({"DEVELOPER"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AppResource {

    @Inject
    AppService appService;

    @POST
    @Transactional
    public App createApp(Map<String, String> request, @HeaderParam("X-User") String userUuid) {
        log.info("Creating app name=" + request.get("name") + " user=" + userUuid);
        return appService.createApp(request.get("name"), userUuid);
    }

    @GET
    public List<App> listApps(@QueryParam("user") String userUuid) {
        log.debug("Listing apps for user=" + userUuid);
        return appService.listAppsForUser(userUuid);
    }

    @POST
    @Path("/{app}/tokens")
    @Transactional
    @RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public Map<String, String> createToken(@PathParam("app") String appUuid) {
        String refresh = appService.createToken(appUuid, 900, 30 * 24 * 3600);
        return Map.of("refreshToken", refresh);
    }

    @DELETE
    @Path("/{app}/tokens/{token}")
    @Transactional
    @RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public void revoke(@PathParam("token") String tokenId) {
        appService.revokeToken(tokenId);
    }
}
