package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Candidate email commands (ATS plan §P15): template CRUD, merge-field
 * rendering, the manual send path, and the review-before-send queue.
 * <p>
 * Every send — automatic (reactor), manual (compose dialog) or approved
 * (review queue) — funnels through {@link #send}: one {@code mail} row
 * (status {@code READY}, picked up by the existing JBeret {@code mail-send}
 * outbox job) plus one {@code EMAIL_SENT} event, both in the caller's
 * transaction. Exactly-once by construction: the mail row and the event
 * commit or roll back together with whatever queued them.
 * <p>
 * P16 (AI email composer) sends its recruiter-reviewed drafts through the
 * manual path — {@link #sendManual} deliberately accepts an edited
 * subject/body rather than re-rendering the template.
 * <p>
 * All mutating methods load their aggregates inside the transaction
 * (the §P11 flush lesson — never mutate a detached entity).
 */
@JBossLog
@ApplicationScoped
public class RecruitmentEmailService {

    /** Reactor-trigger keys (plan §P15). Everything else is manual-send only. */
    public static final String KEY_ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT";
    public static final String KEY_REJECTION_SCREENING = "REJECTION_SCREENING";
    public static final String KEY_REJECTION_POST_INTERVIEW = "REJECTION_POST_INTERVIEW";
    public static final String STAGE_KEY_PREFIX = "STAGE_";

    public static final int SUBJECT_MAX_LENGTH = 300;
    public static final int BODY_MAX_LENGTH = 10_000;
    public static final int NAME_MAX_LENGTH = 120;

    private static final Pattern TEMPLATE_KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");

    @Inject
    EntityManager em;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    public List<RecruitmentEmailTemplate> listTemplates() {
        return RecruitmentEmailTemplate.list("ORDER BY templateKey");
    }

    @Transactional
    public RecruitmentEmailTemplate createTemplate(String templateKey, String name, String subject,
                                                   String body, boolean autoSend, boolean active) {
        String key = normalizeKey(templateKey);
        validateTemplateFields(name, subject, body);
        if (RecruitmentEmailTemplate.count("templateKey = ?1", key) > 0) {
            throw new BusinessRuleViolation("A template with key '" + key + "' already exists");
        }
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setTemplateKey(key);
        template.setName(name.trim());
        template.setSubject(subject.trim());
        template.setBody(body);
        template.setAutoSend(autoSend);
        template.setActive(active);
        template.persist();
        return template;
    }

    @Transactional
    public RecruitmentEmailTemplate updateTemplate(String uuid, String name, String subject,
                                                   String body, boolean autoSend, boolean active) {
        RecruitmentEmailTemplate template = RecruitmentEmailTemplate.findById(uuid);
        if (template == null) {
            return null;
        }
        validateTemplateFields(name, subject, body);
        template.setName(name.trim());
        template.setSubject(subject.trim());
        template.setBody(body);
        template.setAutoSend(autoSend);
        template.setActive(active);
        return template;
    }

    /** Active template for a reactor-trigger key; null = trigger silently off. */
    public RecruitmentEmailTemplate findActiveByKey(String templateKey) {
        return RecruitmentEmailTemplate
                .<RecruitmentEmailTemplate>find("templateKey = ?1 and active = true", templateKey)
                .firstResult();
    }

    /** Reserved reactor-trigger keys — the UI's trigger picker mirrors this set. */
    public static boolean isTriggerKey(String key) {
        return KEY_ACKNOWLEDGEMENT.equals(key)
                || KEY_REJECTION_SCREENING.equals(key)
                || KEY_REJECTION_POST_INTERVIEW.equals(key)
                || key != null && key.startsWith(STAGE_KEY_PREFIX);
    }

    private static void validateTemplateFields(String name, String subject, String body) {
        if (name == null || name.isBlank() || name.trim().length() > NAME_MAX_LENGTH) {
            throw new BusinessRuleViolation("name is required (max " + NAME_MAX_LENGTH + " characters)");
        }
        if (subject == null || subject.isBlank() || subject.trim().length() > SUBJECT_MAX_LENGTH) {
            throw new BusinessRuleViolation("subject is required (max " + SUBJECT_MAX_LENGTH + " characters)");
        }
        if (body == null || body.isBlank() || body.length() > BODY_MAX_LENGTH) {
            throw new BusinessRuleViolation("body is required (max " + BODY_MAX_LENGTH + " characters)");
        }
    }

    private static String normalizeKey(String templateKey) {
        String key = templateKey == null ? "" : templateKey.trim().toUpperCase();
        if (!TEMPLATE_KEY_PATTERN.matcher(key).matches()) {
            throw new BusinessRuleViolation(
                    "templateKey must be 2-60 characters of A-Z, 0-9 and underscore, starting with a letter");
        }
        return key;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    public RecruitmentEmailRenderer.Rendered render(RecruitmentEmailTemplate template,
                                                    RecruitmentCandidate candidate,
                                                    RecruitmentPosition position) {
        return RecruitmentEmailRenderer.render(template.getSubject(), template.getBody(),
                candidate, position);
    }

    // ------------------------------------------------------------------
    // Manual send (compose dialog; P16's AI drafts reuse this path)
    // ------------------------------------------------------------------

    /**
     * Send a recruiter-reviewed email to the candidate. Subject/body arrive
     * final (possibly edited from the rendered template); nothing is
     * re-rendered here. Appends {@code EMAIL_SENT} with the recruiter as
     * actor.
     *
     * @throws BusinessRuleViolation when the candidate has no email address
     */
    @Transactional
    public RecruitmentPendingEmailResult sendManual(String candidateUuid, String templateUuid,
                                                    String applicationUuid, String subject,
                                                    String body, String actorUserUuid) {
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        requireEmail(candidate);
        RecruitmentEmailTemplate template = templateUuid == null ? null
                : RecruitmentEmailTemplate.findById(templateUuid);
        String templateKey = template == null ? null : template.getTemplateKey();
        String mailUuid = send(candidate, applicationUuid, positionUuidOf(applicationUuid),
                templateKey, template == null ? null : template.getUuid(),
                subject, body, "MANUAL", null,
                RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                        .actorUser(actorUserUuid),
                visibilityFor(candidate.getUuid()));
        return new RecruitmentPendingEmailResult(mailUuid, templateKey);
    }

    public record RecruitmentPendingEmailResult(String mailUuid, String templateKey) {
    }

    // ------------------------------------------------------------------
    // Review-before-send queue
    // ------------------------------------------------------------------

    /** All PENDING rows, oldest first. Callers apply per-candidate visibility filtering. */
    public List<RecruitmentPendingEmail> listPending() {
        return RecruitmentPendingEmail.list("status = ?1 ORDER BY createdAt ASC",
                RecruitmentPendingEmailStatus.PENDING);
    }

    public long countPending() {
        return RecruitmentPendingEmail.count("status = ?1", RecruitmentPendingEmailStatus.PENDING);
    }

    /**
     * Queue a rendered email for recruiter review (reactor path). Runs in
     * the reactor's delivery transaction — exactly-once with the trigger
     * event's processing.
     */
    @Transactional
    public RecruitmentPendingEmail queueForReview(RecruitmentCandidate candidate,
                                                  String applicationUuid,
                                                  RecruitmentEmailTemplate template,
                                                  RecruitmentEmailRenderer.Rendered rendered,
                                                  RecruitmentPendingEmailReason reason,
                                                  String triggerEventUuid) {
        RecruitmentPendingEmail pending = new RecruitmentPendingEmail();
        pending.setCandidateUuid(candidate.getUuid());
        pending.setApplicationUuid(applicationUuid);
        pending.setTemplateUuid(template.getUuid());
        pending.setTemplateKey(template.getTemplateKey());
        pending.setReason(reason);
        pending.setToEmail(candidate.getEmail());
        pending.setSubject(rendered.subject());
        pending.setBody(rendered.body());
        pending.setTriggerEventUuid(triggerEventUuid);
        pending.persist();
        return pending;
    }

    /**
     * Approve one PENDING row: send (optionally with recruiter edits) and
     * mark APPROVED. One-shot under a pessimistic row lock — a concurrent
     * approve/dismiss loses with 409.
     *
     * @return the approved row, or null when the uuid does not exist
     */
    @Transactional
    public RecruitmentPendingEmail approve(String pendingUuid, String editedSubject,
                                           String editedBody, String actorUserUuid) {
        RecruitmentPendingEmail pending = em.find(RecruitmentPendingEmail.class, pendingUuid,
                LockModeType.PESSIMISTIC_WRITE);
        if (pending == null) {
            return null;
        }
        requirePending(pending);
        RecruitmentCandidate candidate = requireCandidate(pending.getCandidateUuid());
        requireEmail(candidate);
        String subject = editedSubject == null || editedSubject.isBlank()
                ? pending.getSubject() : editedSubject.trim();
        String body = editedBody == null || editedBody.isBlank()
                ? pending.getBody() : editedBody;
        send(candidate, pending.getApplicationUuid(), positionUuidOf(pending.getApplicationUuid()),
                pending.getTemplateKey(), pending.getTemplateUuid(),
                subject, body, "REVIEW_APPROVED", pending.getUuid(),
                RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                        .actorUser(actorUserUuid),
                visibilityFor(candidate.getUuid()));
        pending.setStatus(RecruitmentPendingEmailStatus.APPROVED);
        pending.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        pending.setResolvedBy(actorUserUuid);
        return pending;
    }

    /**
     * Dismiss one PENDING row without sending. One-shot, same locking as
     * {@link #approve}. Appends no event — nothing was communicated.
     *
     * @return the dismissed row, or null when the uuid does not exist
     */
    @Transactional
    public RecruitmentPendingEmail dismiss(String pendingUuid, String actorUserUuid) {
        RecruitmentPendingEmail pending = em.find(RecruitmentPendingEmail.class, pendingUuid,
                LockModeType.PESSIMISTIC_WRITE);
        if (pending == null) {
            return null;
        }
        requirePending(pending);
        pending.setStatus(RecruitmentPendingEmailStatus.DISMISSED);
        pending.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        pending.setResolvedBy(actorUserUuid);
        return pending;
    }

    private static void requirePending(RecruitmentPendingEmail pending) {
        if (pending.getStatus() != RecruitmentPendingEmailStatus.PENDING) {
            throw new BusinessRuleViolation(
                    "This email has already been handled (" + pending.getStatus() + ")");
        }
    }

    // ------------------------------------------------------------------
    // The single send funnel
    // ------------------------------------------------------------------

    /**
     * Persist the outgoing mail (status {@code READY} — the JBeret
     * {@code mail-send} outbox job delivers asynchronously with retry) and
     * append {@code EMAIL_SENT}, both in the caller's transaction.
     * Payload carries structural facts only; recipient, subject and body
     * are pii (anonymization contract).
     *
     * @return the mail row's uuid
     */
    public String send(RecruitmentCandidate candidate, String applicationUuid, String positionUuid,
                       String templateKey, String templateUuid, String subject, String body,
                       String trigger, String pendingUuid, RecruitmentEventBuilder eventBase,
                       RecruitmentEventVisibility visibility) {
        String mailUuid = UUID.randomUUID().toString();
        TrustworksMail mail = new TrustworksMail(mailUuid, candidate.getEmail(),
                subject, RecruitmentEmailRenderer.toHtml(body));
        mail.setStatus(MailStatus.READY);
        mail.persist();

        RecruitmentEventBuilder event = eventBase
                .candidate(candidate.getUuid())
                .application(applicationUuid)
                .position(positionUuid)
                .visibility(visibility)
                .payload("trigger", trigger)
                .payload("mail_uuid", mailUuid)
                .pii("to_email", candidate.getEmail())
                .pii("subject", subject)
                .pii("body", body);
        if (templateKey != null) {
            event.payload("template_key", templateKey);
        }
        if (templateUuid != null) {
            event.payload("template_uuid", templateUuid);
        }
        if (pendingUuid != null) {
            event.payload("pending_email_uuid", pendingUuid);
        }
        eventRecorder.record(event);
        log.infof("EMAIL_SENT queued mail=%s candidate=%s template=%s trigger=%s",
                mailUuid, candidate.getUuid(), templateKey, trigger);
        return mailUuid;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Fail-closed CIRCLE (the P10 MEDIUM-2 posture): any application the
     * candidate has ever held on a PARTNER-track position makes the email
     * event circle-scoped on the timeline.
     */
    public RecruitmentEventVisibility visibilityFor(String candidateUuid) {
        Long partnerApplications = em.createQuery(
                        "select count(a) from RecruitmentApplication a, RecruitmentPosition p "
                                + "where p.uuid = a.positionUuid "
                                + "  and a.candidateUuid = :candidate "
                                + "  and p.hiringTrack = :track", Long.class)
                .setParameter("candidate", candidateUuid)
                .setParameter("track", RecruitmentHiringTrack.PARTNER)
                .getSingleResult();
        return partnerApplications > 0
                ? RecruitmentEventVisibility.CIRCLE
                : RecruitmentEventVisibility.NORMAL;
    }

    private static String positionUuidOf(String applicationUuid) {
        if (applicationUuid == null) {
            return null;
        }
        RecruitmentApplication application = RecruitmentApplication.findById(applicationUuid);
        return application == null ? null : application.getPositionUuid();
    }

    private static RecruitmentCandidate requireCandidate(String candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            throw new BusinessRuleViolation("Candidate not found");
        }
        return candidate;
    }

    private static void requireEmail(RecruitmentCandidate candidate) {
        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            throw new BusinessRuleViolation(
                    "The candidate has no email address — add one on the profile first");
        }
    }
}
