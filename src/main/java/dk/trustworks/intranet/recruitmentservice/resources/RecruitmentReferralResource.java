package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.MyReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PendingReferralsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCvDownload;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCvResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralCreateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ReferralTriageResponse;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import dk.trustworks.intranet.recruitmentservice.util.PublicApplyDocuments;
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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
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

    /**
     * Attach the OPTIONAL CV to a referral the caller just submitted
     * (2026-09-02). Multipart, one field: {@code cv}.
     * <p>
     * A separate request rather than a field on the submit body, on
     * purpose: the CV is an afterthought to the referral, and a failed
     * upload (bad type, oversize, flaky connection) must never cost the
     * employee the referral they already wrote. The form posts this
     * straight after the 201 and tells the user if only this leg failed.
     * <p>
     * Same file guard as the public apply forms and the Documents-tab
     * upload — {@link PublicApplyDocuments}: PDF/JPEG/PNG by MIME
     * allowlist AND magic bytes, 10 MB cap, positive-allowlist filename
     * sanitiser. {@code ReferralService} owns the rest: only the
     * submitting employee (404 otherwise), only while SUBMITTED (409),
     * and a re-upload replaces rather than accumulates.
     */
    @POST
    @Path("/{uuid}/cv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response attachCv(@PathParam("uuid") UUID referralUuid,
                             @RestForm("cv") FileUpload cv) {
        enforceFlag();
        if (cv == null || cv.size() == 0) {
            throw new WebApplicationException("cv is required", Response.Status.BAD_REQUEST);
        }
        if (cv.size() > PublicApplyDocuments.MAX_BYTES) {
            throw new WebApplicationException("File exceeds the 10 MB limit",
                    Response.Status.BAD_REQUEST);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(cv.uploadedFile());
        } catch (IOException e) {
            log.errorf(e, "Failed to read uploaded CV bytes for referral=%s", referralUuid);
            throw new WebApplicationException("Failed to read the uploaded file",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
        String contentType = PublicApplyDocuments.normaliseContentType(cv.contentType());
        if (!PublicApplyDocuments.ALLOWED_MIME_TYPES.contains(contentType)
                || !PublicApplyDocuments.magicMatches(contentType, bytes)) {
            throw new WebApplicationException("Only PDF, JPEG and PNG files are accepted",
                    Response.Status.UNSUPPORTED_MEDIA_TYPE);
        }
        String rawName = cv.fileName();
        String safeName = PublicApplyDocuments.sanitiseFilename(rawName);
        if (safeName.isBlank()) {
            safeName = "cv" + switch (contentType) {
                case "application/pdf" -> ".pdf";
                case "image/jpeg" -> ".jpg";
                default -> ".png";
            };
        }
        String piiName = rawName == null || rawName.isBlank()
                ? safeName
                : (rawName.length() > 255 ? rawName.substring(0, 255) : rawName);
        ReferralCvResponse stored = referralService.attachCv(
                referralUuid, currentActor(), bytes, contentType, safeName, piiName);
        return Response.status(Response.Status.CREATED).entity(stored).build();
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
        requireInboxTier(actor);
        return referralService.listPending(actor);
    }

    /**
     * Stream the CV attached to a pending referral so the recruiter can read
     * it WHILE triaging — the whole point of letting the referrer attach one.
     * Inbox tier only, exactly like the queue that surfaces the link.
     * <p>
     * Served as {@code Content-Disposition: attachment} with the sanitised
     * filename, the same convention as every candidate document; the
     * frontend's preview modal frames the bytes itself when its own
     * allowlist calls them a PDF. 404 when the referral does not exist or
     * carries no CV.
     */
    @GET
    @Path("/{uuid}/cv")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @RolesAllowed({"recruitment:read"})
    public Response downloadCv(@PathParam("uuid") UUID referralUuid) {
        enforceFlag();
        UUID actor = currentActor();
        requireInboxTier(actor);
        ReferralCvDownload download = referralService.readCv(referralUuid, actor);
        return Response.ok(download.bytes(), download.contentType())
                .header("Content-Disposition",
                        "attachment; filename=\"" + headerSafe(download.filename()) + "\"")
                .build();
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
        requireInboxTier(actor);
        ReferralTriageResponse result = referralService.triage(referralUuid, request, actor);
        if (result.candidateUuid() != null) {
            return Response.created(URI.create("/recruitment/candidates/" + result.candidateUuid()))
                    .entity(result)
                    .build();
        }
        return Response.ok(result).build();
    }

    // ---- Helpers --------------------------------------------------------------------

    /** Strip characters that could break out of the Content-Disposition header. */
    private static String headerSafe(String filename) {
        return filename.replaceAll("[\"\\r\\n\\\\]", "_");
    }

    /**
     * The intake queues are Inbox-tier surfaces: ADMIN, HR, RECRUITMENT or
     * TEAMLEAD. Decisions 12/13 (2026-08-23) opened the queues and their
     * actions to team leads — widen the queue rather than hide the tab.
     * The assistant stays out ({@code INBOX_TIER_ROLES}).
     */
    private void requireInboxTier(UUID actor) {
        if (!visibility.isInboxTier(actor.toString())) {
            throw new WebApplicationException(
                    "Referral triage is reserved for the recruitment team and team leads",
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
