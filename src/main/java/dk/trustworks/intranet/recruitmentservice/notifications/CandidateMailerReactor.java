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
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentConsentService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 *   <li><b>Rejection</b> — {@code APPLICATION_REJECTED} → the first active
 *       template in {@link RecruitmentEmailService#rejectionKeyChain}:
 *       {@code REJECTION_<reason>_<bucket>}, then
 *       {@code REJECTION_<reason>}, then the generic
 *       {@code REJECTION_SCREENING} (from SCREENING) /
 *       {@code REJECTION_POST_INTERVIEW} (any later stage). A recruiter who
 *       picked a specific letter in the reject dialog overrides the chain
 *       ({@code payload.email_template_key}); one who chose to send nothing
 *       suppresses it entirely ({@code payload.suppress_email}).</li>
 *   <li><b>Pooled</b> — {@code CANDIDATE_POOLED} → the first active template
 *       in {@link RecruitmentEmailService#pooledKeyChain}:
 *       {@code POOLED_<bucket>} then {@code POOLED}. Only on ENTERING the
 *       pool ({@code payload.entered_pool}) — re-bucketing a pooled
 *       candidate must not mail them again. This is the only candidate
 *       email an unsolicited applicant can receive after the receipt: their
 *       submission creates no application, so no rejection trigger can ever
 *       fire for them.</li>
 *   <li><b>Unsolicited receipt</b> — {@code UNSOLICITED_APPLICATION_RECEIVED}
 *       → template {@code UNSOLICITED_ACKNOWLEDGEMENT} (remediation F6: the
 *       unsolicited path creates no application, so the acknowledgement
 *       trigger never fired for it).</li>
 *   <li><b>Duplicate receipt</b> — {@code DUPLICATE_APPLICATION_RECEIVED}
 *       → template {@code DUPLICATE_APPLICATION_NOTICE} (remediation F7: a
 *       repeat submission onto an open application stores documents but
 *       creates nothing — the candidate still deserves a receipt).</li>
 * </ul>
 * Rules enforced here:
 * <ul>
 *   <li><b>Consent links are minted, not left dangling:</b> a letter whose
 *       text carries {@code {{consent_link}}} gets a real token minted for
 *       this candidate before it renders. Without that the talent-pool
 *       letter could only ask "may we keep your profile?" rhetorically —
 *       there was no way for the candidate to answer, and the answer is
 *       exactly what GDPR wants on the record.</li>
 *   <li><b>Flag:</b> {@code recruitment.interviews.enabled} (spec §11 puts
 *       candidate comms under core flag 2) checked per event — off ⇒
 *       silent PROCESSED advance, no backfill on later enable.</li>
 *   <li><b>Partner-referral guard:</b> rejection AND pooling emails for
 *       {@code source = PARTNER_REFERRAL} candidates NEVER auto-send —
 *       always queued for review, regardless of the template's
 *       {@code auto_send} value. Pooling joined the guard with the
 *       rejection-routing work: "not now, may we keep you on file" is the
 *       same soft no, and a partner's candidate hearing it unreviewed is
 *       the same accident.</li>
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

    /** The one merge field this reactor has to resolve itself. */
    private static final String CONSENT_LINK_TOKEN = "consent_link";

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    RecruitmentConsentService consentService;

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
            case APPLICATION_CREATED, APPLICATION_STAGE_CHANGED, APPLICATION_REJECTED,
                    UNSOLICITED_APPLICATION_RECEIVED, DUPLICATE_APPLICATION_RECEIVED,
                    CANDIDATE_POOLED -> {
            }
            default -> {
                return; // not ours — silent advance
            }
        }
        if (!featureFlag.isInterviewsEnabled()) {
            return; // side effects gated; offset advances, no backfill on later enable
        }
        Map<String, Object> payload = parse(event.getPayload());
        // A CHAIN, not a key: the first rung answered by an active template
        // wins, and the last rung is always what this reactor sent before the
        // chain existed — so a pipeline with no specific templates behaves
        // exactly as it did.
        List<String> templateKeys = switch (event.getEventType()) {
            case APPLICATION_CREATED -> singleKey(acknowledgementKey(payload));
            case APPLICATION_STAGE_CHANGED -> singleKey(stageKey(payload));
            case APPLICATION_REJECTED -> rejectionKeys(payload);
            case UNSOLICITED_APPLICATION_RECEIVED ->
                    List.of(RecruitmentEmailService.KEY_UNSOLICITED_ACKNOWLEDGEMENT);
            case DUPLICATE_APPLICATION_RECEIVED ->
                    List.of(RecruitmentEmailService.KEY_DUPLICATE_APPLICATION_NOTICE);
            case CANDIDATE_POOLED -> pooledKeys(payload);
            default -> List.of();
        };
        if (templateKeys.isEmpty()) {
            return;
        }
        // Resolved by TRIGGER, not by key: a letter TA has pointed at this
        // moment answers it whatever the letter's own key happens to be, and
        // a letter that has claimed no moment still answers its own key.
        RecruitmentEmailTemplate template = emailService.findFirstActiveByTrigger(templateKeys);
        if (template == null) {
            log.debugf("Candidate mailer: no active template in %s for event seq %d — skipping",
                    templateKeys, event.getSeq());
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
                    candidate.getUuid(), template.getTemplateKey(), event.getSeq());
            return;
        }
        RecruitmentPosition position = event.getPositionUuid() == null ? null
                : RecruitmentPosition.findById(event.getPositionUuid());
        // Minted only when the letter actually asks for one. Minting on every
        // pooling would stamp a token onto candidates whose letter never
        // offers a choice, which is a consent record of a question nobody was
        // asked. The mint commits with the mail row and the event, exactly as
        // it does in the GDPR sweep — a failed delivery mints nothing.
        Map<String, String> extras = consentExtras(template, candidate);
        // Through the SERVICE, not the renderer: the house merge values
        // (visiting address, company name, recruiter) are lookups the
        // renderer deliberately does not do, and an automatic send needs
        // them resolved — to empty, in the recruiter's case — exactly as a
        // manual one does. The consent link the mint just produced wins on
        // top.
        RecruitmentEmailRenderer.Rendered rendered =
                emailService.render(template, candidate, position, extras);

        // The template's copy policy applies to automatic sends too — an
        // auto-rejection BCCs the panel that met the candidate, which is
        // most of the point of copying at all. SENDER resolves to nobody
        // here: no human acted, so the Reply-To falls back to the
        // configured recruiting mailbox as well.
        RecruitmentEmailService.EmailCopies copies = emailService.copiesFor(
                template, candidate, event.getApplicationUuid(), null);

        boolean partnerReferralNo =
                (event.getEventType() == RecruitmentEventType.APPLICATION_REJECTED
                        || event.getEventType() == RecruitmentEventType.CANDIDATE_POOLED)
                        && candidate.getSource() == CandidateSource.PARTNER_REFERRAL;
        if (template.isAutoSend() && !partnerReferralNo) {
            emailService.send(candidate, event.getApplicationUuid(), event.getPositionUuid(),
                    template.getTemplateKey(), template.getUuid(),
                    rendered.subject(), rendered.body(), template.getBodyFormat(), "AUTO", null,
                    RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT).actorSystem(),
                    event.getVisibility(),
                    emailService.replyToFallback(), copies);
            return;
        }
        // Review-first: queue once per (trigger event, template) — the DB
        // unique key backs the chassis dedupe up.
        if (RecruitmentPendingEmail.count(
                "triggerEventUuid = ?1 and templateKey = ?2",
                event.getEventId(), template.getTemplateKey()) > 0) {
            return;
        }
        RecruitmentPendingEmailReason reason = partnerReferralNo
                ? RecruitmentPendingEmailReason.PARTNER_REFERRAL
                : RecruitmentPendingEmailReason.REVIEW_FIRST_TEMPLATE;
        RecruitmentPendingEmail pending = emailService.queueForReview(candidate,
                event.getApplicationUuid(), template, rendered, reason, event.getEventId(), copies);
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

    /**
     * A one-rung chain, or none at all when the trigger did not apply.
     * <p>
     * This mapping and the two below are package-private rather than
     * private so the DB-free tier can exercise them: the surrounding
     * {@code handle()} needs the whole chassis and a database, which is
     * exactly the reason the plan service grew a pure core too.
     */
    static List<String> singleKey(String key) {
        return key == null ? List.of() : List.of(key);
    }

    /**
     * The rejecter's explicit choice first, then the reason/stage chain.
     * <p>
     * Both overrides come from the reject dialog and are recorded on the
     * {@code APPLICATION_REJECTED} event, so the letter that went out is
     * reconstructable from the event stream alone — the same posture as the
     * reason code itself.
     */
    static List<String> rejectionKeys(Map<String, Object> payload) {
        if (Boolean.TRUE.equals(payload.get("suppress_email"))) {
            return List.of();
        }
        Object override = payload.get("email_template_key");
        if (override instanceof String key && !key.isBlank()) {
            // Deliberately NOT followed by the chain: a recruiter who picked
            // a letter and got an inactive one wants to hear nothing, not a
            // different letter they did not choose.
            return List.of(key.trim());
        }
        return RecruitmentEmailService.rejectionKeyChain(
                asString(payload.get("reason_code")), asString(payload.get("from_stage")));
    }

    /**
     * Entering the pool mails; re-bucketing a pooled candidate does not.
     * {@code pool()} accepts POOLED → POOLED for exactly that re-bucketing,
     * and a candidate moved PROSPECT → CONTACTED has not become newly
     * pooled — mailing them the "may we keep you on file" letter a second
     * time would read as a system fault.
     */
    static List<String> pooledKeys(Map<String, Object> payload) {
        if (Boolean.FALSE.equals(payload.get("entered_pool"))) {
            return List.of();
        }
        return RecruitmentEmailService.pooledKeyChain(asString(payload.get("pool_status")));
    }

    /**
     * {@code {{consent_link}}} for a letter that uses it, empty otherwise.
     * <p>
     * The token is minted at THIS point even on the review-first path, where
     * the letter then waits in the queue: {@code queueForReview} stores the
     * rendered text and {@code approve} sends it verbatim, so a link filled
     * any later would never reach the body. That is why the expiry is a
     * retention-length window rather than a short one — see
     * {@link RecruitmentConsentService#poolTokenExpiry}.
     */
    private Map<String, String> consentExtras(RecruitmentEmailTemplate template,
                                              RecruitmentCandidate candidate) {
        if (!RecruitmentEmailRenderer.usesToken(template.getSubject(), template.getBody(),
                template.getBodyFormat(), CONSENT_LINK_TOKEN)) {
            return Map.of();
        }
        RecruitmentConsentService.MintedToken minted = consentService.mintToken(
                candidate.getUuid(),
                RecruitmentConsentService.poolTokenExpiry(
                        candidate, LocalDateTime.now(ZoneOffset.UTC)));
        log.infof("Candidate mailer: minted a talent-pool consent link for candidate %s "
                + "(consent=%s, template=%s)", candidate.getUuid(), minted.consentUuid(),
                template.getTemplateKey());
        return Map.of(CONSENT_LINK_TOKEN, consentService.consentLinkFor(minted.token()));
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
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
