package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.ApproveEmailRequest;
import dk.trustworks.intranet.recruitmentservice.dto.EmailTemplateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.EmailTemplateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.EmailTemplatesResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PendingEmailResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PendingEmailsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RenderEmailRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RenderedEmailResponse;
import dk.trustworks.intranet.recruitmentservice.dto.SendEmailRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for candidate emails (ATS plan §P15): template
 * management, compose render/send, and the review-before-send queue.
 * Thin by convention: flag gate → actor resolution → tier/visibility
 * check → delegate to {@link RecruitmentEmailService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Every endpoint is recruiter-tier ({@code ADMIN}/{@code HR}/
 *       {@code CXO} via {@link RecruitmentVisibility#isRecruiterTier}) —
 *       candidate communication is a recruiter surface (plan §P15;
 *       teamleads' review-first rejections land in this queue for a
 *       recruiter to approve).</li>
 *   <li>Per-candidate endpoints additionally funnel through
 *       {@code canReadCandidateProfile} — 404-not-403, so partner-track
 *       existence never leaks. The pending LIST filters rows the same
 *       way.</li>
 *   <li>Feature flag {@code recruitment.interviews.enabled} (core flag 2
 *       — spec §11 places candidate comms with it): off + non-admin
 *       caller → 404, the sibling-resource convention; admins bypass for
 *       dark testing.</li>
 *   <li>Input caps enforced explicitly here — {@code @Valid} is inert in
 *       this repo (§P4.9).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentEmailResource {

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
    RecruitmentEmailService emailService;

    // ---- Templates -------------------------------------------------------------

    @GET
    @Path("/email-templates")
    public EmailTemplatesResponse listTemplates() {
        enforceFlag();
        requireRecruiterTier(currentActor());
        List<EmailTemplateResponse> templates = emailService.listTemplates().stream()
                .map(EmailTemplateResponse::of)
                .toList();
        return new EmailTemplatesResponse(templates, templates.size());
    }

    @POST
    @Path("/email-templates")
    @RolesAllowed({"recruitment:write"})
    public Response createTemplate(EmailTemplateRequest request) {
        enforceFlag();
        requireRecruiterTier(currentActor());
        Objects.requireNonNull(request, "request body must not be null");
        if (request.templateKey() == null || request.templateKey().isBlank()) {
            throw badRequest("templateKey is required");
        }
        requireTemplateFields(request);
        RecruitmentEmailTemplate template = emailService.createTemplate(
                request.templateKey(), request.name(), request.subject(), request.body(),
                Boolean.TRUE.equals(request.autoSend()),
                request.active() == null || request.active());
        return Response.status(Response.Status.CREATED)
                .entity(EmailTemplateResponse.of(template))
                .build();
    }

    @PUT
    @Path("/email-templates/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public EmailTemplateResponse updateTemplate(@PathParam("uuid") UUID uuid,
                                                EmailTemplateRequest request) {
        enforceFlag();
        requireRecruiterTier(currentActor());
        Objects.requireNonNull(request, "request body must not be null");
        requireTemplateFields(request);
        RecruitmentEmailTemplate template = emailService.updateTemplate(uuid.toString(),
                request.name(), request.subject(), request.body(),
                Boolean.TRUE.equals(request.autoSend()),
                request.active() == null || request.active());
        if (template == null) {
            throw new NotFoundException("Resource not found");
        }
        return EmailTemplateResponse.of(template);
    }

    // ---- Compose: render + send --------------------------------------------------

    @POST
    @Path("/candidates/{uuid}/emails/render")
    public RenderedEmailResponse render(@PathParam("uuid") UUID candidateUuid,
                                        RenderEmailRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        Objects.requireNonNull(request, "request body must not be null");
        if (request.templateUuid() == null || request.templateUuid().isBlank()) {
            throw badRequest("templateUuid is required");
        }
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, actor);
        RecruitmentEmailTemplate template = RecruitmentEmailTemplate.findById(request.templateUuid());
        if (template == null) {
            throw new NotFoundException("Resource not found");
        }
        RecruitmentEmailRenderer.Rendered rendered = emailService.render(template, candidate,
                positionForContext(candidate, request.applicationUuid()));
        return new RenderedEmailResponse(rendered.subject(), rendered.body(),
                List.copyOf(rendered.unresolvedFields()));
    }

    @POST
    @Path("/candidates/{uuid}/emails/send")
    @RolesAllowed({"recruitment:write"})
    public Response send(@PathParam("uuid") UUID candidateUuid, SendEmailRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        Objects.requireNonNull(request, "request body must not be null");
        requireSubjectAndBody(request.subject(), request.body());
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, actor);
        String applicationUuid = ownApplicationOrNull(candidate, request.applicationUuid());
        RecruitmentEmailService.RecruitmentPendingEmailResult result = emailService.sendManual(
                candidate.getUuid(), blankToNull(request.templateUuid()), applicationUuid,
                request.subject().trim(), request.body(), actor.toString());
        return Response.status(Response.Status.CREATED)
                .entity(result)
                .build();
    }

    // ---- Review queue ------------------------------------------------------------

    @GET
    @Path("/emails/pending")
    public PendingEmailsResponse listPending() {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        List<PendingEmailResponse> rows = emailService.listPending().stream()
                .map(pending -> {
                    RecruitmentCandidate candidate =
                            RecruitmentCandidate.findById(pending.getCandidateUuid());
                    // Partner-circle hard filter (P8 read matrix): a queue
                    // row must never reveal a candidate the viewer cannot
                    // read.
                    if (candidate == null
                            || !visibility.canReadCandidateProfile(actor.toString(), candidate)) {
                        return null;
                    }
                    return PendingEmailResponse.of(pending, candidate);
                })
                .filter(Objects::nonNull)
                .toList();
        return new PendingEmailsResponse(rows, rows.size());
    }

    @POST
    @Path("/emails/pending/{uuid}/approve")
    @RolesAllowed({"recruitment:write"})
    public PendingEmailResponse approve(@PathParam("uuid") UUID pendingUuid,
                                        ApproveEmailRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        requireVisiblePendingRow(pendingUuid, actor);
        String subject = request == null ? null : request.subject();
        String body = request == null ? null : request.body();
        if (subject != null && subject.trim().length() > RecruitmentEmailService.SUBJECT_MAX_LENGTH) {
            throw badRequest("subject exceeds " + RecruitmentEmailService.SUBJECT_MAX_LENGTH + " characters");
        }
        if (body != null && body.length() > RecruitmentEmailService.BODY_MAX_LENGTH) {
            throw badRequest("body exceeds " + RecruitmentEmailService.BODY_MAX_LENGTH + " characters");
        }
        RecruitmentPendingEmail approved = emailService.approve(pendingUuid.toString(),
                subject, body, actor.toString());
        if (approved == null) {
            throw new NotFoundException("Resource not found");
        }
        return PendingEmailResponse.of(approved,
                RecruitmentCandidate.findById(approved.getCandidateUuid()));
    }

    @POST
    @Path("/emails/pending/{uuid}/dismiss")
    @RolesAllowed({"recruitment:write"})
    public PendingEmailResponse dismiss(@PathParam("uuid") UUID pendingUuid) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        requireVisiblePendingRow(pendingUuid, actor);
        RecruitmentPendingEmail dismissed = emailService.dismiss(pendingUuid.toString(),
                actor.toString());
        if (dismissed == null) {
            throw new NotFoundException("Resource not found");
        }
        return PendingEmailResponse.of(dismissed,
                RecruitmentCandidate.findById(dismissed.getCandidateUuid()));
    }

    // ---- Helpers -----------------------------------------------------------------

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

    /** Candidate comms are a recruiter surface — 404-not-403 keeps existence hidden. */
    private void requireRecruiterTier(UUID actor) {
        if (!visibility.isRecruiterTier(actor.toString())) {
            throw new NotFoundException("Resource not found");
        }
    }

    private RecruitmentCandidate requireVisibleCandidate(UUID candidateUuid, UUID actor) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || !visibility.canReadCandidateProfile(actor.toString(), candidate)) {
            throw new NotFoundException("Resource not found");
        }
        return candidate;
    }

    /** Approve/dismiss re-check the row's candidate through the same read matrix. */
    private void requireVisiblePendingRow(UUID pendingUuid, UUID actor) {
        RecruitmentPendingEmail pending = RecruitmentPendingEmail.findById(pendingUuid.toString());
        if (pending == null) {
            throw new NotFoundException("Resource not found");
        }
        requireVisibleCandidate(UUID.fromString(pending.getCandidateUuid()), actor);
    }

    /** The application context must belong to the addressed candidate. */
    private String ownApplicationOrNull(RecruitmentCandidate candidate, String applicationUuid) {
        String value = blankToNull(applicationUuid);
        if (value == null) {
            return null;
        }
        RecruitmentApplication application = RecruitmentApplication.findById(value);
        if (application == null || !application.getCandidateUuid().equals(candidate.getUuid())) {
            throw badRequest("applicationUuid does not belong to this candidate");
        }
        return value;
    }

    private RecruitmentPosition positionForContext(RecruitmentCandidate candidate,
                                                   String applicationUuid) {
        String value = ownApplicationOrNull(candidate, applicationUuid);
        if (value == null) {
            return null;
        }
        RecruitmentApplication application = RecruitmentApplication.findById(value);
        return application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
    }

    /** Explicit input caps — {@code @Valid} is inert in this repo (§P4.9). */
    private void requireTemplateFields(EmailTemplateRequest request) {
        if (request.name() == null || request.name().isBlank()
                || request.name().trim().length() > RecruitmentEmailService.NAME_MAX_LENGTH) {
            throw badRequest("name is required (max "
                    + RecruitmentEmailService.NAME_MAX_LENGTH + " characters)");
        }
        requireSubjectAndBody(request.subject(), request.body());
    }

    private void requireSubjectAndBody(String subject, String body) {
        if (subject == null || subject.isBlank()
                || subject.trim().length() > RecruitmentEmailService.SUBJECT_MAX_LENGTH) {
            throw badRequest("subject is required (max "
                    + RecruitmentEmailService.SUBJECT_MAX_LENGTH + " characters)");
        }
        if (body == null || body.isBlank() || body.length() > RecruitmentEmailService.BODY_MAX_LENGTH) {
            throw badRequest("body is required (max "
                    + RecruitmentEmailService.BODY_MAX_LENGTH + " characters)");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
