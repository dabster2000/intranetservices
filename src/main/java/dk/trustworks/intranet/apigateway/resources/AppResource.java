package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.appplatform.model.App;
import dk.trustworks.intranet.appplatform.model.AppMember;
import dk.trustworks.intranet.appplatform.model.AppRole;
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
@RolesAllowed({"DEVELOPER", "SYSTEM"})
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AppResource {

    @Inject
    AppService appService;

    @POST
    @Transactional
    public App createApp(Map<String, String> request, @HeaderParam("X-Requested-By") String userUuid) {
        log.info("Creating app name=" + request.get("name") + " user=" + userUuid);
        return appService.createApp(request.get("name"), userUuid);
    }

    @GET
    public List<App> listApps(@QueryParam("user") String userUuid,
                              @QueryParam("page") @DefaultValue("0") int page,
                              @QueryParam("size") @DefaultValue("50") int size) {
        log.debug("Listing apps for user=" + userUuid + " page=" + page + " size=" + size);
        if(page >= 0 && size > 0) {
            return appService.listAppsForUser(userUuid, page, size);
        }
        return appService.listAppsForUser(userUuid);
    }

    @GET
    @Path("/count")
    public long countApps(@QueryParam("user") String userUuid) {
        log.debug("Counting apps for user=" + userUuid);
        return appService.countAppsForUser(userUuid);
    }

    @POST
    @Path("/{app}/tokens")
    @Transactional
    //@RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public Map<String, String> createToken(@PathParam("app") String appUuid) {
        log.info("Creating token for app=" + appUuid);
        String refresh = appService.createToken(appUuid, 900, 30 * 24 * 3600);
        return Map.of("refreshToken", refresh);
    }

    @DELETE
    @Path("/{app}/tokens/{token}")
    @Transactional
    //@RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public void revoke(@PathParam("token") String tokenId) {
        log.info("Revoking token " + tokenId);
        appService.revokeToken(tokenId);
    }

    @GET
    @Path("/{app}/members")
    //@RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public List<AppMember> listMembers(@PathParam("app") String appUuid) {
        log.debug("Listing members for app=" + appUuid);
        return appService.listMembers(appUuid);
    }

    @POST
    @Path("/{app}/members/{user}")
    @Transactional
    //@RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public void addMember(@PathParam("app") String appUuid, @PathParam("user") String userUuid) {
        log.info("Adding member " + userUuid + " to app " + appUuid);
        appService.addMember(appUuid, userUuid);
    }

    @PUT
    @Path("/{app}/members/{user}")
    @Transactional
    //@RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public void changeRole(@PathParam("app") String appUuid,
                           @PathParam("user") String userUuid,
                           @QueryParam("role") String role) {
        log.info("Changing role for user=" + userUuid + " to " + role + " in app=" + appUuid);
        appService.changeRole(appUuid, userUuid, AppRole.valueOf(role));
    }

    @DELETE
    @Path("/{app}/members/{user}")
    @Transactional
    //@RolesAllowed({"DEVELOPER","APP_ADMIN"})
    public void removeMember(@PathParam("app") String appUuid, @PathParam("user") String userUuid) {
        log.info("Removing member " + userUuid + " from app " + appUuid);
        appService.removeMember(appUuid, userUuid);
    }
}
