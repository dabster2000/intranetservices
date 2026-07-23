package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.Map;

/**
 * P15 candidate mailer (plan §P15): template-driven candidate emails on
 * pipeline events.
 * <ul>
 *   <li><b>Acknowledgement</b> — {@code APPLICATION_CREATED} with
 *       {@code payload.origin = "public_form"} (public submissions only;
 *       a recruiter attaching a candidate manually is not an application
 *       receipt) → template {@code ACKNOWLEDGEMENT}.</li>
 *   <li><b>Stage-triggered</b> — forward {@code APPLICATION_STAGE_CHANGED}
 *       → template {@code STAGE_<to>} when one exists and is active
 *       (none are seeded; TA creates them on /recruitment/settings).
 *       Back-moves never mail the candidate.</li>
 *   <li><b>Rejection</b> — {@code APPLICATION_REJECTED} → template
 *       {@code REJECTION_SCREENING} (from SCREENING) or
 *       {@code REJECTION_POST_INTERVIEW} (any later stage; defaults
 *       review-first per plan).</li>
 * </ul>
 * Rules enforced here:
 * <ul>
 *   <li><b>Flag:</b> {@code recruitment.interviews.enabled} (spec §11 puts
 *       candidate comms under core flag 2) checked per event — off ⇒
 *       silent PROCESSED advance, no backfill on later enable.</li>
 *   <li><b>Partner-referral guard:</b> rejection emails for
 *       {@code source = PARTNER_REFERRAL} candidates NEVER auto-send —
 *       always queued for review, regardless of the template's
 *       {@code auto_send} value.</li>
 *   <li><b>Exactly-once:</b> the mail row (async outbox) and the
 *       {@code EMAIL_SENT} event commit inside the delivery transaction —
 *       the chassis' durable dedupe makes catch-up replay produce no
 *       second send.</li>
 *   <li><b>No email address ⇒ visible skip</b> (INFO log), never a
 *       failure — interviews are schedulable without an email (§P11
 *       carry-over) and the pipeline must not block on comms.</li>
 * </ul>
 * Offset seeding to the stream head at deploy comes free from the P1
 * startup guard — no historical replay.
 */
@JBossLog
@ApplicationScoped
public class CandidateMailerReactor extends RecruitmentReactor {

    public static final String NAME = "candidate-mailer";

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentEmailService emailService;

    @Override
    public String name() {
        return NAME;
    }

    /**
     * One live try + two catch-up retries, then durable SKIPPED — the P12
     * posture: comms are best-effort and must never block the watermark.
     */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        switch (event.getEventType()) {
            case APPLICATION_CREATED, APPLICATION_STAGE_CHANGED, APPLICATION_REJECTED -> {
            }
            default -> {
                return; // not ours — silent advance
            }
        }
        if (!featureFlag.isInterviewsEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        Map<String, Object> payload = parse(event.getPayload());
        String templateKey = switch (event.getEventType()) {
            case APPLICATION_CREATED -> acknowledgementKey(payload);
            case APPLICATION_STAGE_CHANGED -> stageKey(payload);
            case APPLICATION_REJECTED -> rejectionKey(payload);
            default -> null;
        };
        if (templateKey == null) {
            return;
        }
        RecruitmentEmailTemplate template = emailService.findActiveByKey(templateKey);
        if (template == null) {
            log.debugf("Candidate mailer: no active template '%s' for event seq %d — skipping",
                    templateKey, event.getSeq());
            return;
        }
        RecruitmentCandidate candidate = event.getCandidateUuid() == null ? null
                : RecruitmentCandidate.findById(event.getCandidateUuid());
        if (candidate == null) {
            log.warnf("Candidate mailer: event seq %d without loadable candidate — skipping",
                    event.getSeq());
            return;
        }
        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            log.infof("Candidate mailer: candidate %s has no email address — skipping '%s' for seq %d",
                    candidate.getUuid(), templateKey, event.getSeq());
            return;
        }
        RecruitmentPosition position = event.getPositionUuid() == null ? null
                : RecruitmentPosition.findById(event.getPositionUuid());
        RecruitmentEmailRenderer.Rendered rendered =
                emailService.render(template, candidate, position);

        boolean partnerReferralRejection =
                event.getEventType() == RecruitmentEventType.APPLICATION_REJECTED
                        && candidate.getSource() == CandidateSource.PARTNER_REFERRAL;
        if (template.isAutoSend() && !partnerReferralRejection) {
            emailService.send(candidate, event.getApplicationUuid(), event.getPositionUuid(),
                    template.getTemplateKey(), template.getUuid(),
                    rendered.subject(), rendered.body(), "AUTO", null,
                    RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT).actorSystem(),
                    event.getVisibility());
            return;
        }
        // Review-first: queue once per (trigger event, template) — the DB
        // unique key backs the chassis dedupe up.
        if (RecruitmentPendingEmail.count(
                "triggerEventUuid = ?1 and templateKey = ?2",
                event.getEventId(), template.getTemplateKey()) > 0) {
            return;
        }
        RecruitmentPendingEmailReason reason = partnerReferralRejection
                ? RecruitmentPendingEmailReason.PARTNER_REFERRAL
                : RecruitmentPendingEmailReason.REVIEW_FIRST_TEMPLATE;
        RecruitmentPendingEmail pending = emailService.queueForReview(candidate,
                event.getApplicationUuid(), template, rendered, reason, event.getEventId());
        if (pending.getStatus() != RecruitmentPendingEmailStatus.PENDING) {
            throw new IllegalStateException("queued pending email must be PENDING");
        }
        log.infof("Candidate mailer: queued '%s' for review (candidate=%s, reason=%s, seq=%d)",
                template.getTemplateKey(), candidate.getUuid(), reason, event.getSeq());
    }

    // ------------------------------------------------------------------
    // Trigger → template-key mapping
    // ------------------------------------------------------------------

    /** Acknowledgement fires for public form submissions only. */
    private static String acknowledgementKey(Map<String, Object> payload) {
        return "public_form".equals(payload.get("origin"))
                ? RecruitmentEmailService.KEY_ACKNOWLEDGEMENT
                : null;
    }

    /** Forward stage entries only; back-moves never mail the candidate. */
    private static String stageKey(Map<String, Object> payload) {
        if ("BACK".equals(payload.get("direction"))) {
            return null;
        }
        Object to = payload.get("to");
        return to == null ? null : RecruitmentEmailService.STAGE_KEY_PREFIX + to;
    }

    private static String rejectionKey(Map<String, Object> payload) {
        Object fromStage = payload.get("from_stage");
        return RecruitmentStage.SCREENING.name().equals(fromStage)
                ? RecruitmentEmailService.KEY_REJECTION_SCREENING
                : RecruitmentEmailService.KEY_REJECTION_POST_INTERVIEW;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
