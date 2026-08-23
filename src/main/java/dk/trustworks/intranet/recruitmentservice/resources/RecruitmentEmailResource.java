package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.ai.AiEmailDraftService;
import dk.trustworks.intranet.recruitmentservice.dto.ApproveEmailRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CopyOptionsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CopyRecipientResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DraftEmailRequest;
import dk.trustworks.intranet.recruitmentservice.dto.EmailSettingsRequest;
import dk.trustworks.intranet.recruitmentservice.dto.EmailSettingsResponse;
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
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyMode;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiVoiceCard;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailCopyResolver;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailHtmlSanitizer;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST entry point for candidate emails (ATS plan §P15): template
 * management, compose render/send, and the review-before-send queue.
 * Thin by convention: flag gate → actor resolution → tier/visibility
 * check → delegate to {@link RecruitmentEmailService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Every endpoint is recruiter-tier ({@code ADMIN}/{@code HR}/
 *       {@code RECRUITMENT} via {@link RecruitmentVisibility#isRecruiterTier}) —
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
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    AiEmailDraftService draftService;

    @Inject
    RecruitmentEmailCopyResolver copyResolver;

    @Inject
    RecruitmentAiVoiceCard voiceCard;

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
        // Absent active defaults to INACTIVE (F9): a template someone is
        // still writing must not fire at candidates — activation is an
        // explicit act, mirrored by the dialog's unticked default.
        RecruitmentEmailTemplate template = emailService.createTemplate(
                request.templateKey(), request.name(), request.subject(), request.body(),
                RecruitmentEmailBodyFormat.parse(request.bodyFormat()),
                Boolean.TRUE.equals(request.autoSend()),
                Boolean.TRUE.equals(request.active()),
                copyRolesOf(request.copyRoles()), copyModeOf(request.copyMode()));
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
        // Absent active keeps the stored state (F9): an update that does not
        // speak about activation must never flip an inactive template live.
        RecruitmentEmailTemplate template = emailService.updateTemplate(uuid.toString(),
                request.name(), request.subject(), request.body(),
                request.bodyFormat() == null || request.bodyFormat().isBlank()
                        ? null : RecruitmentEmailBodyFormat.parse(request.bodyFormat()),
                Boolean.TRUE.equals(request.autoSend()),
                request.active(),
                copyRolesOf(request.copyRoles()), copyModeOf(request.copyMode()));
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
        // The compose dialog composes in rich text whatever the template is,
        // so a legacy PLAIN template is up-converted here rather than forcing
        // the dialog to carry two editing modes for an identical result.
        RecruitmentEmailRenderer.Rendered rendered = emailService.renderAsHtml(template, candidate,
                positionForContext(candidate, request.applicationUuid(), actor));
        return new RenderedEmailResponse(rendered.subject(), rendered.body(),
                RecruitmentEmailBodyFormat.HTML.name(),
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
        RecruitmentEmailBodyFormat sendFormat =
                RecruitmentEmailBodyFormat.parse(request.bodyFormat());
        requireSubjectAndBody(request.subject(), request.body(), sendFormat);
        requireNoUnresolvedLinks(request.subject(), request.body(), sendFormat);
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, actor);
        String applicationUuid = visibleApplicationOrNull(candidate,
                request.applicationUuid(), actor);
        RecruitmentEmailService.RecruitmentPendingEmailResult result = emailService.sendManual(
                candidate.getUuid(), blankToNull(request.templateUuid()), applicationUuid,
                request.subject().trim(), request.body(), sendFormat, actor.toString(),
                request.copyUserUuids(), copyModeOrNull(request.copyMode()));
        return Response.status(Response.Status.CREATED)
                .entity(result)
                .build();
    }

    /**
     * Everyone this candidate's email may copy, with the picked template's
     * policy already applied — one call, so the dialog needs no client-side
     * authorization or policy logic. Read-only and side-effect free.
     */
    @GET
    @Path("/candidates/{uuid}/emails/copy-options")
    public CopyOptionsResponse copyOptions(@PathParam("uuid") UUID candidateUuid,
                                           @QueryParam("templateUuid") String templateUuid,
                                           @QueryParam("applicationUuid") String applicationUuid) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, actor);
        String ownApplication = visibleApplicationOrNull(candidate, applicationUuid, actor);
        RecruitmentEmailTemplate template = blankToNull(templateUuid) == null ? null
                : RecruitmentEmailTemplate.findById(templateUuid);

        List<RecruitmentEmailCopyResolver.CopyRecipient> pool =
                copyResolver.eligiblePool(candidate, ownApplication, actor.toString());
        Set<String> preselected = emailService
                .copiesFor(template, candidate, ownApplication, actor.toString())
                .recipients().stream()
                .map(RecruitmentEmailCopyResolver.CopyRecipient::userUuid)
                .collect(Collectors.toSet());

        return new CopyOptionsResponse(
                pool.stream()
                        .map(r -> CopyRecipientResponse.of(r, preselected.contains(r.userUuid())))
                        .toList(),
                (template == null ? RecruitmentEmailCopyMode.BCC : template.getCopyMode()).name(),
                emailService.replyToFor(actor.toString()));
    }

    // ---- Sender, reply and tone-of-voice settings ----------------------------------

    @GET
    @Path("/email-settings")
    public EmailSettingsResponse emailSettings() {
        enforceFlag();
        requireRecruiterTier(currentActor());
        return new EmailSettingsResponse(
                emailService.replyToFallback() == null ? "" : emailService.replyToFallback(),
                emailService.fromName(),
                emailService.fromAddress(),
                voiceCard.editableCard(),
                RecruitmentAiVoiceCard.defaultCard(),
                voiceCard.isDefault());
    }

    /**
     * Partial update: a {@code null} field is left alone, an empty field is
     * cleared. The page posts sender/replies and the tone-of-voice card from
     * two independent forms, so a whole-object PUT would let one form blank
     * the other's value.
     */
    @PUT
    @Path("/email-settings")
    @RolesAllowed({"recruitment:write"})
    public EmailSettingsResponse updateEmailSettings(EmailSettingsRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        Objects.requireNonNull(request, "request body must not be null");
        if (request.replyToFallback() != null) {
            emailService.updateReplyToFallback(request.replyToFallback(), actor.toString());
        }
        if (request.aiVoiceCard() != null) {
            if (request.aiVoiceCard().length() > RecruitmentAiVoiceCard.MAX_LENGTH) {
                throw badRequest("aiVoiceCard exceeds " + RecruitmentAiVoiceCard.MAX_LENGTH
                        + " characters");
            }
            voiceCard.update(request.aiVoiceCard(), actor.toString());
        }
        return emailSettings();
    }

    // ---- AI draft (P16 — returns a draft only, never sends) -----------------------

    /**
     * Personalise a template body for the candidate with the AI composer
     * (P16). <b>No send side effect</b> — the response is a draft the
     * recruiter reviews, edits and sends through {@link #send}; the
     * template's {@code auto_send} setting is irrelevant here by
     * construction (there is no code path from this endpoint to the mail
     * outbox). Gated on {@code recruitment.ai.email-composer.enabled}
     * with the P9 toggle convention (404-style + resource-level
     * {@code admin:*} bypass; the frontend hides the button on the
     * literal flag).
     * <p>
     * Deliberately NOT {@code @Transactional} (§P9 security review M1):
     * the OpenAI round-trip must not pin a pooled DB connection.
     */
    @POST
    @Path("/candidates/{uuid}/emails/draft")
    @RolesAllowed({"recruitment:write"})
    public RenderedEmailResponse draft(@PathParam("uuid") UUID candidateUuid,
                                       DraftEmailRequest request) {
        enforceFlag();
        enforceComposerToggle();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        Objects.requireNonNull(request, "request body must not be null");
        if (request.templateUuid() == null || request.templateUuid().isBlank()) {
            throw badRequest("templateUuid is required — the AI composer personalises an existing template");
        }
        String instruction = blankToNull(request.instruction());
        if (instruction != null && instruction.length() > AiEmailDraftService.INSTRUCTION_MAX_LENGTH) {
            throw badRequest("instruction exceeds " + AiEmailDraftService.INSTRUCTION_MAX_LENGTH
                    + " characters — keep it to a short sentence");
        }
        RecruitmentCandidate candidate = requireVisibleCandidate(candidateUuid, actor);
        RecruitmentEmailTemplate template = RecruitmentEmailTemplate.findById(request.templateUuid());
        if (template == null) {
            throw new NotFoundException("Resource not found");
        }
        String applicationUuid = visibleApplicationOrNull(candidate,
                request.applicationUuid(), actor);
        RecruitmentApplication application = applicationUuid == null ? null
                : RecruitmentApplication.findById(applicationUuid);
        try {
            AiEmailDraftService.Draft drafted = draftService.draft(candidate, template,
                    application, instruction, actor.toString());
            // Same contract as /render: the compose dialog edits in rich text
            // whatever the source template is, so a draft written against a
            // legacy PLAIN template is up-converted here. Without this its
            // paragraph breaks are plain "\n" dropped into a contentEditable,
            // where they collapse into one run-on block.
            String draftBody = drafted.bodyFormat().isHtml() ? drafted.body()
                    : RecruitmentEmailHtmlSanitizer.plainToHtml(drafted.body());
            return new RenderedEmailResponse(drafted.subject(), draftBody,
                    RecruitmentEmailBodyFormat.HTML.name(),
                    List.copyOf(drafted.unresolvedFields()));
        } catch (IllegalStateException e) {
            // OpenAI failure/refusal — a human-readable upstream error, no
            // candidate data in the message.
            throw new WebApplicationException(Response.status(Response.Status.BAD_GATEWAY)
                    .entity(Map.of("error", "AI_DRAFT_FAILED",
                            "message", "The AI draft could not be generated right now — try again, "
                                    + "or write the email yourself"))
                    .build());
        }
    }

    // ---- Review queue ------------------------------------------------------------

    @GET
    @Path("/emails/pending")
    public PendingEmailsResponse listPending() {
        enforceFlag();
        UUID actor = currentActor();
        requireRecruiterTier(actor);
        List<RecruitmentPendingEmail> pending = emailService.listPending();
        PendingVisibility pendingVisibility = pendingVisibility(pending, actor);
        List<PendingEmailResponse> rows = pending.stream()
                .map(row -> {
                    RecruitmentCandidate candidate = pendingVisibility.candidates()
                            .get(row.getCandidateUuid());
                    if (!pendingVisibility.canRead(row, candidate)) {
                        return null;
                    }
                    return PendingEmailResponse.of(row, candidate,
                            snapshotCopies(row, candidate));
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
        RecruitmentPendingEmail pending = requireVisiblePendingRow(pendingUuid, actor);
        String subject = request == null ? null : request.subject();
        String body = request == null ? null : request.body();
        if (subject != null && subject.trim().length() > RecruitmentEmailService.SUBJECT_MAX_LENGTH) {
            throw badRequest("subject exceeds " + RecruitmentEmailService.SUBJECT_MAX_LENGTH + " characters");
        }
        if (body != null && body.length() > RecruitmentEmailService.BODY_MAX_LENGTH) {
            throw badRequest("body exceeds " + RecruitmentEmailService.BODY_MAX_LENGTH + " characters");
        }
        // null keeps the snapshot's stored format — the distinction is the
        // approve() contract, so it is resolved once and passed on unchanged.
        RecruitmentEmailBodyFormat requestFormat =
                request == null || request.bodyFormat() == null || request.bodyFormat().isBlank()
                        ? null : RecruitmentEmailBodyFormat.parse(request.bodyFormat());
        // A null field means "approve the snapshot unedited", so the gate has
        // to judge what will actually be sent — not only what was retyped.
        requireNoUnresolvedLinks(subject == null ? pending.getSubject() : subject,
                body == null ? pending.getBody() : body,
                requestFormat == null ? pending.getBodyFormat() : requestFormat);
        RecruitmentPendingEmail approved = emailService.approve(pendingUuid.toString(),
                subject, body, requestFormat,
                actor.toString(),
                request == null ? null : request.copyUserUuids(),
                copyModeOrNull(request == null ? null : request.copyMode()));
        if (approved == null) {
            throw new NotFoundException("Resource not found");
        }
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(approved.getCandidateUuid());
        return PendingEmailResponse.of(approved, candidate, snapshotCopies(approved, candidate));
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
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(dismissed.getCandidateUuid());
        return PendingEmailResponse.of(dismissed, candidate, snapshotCopies(dismissed, candidate));
    }

    // ---- Helpers -----------------------------------------------------------------

    /**
     * The queue row's snapshotted copy list, re-resolved for display —
     * which also re-applies the read matrix, so a person who lost their
     * involvement while the row waited simply disappears from the list the
     * approver sees (and from the send).
     */
    private List<CopyRecipientResponse> snapshotCopies(RecruitmentPendingEmail pending,
                                                       RecruitmentCandidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        return copyResolver.resolveExplicit(candidate,
                        pending.getApplicationUuid(),
                        RecruitmentEmailCopyResolver.splitUserUuids(pending.getCopyUserUuids()))
                .stream()
                .map(recipient -> CopyRecipientResponse.of(recipient, true))
                .toList();
    }

    /** Parse the copy-role list from the wire; unknown tokens are rejected. */
    private Set<RecruitmentEmailCopyRole> copyRolesOf(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<RecruitmentEmailCopyRole> parsed = new LinkedHashSet<>();
        for (String role : roles) {
            parsed.add(RecruitmentEmailCopyRole.parse(role)
                    .orElseThrow(() -> badRequest("Unknown copy role '" + role
                            + "' — expected INTERVIEWERS, SENDER or HIRING_OWNER")));
        }
        return parsed;
    }

    /** Copy mode with the BCC default; unknown values are rejected. */
    private RecruitmentEmailCopyMode copyModeOf(String mode) {
        RecruitmentEmailCopyMode parsed = copyModeOrNull(mode);
        return parsed == null ? RecruitmentEmailCopyMode.BCC : parsed;
    }

    /** Copy mode override; null when absent, rejected when unrecognised. */
    private RecruitmentEmailCopyMode copyModeOrNull(String mode) {
        if (blankToNull(mode) == null) {
            return null;
        }
        try {
            return RecruitmentEmailCopyMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("copyMode must be BCC or CC");
        }
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

    /**
     * The P16 composer toggle ({@code recruitment.ai.email-composer.enabled})
     * — the P9 AI-toggle convention: 404-style feature-disabled error with
     * the resource-level {@code admin:*} bypass (the toggle itself has no
     * bypass; the frontend reads it literally and hides the button).
     */
    private void enforceComposerToggle() {
        if (aiFlags.isEmailComposerEnabled()) {
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

    /** Approve/dismiss re-check both candidate and application route. */
    private RecruitmentPendingEmail requireVisiblePendingRow(UUID pendingUuid, UUID actor) {
        RecruitmentPendingEmail pending = RecruitmentPendingEmail.findById(pendingUuid.toString());
        if (pending == null) {
            throw new NotFoundException("Resource not found");
        }
        PendingVisibility pendingVisibility = pendingVisibility(List.of(pending), actor);
        RecruitmentCandidate candidate = pendingVisibility.candidates()
                .get(pending.getCandidateUuid());
        if (!pendingVisibility.canRead(pending, candidate)) {
            throw new NotFoundException("Resource not found");
        }
        return pending;
    }

    /**
     * Application context is an object boundary of its own. A recruiter may
     * read a mixed candidate through an ordinary application while a PARTNER
     * application on the same candidate remains circle-confidential. Treat an
     * unknown, mismatched or unreadable application identically so the compose
     * routes cannot become a hidden-position oracle.
     */
    private String visibleApplicationOrNull(RecruitmentCandidate candidate,
                                            String applicationUuid,
                                            UUID actor) {
        String value = blankToNull(applicationUuid);
        if (value == null) {
            return null;
        }
        RecruitmentApplication application = RecruitmentApplication.findById(value);
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
        if (application == null
                || !application.getCandidateUuid().equals(candidate.getUuid())
                || position == null
                || !visibility.canReadPosition(actor.toString(), position)) {
            throw new NotFoundException("Resource not found");
        }
        return value;
    }

    private RecruitmentPosition positionForContext(RecruitmentCandidate candidate,
                                                   String applicationUuid,
                                                   UUID actor) {
        String value = visibleApplicationOrNull(candidate, applicationUuid, actor);
        if (value == null) {
            return null;
        }
        RecruitmentApplication application = RecruitmentApplication.findById(value);
        return application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());
    }

    /**
     * Batch the pending queue's soft references once. Application-less rows
     * remain candidate-scoped; application-bound rows require that exact
     * position to be readable and that the soft reference still belongs to
     * the row's candidate.
     */
    private PendingVisibility pendingVisibility(List<RecruitmentPendingEmail> rows, UUID actor) {
        List<String> candidateUuids = rows.stream()
                .map(RecruitmentPendingEmail::getCandidateUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, RecruitmentCandidate> candidates = candidateUuids.isEmpty() ? Map.of()
                : RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1", candidateUuids)
                        .stream().collect(Collectors.toMap(
                                RecruitmentCandidate::getUuid, candidate -> candidate));

        List<String> applicationUuids = rows.stream()
                .map(RecruitmentPendingEmail::getApplicationUuid)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        Map<String, RecruitmentApplication> applications = applicationUuids.isEmpty() ? Map.of()
                : RecruitmentApplication.<RecruitmentApplication>list(
                                "uuid in ?1", applicationUuids)
                        .stream().collect(Collectors.toMap(
                                RecruitmentApplication::getUuid, application -> application));

        List<String> positionUuids = applications.values().stream()
                .map(RecruitmentApplication::getPositionUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<RecruitmentPosition> positions = positionUuids.isEmpty() ? List.of()
                : RecruitmentPosition.list("uuid in ?1", positionUuids);
        Set<String> readablePositions = visibility.readablePositionUuids(
                actor.toString(), positions);
        Set<String> partnerOnly = new LinkedHashSet<>(
                visibility.partnerTrackOnlyCandidateUuids(actor.toString(), null));
        boolean admin = visibility.rolesOf(actor.toString()).contains("ADMIN");
        boolean mayReadHiredFiles = admin
                || visibility.canReadHiredCandidateFiles(actor.toString());
        Set<String> readableCandidates = candidates.values().stream()
                .filter(candidate -> admin
                        || (candidate.getStatus()
                                        != dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus.HIRED
                                || mayReadHiredFiles)
                        && !partnerOnly.contains(candidate.getUuid()))
                .map(RecruitmentCandidate::getUuid)
                .collect(Collectors.toSet());
        return new PendingVisibility(candidates, applications,
                readableCandidates, readablePositions);
    }

    private record PendingVisibility(
            Map<String, RecruitmentCandidate> candidates,
            Map<String, RecruitmentApplication> applications,
            Set<String> readableCandidates,
            Set<String> readablePositions) {

        private boolean canRead(RecruitmentPendingEmail pending,
                                RecruitmentCandidate candidate) {
            if (candidate == null || !readableCandidates.contains(candidate.getUuid())) {
                return false;
            }
            String applicationUuid = blankToNull(pending.getApplicationUuid());
            if (applicationUuid == null) {
                return true;
            }
            RecruitmentApplication application = applications.get(applicationUuid);
            return application != null
                    && pending.getCandidateUuid().equals(application.getCandidateUuid())
                    && readablePositions.contains(application.getPositionUuid());
        }
    }

    /** Explicit input caps — {@code @Valid} is inert in this repo (§P4.9). */
    private void requireTemplateFields(EmailTemplateRequest request) {
        if (request.name() == null || request.name().isBlank()
                || request.name().trim().length() > RecruitmentEmailService.NAME_MAX_LENGTH) {
            throw badRequest("name is required (max "
                    + RecruitmentEmailService.NAME_MAX_LENGTH + " characters)");
        }
        requireSubjectAndBody(request.subject(), request.body(),
                RecruitmentEmailBodyFormat.parse(request.bodyFormat()));
    }

    private void requireSubjectAndBody(String subject, String body,
                                       RecruitmentEmailBodyFormat bodyFormat) {
        if (subject == null || subject.isBlank()
                || subject.trim().length() > RecruitmentEmailService.SUBJECT_MAX_LENGTH) {
            throw badRequest("subject is required (max "
                    + RecruitmentEmailService.SUBJECT_MAX_LENGTH + " characters)");
        }
        // isBlank() is not enough on the HTML path: an empty rich editor
        // serialises to "<p><br></p>", which is blank to a reader and not to
        // String.isBlank().
        if (RecruitmentEmailService.isBlankBody(body, bodyFormat)
                || body.length() > RecruitmentEmailService.BODY_MAX_LENGTH) {
            throw badRequest("body is required (max "
                    + RecruitmentEmailService.BODY_MAX_LENGTH + " characters)");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Refuse a send that still carries an unfilled link placeholder.
     * <p>
     * The compose dialog warns about every unresolved merge field, but a
     * warning is advice and this is not advisable: {@code {{consent_link}}}
     * resolves only inside the GDPR sweep, so a hand-composed renewal mails
     * a dead GDPR consent link and the candidate is auto-deleted at the
     * deadline for not clicking it. Cosmetic tokens
     * ({@code {{position_title}}}) stay a warning — only {@code *_link} is
     * fatal, and only here, at the boundary where a human pressed Send.
     * Automatic sends never reach this method: the sweep and the candidate
     * mailer render their own bodies with every value in hand.
     */
    private void requireNoUnresolvedLinks(String subject, String body,
                                          RecruitmentEmailBodyFormat bodyFormat) {
        Set<String> broken = RecruitmentEmailRenderer.unresolvedLinkTokens(subject, body, bodyFormat);
        if (!broken.isEmpty()) {
            throw badRequest("The email still contains link placeholders that were not filled in: "
                    + broken.stream().map(token -> "{{" + token + "}}").collect(Collectors.joining(", "))
                    + ". They would reach the candidate as text, not a working link — remove them, "
                    + "or let the automatic job send this template.");
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
