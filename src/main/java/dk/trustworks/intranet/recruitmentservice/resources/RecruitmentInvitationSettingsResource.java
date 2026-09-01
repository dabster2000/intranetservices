package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.InvitationSettingsRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InvitationSettingsResponse;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentVisitingAddress;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for the interview-invitation settings on
 * {@code /recruitment/settings}: today, the visiting address the candidate's
 * calendar invitation tells them to turn up at.
 *
 * <p>Thin by convention: flag gate → actor resolution → tier check →
 * delegate to {@link RecruitmentVisitingAddress}.
 *
 * <p>Its own resource rather than a third field on {@code /email-settings}:
 * the {@code INTERVIEW_CANDIDATE_INVITATION} body is the one candidate-facing
 * text that is NOT sent as an email — it is an Outlook calendar event body
 * written by {@code RecruitmentCalendarService} — so none of the sender,
 * reply-to or review-queue machinery beside it applies here.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Recruiter tier ({@code ADMIN}/{@code HR}/{@code RECRUITMENT} via
 *       {@link RecruitmentVisibility#isRecruiterTier}) on BOTH verbs: this
 *       configures what every candidate reads, the same grain as the email
 *       templates and the sender identity. 404-not-403, the sibling-resource
 *       convention.</li>
 *   <li>Scopes: class-level {@code recruitment:read}, {@code recruitment:write}
 *       on the update.</li>
 *   <li>Feature flag {@code recruitment.interviews.enabled}: off + non-admin
 *       caller → 404, so the feature's existence never leaks; admins bypass
 *       for dark testing.</li>
 *   <li>Input cap enforced explicitly here — {@code @Valid} is inert in this
 *       repo (§P4.9).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment/invitation-settings")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentInvitationSettingsResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentVisitingAddress visitingAddress;

    @GET
    public InvitationSettingsResponse settings() {
        enforceFlag();
        requireRecruiterTier(currentActor());
        return response();
    }

    /**
     * Partial update: a {@code null} field is left alone, an empty field is
     * cleared. Blanking the address is a supported choice — invitations then
     * carry neither an address nor arrival instructions, exactly as they did
     * before this setting existed.
     */
    @PUT
    @RolesAllowed({"recruitment:write"})
    public InvitationSettingsResponse update(InvitationSettingsRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        Objects.requireNonNull(request, "request body must not be null");
        if (request.visitingAddress() != null) {
            if (request.visitingAddress().length() > RecruitmentVisitingAddress.MAX_LENGTH) {
                throw badRequest("visitingAddress exceeds "
                        + RecruitmentVisitingAddress.MAX_LENGTH + " characters");
            }
            visitingAddress.update(request.visitingAddress(), actor.toString());
        }
        return response();
    }

    private InvitationSettingsResponse response() {
        return new InvitationSettingsResponse(
                visitingAddress.editableAddress(),
                RecruitmentVisitingAddress.defaultAddress(),
                visitingAddress.isDefault());
    }

    private void enforceFlag() {
        if (featureFlag.isInterviewsEnabled()) {
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

    /**
     * Configuring what every candidate reads is a recruiter surface, the
     * same tier that owns the email templates. 404-not-403 keeps existence
     * hidden.
     */
    private void requireRecruiterTier(UUID actor) {
        if (!visibility.isRecruiterTier(actor.toString())) {
            throw new NotFoundException("Resource not found");
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
