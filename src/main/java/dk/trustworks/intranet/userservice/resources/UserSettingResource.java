package dk.trustworks.intranet.userservice.resources;

import dk.trustworks.intranet.userservice.model.UserSetting;
import dk.trustworks.intranet.userservice.services.UserSettingService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@JBossLog
@Path("/users/{useruuid}/settings")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM", "USER"})
public class UserSettingResource {

    @Inject
    UserSettingService userSettingService;

    /**
     * Get all settings for a user.
     *
     * @param userUuid User UUID
     * @return List of all settings for the user
     */
    @GET
    public List<UserSetting> getAllSettings(@PathParam("useruuid") String userUuid) {
        log.infof("GET /users/%s/settings", userUuid);
        return userSettingService.findAllByUser(userUuid);
    }

    /**
     * Get a specific setting for a user.
     *
     * @param userUuid User UUID
     * @param key Setting key
     * @return The setting if found, 404 otherwise
     */
    @GET
    @Path("/{key}")
    public Response getSetting(@PathParam("useruuid") String userUuid,
                               @PathParam("key") String key) {
        log.infof("GET /users/%s/settings/%s", userUuid, key);

        Optional<UserSetting> setting = userSettingService.findByUserAndKey(userUuid, key);

        if (setting.isPresent()) {
            return Response.ok(setting.get()).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Setting not found"))
                    .build();
        }
    }

    /**
     * Create or update a user setting.
     *
     * @param userUuid User UUID
     * @param key Setting key
     * @param request Request body containing the setting value
     * @return The created or updated setting
     */
    @PUT
    @Path("/{key}")
    @Transactional
    public UserSetting upsertSetting(@PathParam("useruuid") String userUuid,
                                     @PathParam("key") String key,
                                     Map<String, String> request) {
        log.infof("PUT /users/%s/settings/%s", userUuid, key);

        String value = request.get("value");
        if (value == null) {
            throw new WebApplicationException("Request body must contain 'value' field", Response.Status.BAD_REQUEST);
        }

        return userSettingService.upsert(userUuid, key, value);
    }

    /**
     * Delete a user setting.
     *
     * @param userUuid User UUID
     * @param key Setting key
     * @return 204 No Content if deleted, 404 if not found
     */
    @DELETE
    @Path("/{key}")
    @Transactional
    public Response deleteSetting(@PathParam("useruuid") String userUuid,
                                  @PathParam("key") String key) {
        log.infof("DELETE /users/%s/settings/%s", userUuid, key);

        boolean deleted = userSettingService.delete(userUuid, key);

        if (deleted) {
            return Response.noContent().build();
        } else {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Setting not found"))
                    .build();
        }
    }
}
