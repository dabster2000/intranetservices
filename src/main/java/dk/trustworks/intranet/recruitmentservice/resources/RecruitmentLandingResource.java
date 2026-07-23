package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentLandingService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.UUID;

/**
 * REST entry point for the role-aware landing page (ATS plan §P17, spec
 * §6.1 {@code /recruitment}): ONE aggregated read per page load — KPI row,
 * "My tasks" in urgency order, "Your pipelines" and the visibility-filtered
 * activity feed, all shaped per caller by
 * {@link RecruitmentLandingService}. The {@code tasks} section is the
 * future Slack App Home source (P23).
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Feature flag {@code recruitment.pipeline.enabled} (core flag 1 —
 *       plan §P17 places the landing with the pipeline surfaces): off +
 *       non-admin caller → 404, the sibling-resource convention; admins
 *       bypass for dark testing.</li>
 *   <li>No role gate beyond authentication: EVERY employee may call this
 *       (spec §6.1 — "everyone with any recruitment involvement"; an
 *       interviewer is a plain employee). The response is authorization:
 *       each section is computed from the caller's involvement through
 *       {@code RecruitmentVisibility} (partner circles stay a hard
 *       filter), and a caller with no involvement gets the
 *       {@code EMPLOYEE} shape with empty sections — the client redirects
 *       them to {@code /recruitment/refer}.</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentLandingResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentLandingService landingService;

    @GET
    @Path("/landing")
    public LandingResponse landing() {
        enforceFlag();
        UUID actor = currentActor();
        return landingService.build(actor.toString());
    }

    private void enforceFlag() {
        if (featureFlag.isPipelineEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By header is required",
                    Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "X-Requested-By is not a valid UUID",
                    Response.Status.BAD_REQUEST);
        }
    }
}
