package dk.trustworks.intranet.aggregates.consultant.resources;

import dk.trustworks.intranet.aggregates.consultant.dto.ConsultantProfileDTO;
import dk.trustworks.intranet.aggregates.consultant.services.ConsultantProfileService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST resource for AI-generated consultant profiles.
 *
 * <p>Accepts a comma-separated list of user UUIDs and returns cached
 * (or freshly generated) sales profiles for each consultant.
 */
@Tag(name = "Consultant Profiles")
@Path("/api/consultants/profiles")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
@JBossLog
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class ConsultantProfileResource {

    private static final int MAX_UUIDS = 10;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Inject
    ConsultantProfileService consultantProfileService;

    @GET
    public Response getProfiles(@QueryParam("uuids") String uuids) {
        if (uuids == null || uuids.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Query parameter 'uuids' is required"))
                    .build();
        }

        List<String> uuidList = Arrays.stream(uuids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (uuidList.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "At least one UUID must be provided"))
                    .build();
        }

        if (uuidList.size() > MAX_UUIDS) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Maximum " + MAX_UUIDS + " UUIDs per request"))
                    .build();
        }

        for (String uuid : uuidList) {
            if (!UUID_PATTERN.matcher(uuid).matches()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid UUID format"))
                        .build();
            }
        }

        List<ConsultantProfileDTO> profiles = consultantProfileService.getProfiles(uuidList);
        return Response.ok(profiles).build();
    }
}
