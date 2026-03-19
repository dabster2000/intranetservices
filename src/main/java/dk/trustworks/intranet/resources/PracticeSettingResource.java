package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.PracticeSetting;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.services.PracticeSettingService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@ApplicationScoped
@JBossLog
@Path("/practice-settings")
@RolesAllowed({"admin:read"})
@SecurityRequirement(name = "jwt")
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class PracticeSettingResource {

    private static final Set<String> VALID_PRACTICES = Set.of("SA", "BA", "PM", "DEV", "CYB", "JK", "UD");
    private static final Set<String> VALID_SETTING_KEYS = Set.of("it_budget");

    @Inject
    PracticeSettingService practiceSettingService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public List<PracticeSetting> findAll() {
        return practiceSettingService.findAll();
    }

    @GET
    @Path("/{practice}/it-budget")
    @RolesAllowed({"devices:read"})
    public Map<String, Integer> getItBudget(@PathParam("practice") String practice) {
        if (!VALID_PRACTICES.contains(practice)) {
            throw new BadRequestException("Invalid practice: " + practice);
        }
        int budget = practiceSettingService.getItBudget(practice);
        return Map.of("itBudget", budget);
    }

    @PUT
    @Path("/{practice}")
    @RolesAllowed({"admin:write"})
    public void updateSettings(@PathParam("practice") String practice, Map<String, String> settings) {
        if (!VALID_PRACTICES.contains(practice)) {
            throw new BadRequestException("Invalid practice: " + practice);
        }
        String updatedBy = requestHeaderHolder.getUserUuid();
        log.infof("PracticeSettingResource.updateSettings practice=%s, updatedBy=%s, keyCount=%d",
                practice, updatedBy, settings.size());
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            if (!VALID_SETTING_KEYS.contains(entry.getKey())) {
                throw new BadRequestException("Invalid setting key: " + entry.getKey());
            }
            practiceSettingService.saveSetting(practice, entry.getKey(), entry.getValue(), updatedBy);
        }
    }
}
