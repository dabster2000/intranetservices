package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.services.AppSettingService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@ApplicationScoped
@JBossLog
@Path("/app-settings")
@RolesAllowed({"dashboard:read"})
@SecurityRequirement(name = "jwt")
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class AppSettingResource {

    @Inject
    AppSettingService appSettingService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public List<AppSetting> findByCategory(@QueryParam("category") String category) {
        if (category == null || category.isBlank()) {
            throw new BadRequestException("Query parameter 'category' is required");
        }
        return appSettingService.findByCategory(category);
    }

    @PUT
    @RolesAllowed({"admin:write"})
    public void updateSetting(Map<String, String> body) {
        String settingKey = body.get("settingKey");
        String settingValue = body.get("settingValue");
        String category = body.get("category");

        if (settingKey == null || settingKey.isBlank()) {
            throw new BadRequestException("settingKey is required");
        }
        if (settingValue == null || settingValue.isBlank()) {
            throw new BadRequestException("settingValue is required");
        }

        String updatedBy = requestHeaderHolder.getUserUuid();
        log.infof("AppSettingResource.updateSetting key=%s, category=%s, updatedBy=%s",
                settingKey, category, updatedBy);
        appSettingService.saveSetting(settingKey, settingValue, category, updatedBy);
    }
}
