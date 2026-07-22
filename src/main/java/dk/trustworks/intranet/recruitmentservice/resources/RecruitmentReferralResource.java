package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.MyReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageResponse;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for the referral channel (ATS plan §P6, spec §6.2) —
 * the only recruitment surface most employees ever touch. Thin by
 * convention: flag gate → actor resolution → tier check → delegate to
 * {@link ReferralService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:refer"})} — the
 *       all-employee scope (spec §7.1). The recruiter-side endpoints
 *       override with {@code recruitment:read}/{@code recruitment:write}
 *       AND additionally enforce the recruiter tier per-user via
 *       {@link RecruitmentVisibility#isRecruiterTier} — backend scopes
 *       cannot distinguish employees (the BFF holds {@code admin:*}).</li>
 *   <li>{@code /mine} returns only the caller's rows and a deliberately
 *       minimal DTO: no candidate uuid, no position facts, no stage
 *       codes — the referrer never gets a handle to the candidate.</li>
 *   <li>Feature flag {@code recruitment.pipeline.enabled}: off +
 *       non-admin caller → 404 (same convention as the sibling
 *       resources).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment/referrals")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:refer"})
public class RecruitmentReferralResource {

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
    ReferralService referralService;

    // ---- Employee side ---------------------------------------------------------

    /** Submit a referral (the 60-second form). 201 with the new uuid. */
    @POST
    public Response submit(ReferralCreateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        UUID actor = currentActor();
        ReferralCreateResponse created = referralService.submit(request, actor);
        return Response.created(URI.create("/recruitment/referrals/" + created.uuid()))
                .entity(created)
                .build();
    }

    /** The caller's own referrals with live milestone statuses, newest first. */
    @GET
    @Path("/mine")
    public MyReferralsResponse mine() {
        enforceFlag();
        return referralService.listMine(currentActor());
    }

    // ---- Recruiter side --------------------------------------------------------

    /** The triage queue: SUBMITTED referrals, oldest first. Recruiter tier only. */
    @GET
    @Path("/pending")
    @RolesAllowed({"recruitment:read"})
    public PendingReferralsResponse pending() {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        return referralService.listPending(actor);
    }

    /**
     * One-shot triage decision: create a candidate (201 with a Location to
     * the new candidate; optionally attaches an application) or dismiss
     * (200). A referral that is no longer SUBMITTED answers 409.
     */
    @POST
    @Path("/{uuid}/triage")
    @RolesAllowed({"recruitment:write"})
    public Response triage(@PathParam("uuid") UUID referralUuid, ReferralTriageRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        ReferralTriageResponse result = referralService.triage(referralUuid, request, actor);
        if (result.candidateUuid() != null) {
            return Response.created(URI.create("/recruitment/candidates/" + result.candidateUuid()))
                    .entity(result)
                    .build();
        }
        return Response.ok(result).build();
    }

    // ---- Helpers --------------------------------------------------------------------

    /**
     * The intake queues are recruiter-tier surfaces (spec §7.2): ADMIN, HR
     * or CXO role — a teamlead's pipeline involvement does not include the
     * raw referral facts.
     */
    private void requireRecruiterTier(UUID actor) {
        if (!visibility.isRecruiterTier(actor.toString())) {
            throw new WebApplicationException(
                    "Referral triage is reserved for the recruiter tier",
                    Response.Status.FORBIDDEN);
        }
    }

    /**
     * Block the request when {@code recruitment.pipeline.enabled} is off,
     * unless the caller holds {@code admin:*}. 404 (not 503) to avoid
     * leaking the feature's existence — same convention as the sibling
     * recruitment resources.
     */
    private void enforceFlag() {
        if (featureFlag.isPipelineEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /**
     * Resolve the acting user from {@code X-Requested-By} (set by
     * {@code HeaderInterceptor}). 400 when absent — every rule on this
     * resource is per-user.
     */
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
