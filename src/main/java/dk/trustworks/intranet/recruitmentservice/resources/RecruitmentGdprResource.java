package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.GdprActionRequests.AnonymizeRequest;
import dk.trustworks.intranet.recruitmentservice.dto.GdprActionRequests.Art14SendRequest;
import dk.trustworks.intranet.recruitmentservice.dto.GdprActionRequests.DsarRecordRequest;
import dk.trustworks.intranet.recruitmentservice.dto.GdprCandidateStatusResponse;
import dk.trustworks.intranet.recruitmentservice.dto.GdprQueueResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAnonymizerService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentDsarExportService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentGdprQueueService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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

import java.util.UUID;

/**
 * The P19 DPO surface (spec §6.2 "GDPR", §7.2 "GDPR queue / anonymize /
 * DSAR — DPO only"): exception queue, per-candidate action state, the
 * Art. 14 notice send, DSAR recording + export, and on-request
 * anonymization.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:gdpr"})} — the
 *       dedicated DPO scope (spec §7.1), granted to no other tier; the
 *       BFF additionally gates its routes with
 *       {@code requireRoles(['DPO','ADMIN'])}.</li>
 *   <li>Feature flag {@code recruitment.gdpr.enabled}: off + non-admin
 *       caller → 404 (module convention). The DPO surface ships dark like
 *       every other phase.</li>
 *   <li>Anonymization demands typed confirmation server-side (the
 *       candidate's full name) — the UI dialog is convenience, not the
 *       guard. Irreversible; mode ON_REQUEST.</li>
 *   <li>The DSAR ZIP is streamed, never stored (see
 *       {@link RecruitmentDsarExportService}).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:gdpr"})
public class RecruitmentGdprResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    /** Template key of the Art. 14 notice email (V448 seed). */
    static final String KEY_ART14_NOTICE = "ART14_NOTICE";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentGdprQueueService queueService;

    @Inject
    RecruitmentAnonymizerService anonymizerService;

    @Inject
    RecruitmentDsarExportService dsarExportService;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    // ---- Queue (the page's single read) ---------------------------------------

    /** KPI header + the three exception queues + the anonymization log. */
    @GET
    @Path("/gdpr/queue")
    public GdprQueueResponse queue() {
        enforceFlag();
        return queueService.queue();
    }

    /** Per-candidate action state for the profile GDPR tab. */
    @GET
    @Path("/gdpr/candidates/{uuid}/status")
    public GdprCandidateStatusResponse candidateStatus(@PathParam("uuid") UUID candidateUuid) {
        enforceFlag();
        return queueService.candidateStatus(requireCandidate(candidateUuid));
    }

    // ---- Art. 14 notice ---------------------------------------------------------

    /**
     * Send the Art. 14 notice email (or record a manual notice). One per
     * candidate — a second call answers 409. Email mode appends BOTH the
     * {@code EMAIL_SENT} (send funnel) and {@code ART14_NOTICE_SENT}
     * (compliance fact) events; manual mode appends only the latter with
     * {@code channel=MANUAL}.
     */
    @POST
    @Path("/gdpr/candidates/{uuid}/art14-send")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public GdprCandidateStatusResponse sendArt14(@PathParam("uuid") UUID candidateUuid,
                                                 Art14SendRequest request) {
        enforceFlag();
        String actor = currentActor().toString();
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        requireActionable(candidate);
        if (!Boolean.TRUE.equals(candidate.getArt14Required())) {
            throw new BusinessRuleViolation(
                    "This candidate applied directly (Art. 13) — no Art. 14 notice is required");
        }
        GdprCandidateStatusResponse status = queueService.candidateStatus(candidate);
        if (status.art14NoticeSent()) {
            throw new BusinessRuleViolation("The Art. 14 notice was already sent for this candidate");
        }
        boolean manual = request != null && request.manual;
        String note = request == null ? null : request.note;
        RecruitmentEventBuilder noticeEvent = RecruitmentEventBuilder
                .event(RecruitmentEventType.ART14_NOTICE_SENT)
                .candidate(candidate.getUuid())
                .actorUser(actor)
                .visibility(emailService.visibilityFor(candidate.getUuid()))
                .payload("channel", manual ? "MANUAL" : "EMAIL");
        if (note != null && !note.isBlank()) {
            noticeEvent.pii("note", note.trim());
        }
        if (!manual) {
            if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
                throw new BusinessRuleViolation(
                        "The candidate has no email address — add one on the profile, or use "
                                + "\"mark as notified manually\" after notifying them another way");
            }
            RecruitmentEmailTemplate template = emailService.findActiveByKey(KEY_ART14_NOTICE);
            if (template == null) {
                throw new BusinessRuleViolation(
                        "No active ART14_NOTICE template — re-activate it on /recruitment/settings");
            }
            RecruitmentEmailRenderer.Rendered rendered =
                    emailService.render(template, candidate, null);
            emailService.send(candidate, null, null,
                    template.getTemplateKey(), template.getUuid(),
                    rendered.subject(), rendered.body(), "ART14_NOTICE", null,
                    RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                            .actorUser(actor),
                    emailService.visibilityFor(candidate.getUuid()));
            noticeEvent.payload("template_key", template.getTemplateKey());
        }
        eventRecorder.record(noticeEvent);
        log.infof("Art. 14 notice %s for candidate %s by %s",
                manual ? "recorded (manual)" : "sent", candidate.getUuid(), actor);
        return queueService.candidateStatus(candidate);
    }

    // ---- DSAR --------------------------------------------------------------------

    /**
     * Record a received data-subject access request — it enters the DPO
     * queue with the Art. 12(3) 30-day deadline. One open DSAR per
     * candidate; a second call answers 409 until an export closes it.
     */
    @POST
    @Path("/gdpr/candidates/{uuid}/dsar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public GdprCandidateStatusResponse recordDsar(@PathParam("uuid") UUID candidateUuid,
                                                  DsarRecordRequest request) {
        enforceFlag();
        String actor = currentActor().toString();
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        requireActionable(candidate);
        if (queueService.hasOpenDsar(candidate.getUuid())) {
            throw new BusinessRuleViolation(
                    "There is already an open access request for this candidate — "
                            + "export the data to close it first");
        }
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.DSAR_RECEIVED)
                .candidate(candidate.getUuid())
                .actorUser(actor)
                .visibility(emailService.visibilityFor(candidate.getUuid()))
                .payload("response_days", RecruitmentGdprQueueService.DSAR_RESPONSE_DAYS);
        if (request != null && request.note != null && !request.note.isBlank()) {
            event.pii("note", request.note.trim());
        }
        eventRecorder.record(event);
        log.infof("DSAR recorded for candidate %s by %s", candidate.getUuid(), actor);
        return queueService.candidateStatus(candidate);
    }

    /**
     * Build and stream the DSAR ZIP (JSON + PDF + README) and append
     * {@code DSAR_EXPORTED} — which also closes any open DSAR queue item.
     * Works without a recorded DSAR too (a proactive export is fine).
     */
    @POST
    @Path("/gdpr/candidates/{uuid}/dsar-export")
    @Produces("application/zip")
    @Transactional
    public Response dsarExport(@PathParam("uuid") UUID candidateUuid) {
        enforceFlag();
        String actor = currentActor().toString();
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        if (candidate.getStatus() == CandidateStatus.ANONYMIZED) {
            throw new BusinessRuleViolation(
                    "This candidate is anonymized — there is no personal data left to export");
        }
        RecruitmentDsarExportService.DsarExport export = dsarExportService.export(candidate);
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.DSAR_EXPORTED)
                .candidate(candidate.getUuid())
                .actorUser(actor)
                .visibility(emailService.visibilityFor(candidate.getUuid()))
                .payload("event_count", export.eventCount())
                .payload("document_count", export.documentCount()));
        log.infof("DSAR export for candidate %s by %s (%d events, %d documents)",
                candidate.getUuid(), actor, export.eventCount(), export.documentCount());
        return Response.ok(export.zipBytes())
                .header("Content-Disposition", "attachment; filename=\"" + export.filename() + "\"")
                .build();
    }

    // ---- On-request anonymization -------------------------------------------------

    /**
     * Irreversibly anonymize a candidate on their erasure request
     * (mode ON_REQUEST). Demands the candidate's full name as typed
     * confirmation — checked here, not just in the UI dialog.
     */
    @POST
    @Path("/gdpr/candidates/{uuid}/anonymize")
    @Consumes(MediaType.APPLICATION_JSON)
    public RecruitmentAnonymizerService.AnonymizationSummary anonymize(
            @PathParam("uuid") UUID candidateUuid, AnonymizeRequest request) {
        enforceFlag();
        String actor = currentActor().toString();
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        String expected = (nullSafe(candidate.getFirstName()) + " "
                + nullSafe(candidate.getLastName())).trim();
        String confirmed = request == null || request.confirmText == null
                ? "" : request.confirmText.trim();
        if (expected.isEmpty() || !expected.equals(confirmed)) {
            throw new WebApplicationException(Response.status(400)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity("{\"error\":\"CONFIRMATION_MISMATCH\"}")
                    .build());
        }
        RecruitmentAnonymizerService.AnonymizationSummary summary = anonymizerService.anonymize(
                candidate.getUuid(), RecruitmentAnonymizerService.Mode.ON_REQUEST, actor);
        log.infof("On-request anonymization of candidate %s by %s", candidateUuid, actor);
        return summary;
    }

    // ---- Shared guards -------------------------------------------------------------

    /** Flag off + non-admin caller → 404 (module convention, P2 idiom). */
    private void enforceFlag() {
        if (featureFlag.isGdprEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    private static RecruitmentCandidate requireCandidate(UUID candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found");
        }
        return candidate;
    }

    /** Art. 14 / DSAR recording make no sense on erased candidates. */
    private static void requireActionable(RecruitmentCandidate candidate) {
        if (candidate.getStatus() == CandidateStatus.ANONYMIZED) {
            throw new BusinessRuleViolation("This candidate is anonymized — no personal data remains");
        }
    }

    /** X-Requested-By is mandatory — every DPO action is per-user audited. */
    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By header is required", Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "X-Requested-By is not a valid UUID", Response.Status.BAD_REQUEST);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
