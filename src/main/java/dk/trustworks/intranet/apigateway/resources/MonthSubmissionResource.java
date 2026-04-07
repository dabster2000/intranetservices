package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.dao.workservice.model.MonthSubmission;
import dk.trustworks.intranet.dao.workservice.services.MonthSubmissionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "time")
@Path("/month-submissions")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"timeregistration:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class MonthSubmissionResource {

    @Inject
    MonthSubmissionService monthSubmissionService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    public MonthSubmission getSubmission(
            @QueryParam("useruuid") String useruuid,
            @QueryParam("year") int year,
            @QueryParam("month") int month) {
        log.debugf("GET /month-submissions: user=%s, year=%d, month=%d", useruuid, year, month);
        return monthSubmissionService.findByUserAndMonth(useruuid, year, month).orElse(null);
    }

    @POST
    @Path("/submit")
    @RolesAllowed({"timeregistration:write"})
    public MonthSubmission submit(SubmitRequest request) {
        String requestedBy = requestHeaderHolder.getUserUuid();
        log.infof("POST /month-submissions/submit: user=%s, year=%d, month=%d, requestedBy=%s",
                request.useruuid, request.year, request.month, requestedBy);

        if (!request.useruuid.equals(requestedBy)) {
            log.infof("Submitting on behalf of another user: target=%s, requestedBy=%s", request.useruuid, requestedBy);
        }

        return monthSubmissionService.submit(request.useruuid, request.year, request.month);
    }

    @POST
    @Path("/unlock")
    @RolesAllowed({"timeregistration:admin"})
    public MonthSubmission unlock(UnlockRequest request) {
        String unlockedBy = requestHeaderHolder.getUserUuid();
        log.infof("POST /month-submissions/unlock: user=%s, year=%d, month=%d, by=%s",
                request.useruuid, request.year, request.month, unlockedBy);
        return monthSubmissionService.unlock(
                request.useruuid, request.year, request.month, unlockedBy, request.reason);
    }

    public static class SubmitRequest {
        public String useruuid;
        public int year;
        public int month;
    }

    public static class UnlockRequest {
        public String useruuid;
        public int year;
        public int month;
        public String reason;
    }
}
