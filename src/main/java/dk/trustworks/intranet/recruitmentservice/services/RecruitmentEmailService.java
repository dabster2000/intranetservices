package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyMode;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
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
    /** Receipt for the unsolicited public form (remediation F6) — no application exists. */
    public static final String KEY_UNSOLICITED_ACKNOWLEDGEMENT = "UNSOLICITED_ACKNOWLEDGEMENT";
    /** Receipt when a repeat submission stored documents onto an open application (remediation F7). */
    public static final String KEY_DUPLICATE_APPLICATION_NOTICE = "DUPLICATE_APPLICATION_NOTICE";
    public static final String STAGE_KEY_PREFIX = "STAGE_";

    /**
     * Prefix of every rejection key. The two generic keys above are the
     * bottom rung of {@link #rejectionKeyChain}; above them sit the
     * reason-coded keys {@code REJECTION_<reason>} and
     * {@code REJECTION_<reason>_<bucket>}.
     */
    public static final String REJECTION_KEY_PREFIX = "REJECTION_";

    /**
     * The generic talent-pool letter ("not now — may we keep you on file"),
     * fired by {@code CANDIDATE_POOLED}. Above it sit the bucket-coded keys
     * {@code POOLED_<CandidatePoolStatus>} — a silver medalist who lost to
     * another hire deserves different words from a cold prospect.
     */
    public static final String KEY_POOLED = "POOLED";
    public static final String POOLED_KEY_PREFIX = "POOLED_";

    /** Stage bucket appended to a reason-coded rejection key. */
    private static final String BUCKET_SCREENING = "SCREENING";
    private static final String BUCKET_POST_INTERVIEW = "POST_INTERVIEW";

    /**
     * The candidate's own Outlook-invitation body, read by
     * {@code RecruitmentCalendarService.candidateTemplate()} — mirrored here
     * as a constant because that class looks the row up by this literal.
     */
    public static final String KEY_INTERVIEW_CANDIDATE_INVITATION = "INTERVIEW_CANDIDATE_INVITATION";

    public static final int SUBJECT_MAX_LENGTH = 300;

    /**
     * Cap on the stored body string, in characters. Raised from 10 000 with
     * rich text: markup costs roughly 10 % on prose and 2-3× on heavily
     * formatted text, and the pre-rich-text budget was the recruiter's writing
     * room, not the markup's. 16 000 is deliberately below 16 383 — the
     * worst-case 4-bytes-per-character bound of the {@code TEXT} column's
     * 65 535 bytes — so no input can overflow the column and no widening
     * migration is needed. For scale, the longest real template today is 1 010
     * characters.
     */
    public static final int BODY_MAX_LENGTH = 16_000;
    public static final int NAME_MAX_LENGTH = 120;

    private static final Pattern TEMPLATE_KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");

    /**
     * Reply-To used when no human sent the mail (the reactor's
     * acknowledgements and auto-rejections, the GDPR sweep's consent
     * renewals). Seeded to {@code hr@trustworks.dk} by V455, editable on
     * {@code /recruitment/settings}; blank means no Reply-To header at all
     * (the pre-V455 behaviour).
     */
    public static final String SETTING_REPLY_TO_FALLBACK = "recruitment.email.reply-to-fallback";
    private static final String SETTING_CATEGORY = "recruitment";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    @Inject
    EntityManager em;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    AppSettingService appSettingService;

    @Inject
    RecruitmentEmailCopyResolver copyResolver;

    /**
     * Display name in front of {@code quarkus.mailer.from}. The envelope
     * address is never overridden — SES verifies sender identities and a
     * per-recruiter From would risk a 554 — so warmth is bought with the
     * display name plus a real Reply-To instead.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.email.from-name",
            defaultValue = "Trustworks Rekruttering")
    String fromName;

    /** The SES-verified sender address; shown read-only on the settings page. */
    @ConfigProperty(name = "quarkus.mailer.from")
    String fromAddress;

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    public List<RecruitmentEmailTemplate> listTemplates() {
        return RecruitmentEmailTemplate.list("ORDER BY templateKey");
    }

    @Transactional
    public RecruitmentEmailTemplate createTemplate(String templateKey, String name, String subject,
                                                   String body, RecruitmentEmailBodyFormat bodyFormat,
                                                   boolean autoSend, boolean active,
                                                   Set<RecruitmentEmailCopyRole> copyRoles,
                                                   RecruitmentEmailCopyMode copyMode) {
        String key = normalizeKey(templateKey);
        RecruitmentEmailBodyFormat format = bodyFormat == null
                ? RecruitmentEmailBodyFormat.PLAIN : bodyFormat;
        String storedBody = sanitizeBodyForStorage(body, format);
        validateTemplateFields(name, subject, storedBody, format);
        if (RecruitmentEmailTemplate.count("templateKey = ?1", key) > 0) {
            throw new BusinessRuleViolation("A template with key '" + key + "' already exists");
        }
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setTemplateKey(key);
        template.setName(name.trim());
        template.setSubject(subject.trim());
        template.setBody(storedBody);
        template.setBodyFormat(format);
        template.setAutoSend(autoSend);
        template.setActive(active);
        template.setCopyRoles(RecruitmentEmailCopyRole.toCsv(copyRoles));
        template.setCopyMode(copyMode == null ? RecruitmentEmailCopyMode.BCC : copyMode);
        template.persist();
        return template;
    }

    @Transactional
    public RecruitmentEmailTemplate updateTemplate(String uuid, String name, String subject,
                                                   String body, RecruitmentEmailBodyFormat bodyFormat,
                                                   boolean autoSend, Boolean active,
                                                   Set<RecruitmentEmailCopyRole> copyRoles,
                                                   RecruitmentEmailCopyMode copyMode) {
        RecruitmentEmailTemplate template = RecruitmentEmailTemplate.findById(uuid);
        if (template == null) {
            return null;
        }
        // Absent format keeps whatever the row already had, so a caller that
        // predates rich text cannot silently reinterpret a stored body.
        RecruitmentEmailBodyFormat format = bodyFormat == null
                ? template.getBodyFormat() : bodyFormat;
        String storedBody = sanitizeBodyForStorage(body, format);
        validateTemplateFields(name, subject, storedBody, format);
        template.setName(name.trim());
        template.setSubject(subject.trim());
        template.setBody(storedBody);
        template.setBodyFormat(format);
        template.setAutoSend(autoSend);
        // Absent keeps the stored state — an omitted flag must never flip a
        // deliberately-inactive template live (the F9 posture: activation is
        // always an explicit act).
        template.setActive(active == null ? template.isActive() : active);
        template.setCopyRoles(RecruitmentEmailCopyRole.toCsv(copyRoles));
        template.setCopyMode(copyMode == null ? RecruitmentEmailCopyMode.BCC : copyMode);
        return template;
    }

    /**
     * Reduce an inbound body to what we are willing to store. The client is
     * never trusted: the frontend sanitizes so the author sees what will be
     * kept, this runs so that is actually true, and the send path sanitizes
     * once more because a body can be stored by one release and sent by the
     * next.
     */
    public static String sanitizeBodyForStorage(String body, RecruitmentEmailBodyFormat format) {
        if (format == null || !format.isHtml()) {
            return body;
        }
        return RecruitmentEmailHtmlSanitizer.clean(body);
    }

    /**
     * Sanitize, then re-check that anything readable survived. The required-body
     * checks upstream run on the raw string, and the two do not agree on
     * invisible format characters: a body of {@code <p>\uFEFF</p>} has
     * non-blank text before cleaning and none after, which would otherwise send
     * an empty email to a candidate — and through the review queue, flip a
     * one-shot row to APPROVED on the way.
     */
    private static String sanitizeSendableBody(String body, RecruitmentEmailBodyFormat format) {
        String cleaned = sanitizeBodyForStorage(body, format);
        if (isBlankBody(cleaned, format)) {
            throw new BusinessRuleViolation(
                    "The message is empty once formatting is removed — write something to send");
        }
        return cleaned;
    }

    /**
     * True when a body carries no readable text. {@code "<p><br></p>"} is what
     * an empty rich editor serialises to and {@code isBlank()} calls that
     * non-empty, so every required-body check goes through here.
     */
    public static boolean isBlankBody(String body, RecruitmentEmailBodyFormat format) {
        if (format != null && format.isHtml()) {
            return RecruitmentEmailHtmlSanitizer.isBlankHtml(body);
        }
        return body == null || body.isBlank();
    }

    /** Active template for a reactor-trigger key; null = trigger silently off. */
    public RecruitmentEmailTemplate findActiveByKey(String templateKey) {
        return RecruitmentEmailTemplate
                .<RecruitmentEmailTemplate>find("templateKey = ?1 and active = true", templateKey)
                .firstResult();
    }

    /**
     * The first template in {@code keys} that exists and is active — the
     * fall-through the candidate mailer resolves a trigger with. Order is
     * most-specific-first; an empty list (or no active row anywhere in it)
     * answers null, which the mailer reads as "send nothing", exactly as a
     * single missing template always has.
     */
    public RecruitmentEmailTemplate findFirstActiveByKey(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            RecruitmentEmailTemplate template = findActiveByKey(key);
            if (template != null) {
                return template;
            }
        }
        return null;
    }

    /**
     * Rejection template keys to try, most specific first (2026-09-02).
     * <p>
     * The reason code has been mandatory on every rejection since P4 and
     * rides in the {@code APPLICATION_REJECTED} payload beside the stage —
     * it was simply never read when choosing the letter, so one generic
     * letter had to cover "too junior", "wanted too much" and "we hired
     * someone else" alike. The chain lets TA answer any of those
     * specifically without a deploy, and falls back to the generic letter
     * (the pre-2026-09-02 behaviour, byte for byte) whenever no specific
     * one is configured:
     * <ol>
     *   <li>{@code REJECTION_<reason>_<bucket>} — reason and stage together</li>
     *   <li>{@code REJECTION_<reason>} — reason alone</li>
     *   <li>{@code REJECTION_SCREENING} / {@code REJECTION_POST_INTERVIEW}</li>
     * </ol>
     * The bucket is the same SCREENING-vs-later split the generic keys have
     * always used, so "too junior at screening" and "too junior after two
     * interviews" can differ without inventing a second stage vocabulary.
     *
     * @param reasonCode the persisted {@link RecruitmentRejectionReason}
     *                   name; null/blank yields the generic rung alone
     * @param fromStage  the {@link RecruitmentStage} name the rejection came
     *                   from; anything but SCREENING counts as post-interview
     */
    public static List<String> rejectionKeyChain(String reasonCode, String fromStage) {
        boolean screening = RecruitmentStage.SCREENING.name().equals(fromStage);
        String generic = screening ? KEY_REJECTION_SCREENING : KEY_REJECTION_POST_INTERVIEW;
        String reason = reasonCode == null ? "" : reasonCode.trim();
        if (reason.isEmpty()) {
            return List.of(generic);
        }
        String bucket = screening ? BUCKET_SCREENING : BUCKET_POST_INTERVIEW;
        // A LinkedHashSet, not a List: should a reason code ever be named
        // SCREENING, rung 2 would collide with the generic rung and the
        // mailer would look the same row up twice.
        return List.copyOf(new LinkedHashSet<>(List.of(
                REJECTION_KEY_PREFIX + reason + "_" + bucket,
                REJECTION_KEY_PREFIX + reason,
                generic)));
    }

    /**
     * Talent-pool template keys to try, most specific first: the bucket-coded
     * {@code POOLED_<status>} then the generic {@code POOLED}.
     * <p>
     * This is the trigger unsolicited candidates never had. An unsolicited
     * submission creates no application ({@code PublicApplyService}), so
     * {@code APPLICATION_REJECTED} can never fire for one and there was no
     * path in the product that told them anything after the receipt.
     * Pooling is the verb they do have.
     */
    public static List<String> pooledKeyChain(String poolStatus) {
        String status = poolStatus == null ? "" : poolStatus.trim();
        if (status.isEmpty()) {
            return List.of(KEY_POOLED);
        }
        return List.copyOf(new LinkedHashSet<>(List.of(
                POOLED_KEY_PREFIX + status, KEY_POOLED)));
    }

    /** Reserved reactor-trigger keys — the UI's trigger picker mirrors this set. */
    public static boolean isTriggerKey(String key) {
        if (key == null) {
            return false;
        }
        return KEY_ACKNOWLEDGEMENT.equals(key)
                || KEY_UNSOLICITED_ACKNOWLEDGEMENT.equals(key)
                || KEY_DUPLICATE_APPLICATION_NOTICE.equals(key)
                || key.startsWith(STAGE_KEY_PREFIX)
                || isRejectionTriggerKey(key)
                || isPooledTriggerKey(key);
    }

    /**
     * Derived from the enums rather than a prefix test on purpose: a
     * {@code REJECTION_} key nothing can ever produce is a manual-send
     * template, and saying otherwise would put a "sent automatically"
     * explainer on a row that never sends.
     */
    private static boolean isRejectionTriggerKey(String key) {
        if (KEY_REJECTION_SCREENING.equals(key) || KEY_REJECTION_POST_INTERVIEW.equals(key)) {
            return true;
        }
        if (!key.startsWith(REJECTION_KEY_PREFIX)) {
            return false;
        }
        for (RecruitmentRejectionReason reason : RecruitmentRejectionReason.values()) {
            String base = REJECTION_KEY_PREFIX + reason.name();
            if (key.equals(base)
                    || key.equals(base + "_" + BUCKET_SCREENING)
                    || key.equals(base + "_" + BUCKET_POST_INTERVIEW)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPooledTriggerKey(String key) {
        if (KEY_POOLED.equals(key)) {
            return true;
        }
        if (!key.startsWith(POOLED_KEY_PREFIX)) {
            return false;
        }
        for (CandidatePoolStatus status : CandidatePoolStatus.values()) {
            if (key.equals(POOLED_KEY_PREFIX + status.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keys a scheduled job owns end to end, which a recruiter must therefore
     * never compose by hand. Distinct from {@link #isTriggerKey}: a trigger
     * key is one the reactor ALSO fires, and composing it manually is a
     * legitimate "send this one again" — whereas a system key's body only
     * renders correctly inside its job.
     * <p>
     * {@code CONSENT_RENEWAL} is the case: its {@code {{consent_link}}}
     * resolves solely in {@link RecruitmentGdprService}'s sweep, which mints
     * the token that makes the link real. Composed by hand it mails a dead
     * placeholder. The compose picker hides these; the send gate
     * ({@link RecruitmentEmailRenderer#unresolvedLinkTokens}) is what
     * actually enforces it.
     * <p>
     * {@code OPTION_INVITATION} (Method B's booking link exists only inside
     * the advance sweep) and {@code INTERVIEW_CANDIDATE_INVITATION} (an
     * Outlook event body, not an email at all — its interview extras resolve
     * only in the calendar service) are the same shape and were added in the
     * candidate-email remediation (F10, 2026-08-22): a recruiter hand-sending
     * either could only produce a broken message.
     */
    public static boolean isSystemKey(String key) {
        return RecruitmentGdprService.KEY_CONSENT_RENEWAL.equals(key)
                || RecruitmentSchedulingCandidateService.TEMPLATE_KEY_OPTION_INVITATION.equals(key)
                || KEY_INTERVIEW_CANDIDATE_INVITATION.equals(key);
    }

    private static void validateTemplateFields(String name, String subject, String body,
                                               RecruitmentEmailBodyFormat format) {
        if (name == null || name.isBlank() || name.trim().length() > NAME_MAX_LENGTH) {
            throw new BusinessRuleViolation("name is required (max " + NAME_MAX_LENGTH + " characters)");
        }
        if (subject == null || subject.isBlank() || subject.trim().length() > SUBJECT_MAX_LENGTH) {
            throw new BusinessRuleViolation("subject is required (max " + SUBJECT_MAX_LENGTH + " characters)");
        }
        if (isBlankBody(body, format) || body.length() > BODY_MAX_LENGTH) {
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
                candidate, position, java.util.Map.of(), template.getBodyFormat());
    }

    /**
     * Render a template for a surface that composes in rich text — the compose
     * dialog and the review queue. A legacy {@code PLAIN} template is
     * up-converted to the equivalent HTML so those surfaces have exactly one
     * editing mode; the candidate sees the same email either way, because the
     * up-conversion is the same escape-and-linebreak step the send path has
     * always applied to plain bodies.
     */
    public RecruitmentEmailRenderer.Rendered renderAsHtml(RecruitmentEmailTemplate template,
                                                          RecruitmentCandidate candidate,
                                                          RecruitmentPosition position) {
        if (template.getBodyFormat() != null && template.getBodyFormat().isHtml()) {
            return render(template, candidate, position);
        }
        RecruitmentEmailRenderer.Rendered plain = render(template, candidate, position);
        return new RecruitmentEmailRenderer.Rendered(plain.subject(),
                RecruitmentEmailHtmlSanitizer.plainToHtml(plain.body()),
                plain.unresolvedFields());
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
                                                    String body, RecruitmentEmailBodyFormat bodyFormat,
                                                    String actorUserUuid,
                                                    List<String> copyUserUuids,
                                                    RecruitmentEmailCopyMode copyMode) {
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        requireEmail(candidate);
        RecruitmentEmailTemplate template = templateUuid == null ? null
                : RecruitmentEmailTemplate.findById(templateUuid);
        String templateKey = template == null ? null : template.getTemplateKey();
        // The recruiter's own text — the one body on this path that was never
        // sanitized on write, because there is no write. Clean it here.
        RecruitmentEmailBodyFormat format = bodyFormat == null
                ? RecruitmentEmailBodyFormat.PLAIN : bodyFormat;
        String cleanBody = sanitizeSendableBody(body, format);
        // An explicit list (even an empty one) is the recruiter's decision
        // and wins; a null list means "whatever the template asks for".
        EmailCopies copies = copyUserUuids == null
                ? copiesFor(template, candidate, applicationUuid, actorUserUuid)
                : copiesOf(candidate, applicationUuid, copyUserUuids,
                        copyMode != null ? copyMode
                                : template != null ? template.getCopyMode()
                                        : RecruitmentEmailCopyMode.BCC);
        String mailUuid = send(candidate, applicationUuid, positionUuidOf(applicationUuid),
                templateKey, template == null ? null : template.getUuid(),
                subject, cleanBody, format, "MANUAL", null,
                RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                        .actorUser(actorUserUuid),
                visibilityFor(candidate.getUuid()),
                replyToFor(actorUserUuid), copies);
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
                                                  String triggerEventUuid,
                                                  EmailCopies copies) {
        EmailCopies snapshot = copies == null ? EmailCopies.none() : copies;
        RecruitmentPendingEmail pending = new RecruitmentPendingEmail();
        pending.setCandidateUuid(candidate.getUuid());
        pending.setApplicationUuid(applicationUuid);
        pending.setTemplateUuid(template.getUuid());
        pending.setTemplateKey(template.getTemplateKey());
        pending.setReason(reason);
        pending.setToEmail(candidate.getEmail());
        pending.setSubject(rendered.subject());
        pending.setBody(rendered.body());
        pending.setBodyFormat(template.getBodyFormat() == null
                ? RecruitmentEmailBodyFormat.PLAIN : template.getBodyFormat());
        pending.setTriggerEventUuid(triggerEventUuid);
        // Snapshot the resolved copy list next to the rendered text
        // (§P15 deviation 5): approving sends what the recruiter reviewed,
        // even if the template's policy changed in the meantime.
        pending.setCopyUserUuids(RecruitmentEmailCopyResolver.userUuidsOf(snapshot.recipients()));
        pending.setCopyMode(snapshot.mode());
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
                                           String editedBody,
                                           RecruitmentEmailBodyFormat editedBodyFormat,
                                           String actorUserUuid,
                                           List<String> copyUserUuids,
                                           RecruitmentEmailCopyMode copyMode) {
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
        // An edited body arrives in the format the approver's editor used —
        // which may be HTML even when the queued snapshot was plain, because
        // the review dialog composes in rich text regardless. The snapshot's
        // own format is only right when there was no edit.
        boolean edited = !isBlankBody(editedBody,
                editedBodyFormat == null ? pending.getBodyFormat() : editedBodyFormat);
        RecruitmentEmailBodyFormat format = !edited ? pending.getBodyFormat()
                : editedBodyFormat == null ? pending.getBodyFormat() : editedBodyFormat;
        String body = edited
                ? sanitizeSendableBody(editedBody, format)
                : pending.getBody();
        // Same rule as the manual path: an explicit list is the approver's
        // decision; null falls back to the snapshot taken at queue time.
        // Either way the people are re-authorized now, not at queue time —
        // involvement can have changed while the row waited.
        List<String> effectiveUuids = copyUserUuids != null ? copyUserUuids
                : RecruitmentEmailCopyResolver.splitUserUuids(pending.getCopyUserUuids());
        EmailCopies copies = copiesOf(candidate, pending.getApplicationUuid(), effectiveUuids,
                copyMode != null ? copyMode : pending.getCopyMode());
        send(candidate, pending.getApplicationUuid(), positionUuidOf(pending.getApplicationUuid()),
                pending.getTemplateKey(), pending.getTemplateUuid(),
                subject, body, format, "REVIEW_APPROVED", pending.getUuid(),
                RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                        .actorUser(actorUserUuid),
                visibilityFor(candidate.getUuid()),
                replyToFor(actorUserUuid), copies);
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
     * The internal copies applied to one send: the mode and the already
     * resolved, already visibility-filtered recipients. {@link #none()} is
     * the "candidate only" case, which is what every send did before V455.
     */
    public record EmailCopies(RecruitmentEmailCopyMode mode,
                              List<RecruitmentEmailCopyResolver.CopyRecipient> recipients) {

        public EmailCopies {
            mode = mode == null ? RecruitmentEmailCopyMode.BCC : mode;
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }

        public static EmailCopies none() {
            return new EmailCopies(RecruitmentEmailCopyMode.BCC, List.of());
        }

        public boolean isEmpty() {
            return recipients.isEmpty();
        }
    }

    /**
     * Persist the outgoing mail (status {@code READY} — the JBeret
     * {@code mail-send} outbox job delivers asynchronously with retry) and
     * append {@code EMAIL_SENT}, both in the caller's transaction.
     * Payload carries structural facts only; recipient, subject, body,
     * Reply-To and the copy addresses are pii (anonymization contract —
     * the copied people's UUIDs stay in payload so P20 can count comms
     * reach after anonymization).
     *
     * @param replyTo where a reply goes; null/blank omits the header
     * @param copies  internal copies, already resolved and authorized
     * @return the mail row's uuid
     */
    public String send(RecruitmentCandidate candidate, String applicationUuid, String positionUuid,
                       String templateKey, String templateUuid, String subject, String body,
                       RecruitmentEmailBodyFormat bodyFormat,
                       String trigger, String pendingUuid, RecruitmentEventBuilder eventBase,
                       RecruitmentEventVisibility visibility,
                       String replyTo, EmailCopies copies) {
        EmailCopies effectiveCopies = copies == null ? EmailCopies.none() : copies;
        RecruitmentEmailBodyFormat format = bodyFormat == null
                ? RecruitmentEmailBodyFormat.PLAIN : bodyFormat;
        String mailUuid = UUID.randomUUID().toString();
        TrustworksMail mail = new TrustworksMail(mailUuid, candidate.getEmail(),
                subject, RecruitmentEmailRenderer.toHtml(body, format));
        mail.setStatus(MailStatus.READY);
        mail.setFromName(fromName);
        if (replyTo != null && !replyTo.isBlank()) {
            mail.setReplyTo(replyTo.trim());
        }
        String copyAddresses = RecruitmentEmailCopyResolver.addressesOf(effectiveCopies.recipients());
        if (copyAddresses != null) {
            if (effectiveCopies.mode() == RecruitmentEmailCopyMode.CC) {
                mail.setCc(copyAddresses);
            } else {
                mail.setBcc(copyAddresses);
            }
        }
        mail.persist();

        RecruitmentEventBuilder event = eventBase
                .candidate(candidate.getUuid())
                .application(applicationUuid)
                .position(positionUuid)
                .visibility(visibility)
                .payload("trigger", trigger)
                .payload("mail_uuid", mailUuid)
                // Structural, so the timeline and the DSAR export know whether
                // the archived body is text or markup without re-sniffing it.
                .payload("body_format", format.name())
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
        if (replyTo != null && !replyTo.isBlank()) {
            event.pii("reply_to", replyTo.trim());
        }
        if (!effectiveCopies.isEmpty()) {
            // Structural in payload (survives anonymization — P20 counts
            // reach from it), addresses in pii.
            event.payload("copy_mode", effectiveCopies.mode().name())
                    .payload("copy_count", effectiveCopies.recipients().size())
                    .payload("copy_user_uuids", effectiveCopies.recipients().stream()
                            .map(RecruitmentEmailCopyResolver.CopyRecipient::userUuid).toList())
                    .pii(effectiveCopies.mode() == RecruitmentEmailCopyMode.CC
                            ? "cc_emails" : "bcc_emails", copyAddresses);
        }
        eventRecorder.record(event);
        log.infof("EMAIL_SENT queued mail=%s candidate=%s template=%s trigger=%s copies=%d/%s",
                mailUuid, candidate.getUuid(), templateKey, trigger,
                effectiveCopies.recipients().size(), effectiveCopies.mode());
        return mailUuid;
    }

    // ------------------------------------------------------------------
    // Reply routing
    // ------------------------------------------------------------------

    /**
     * Where a candidate's reply should go: the acting recruiter's own
     * address when a human sent the mail, otherwise the configured
     * fallback ({@link #SETTING_REPLY_TO_FALLBACK}). Returns null only
     * when there is no actor AND the fallback has been blanked — the
     * deliberate "no Reply-To" opt-out.
     */
    public String replyToFor(String actorUserUuid) {
        if (actorUserUuid != null && !actorUserUuid.isBlank()) {
            User actor = User.findById(actorUserUuid);
            if (actor != null && actor.getEmail() != null && !actor.getEmail().isBlank()) {
                return actor.getEmail().trim();
            }
        }
        return replyToFallback();
    }

    /** Display name in front of the configured sender address. */
    public String fromName() {
        return fromName;
    }

    /** The SES-verified envelope address every candidate email is sent from. */
    public String fromAddress() {
        return fromAddress;
    }

    /** The configured fallback address; null when unset or blanked. */
    public String replyToFallback() {
        return appSettingService.findByKey(SETTING_REPLY_TO_FALLBACK)
                .map(AppSetting::getSettingValue)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }

    /**
     * Set the fallback. An empty value is legal and means "send without a
     * Reply-To header"; anything else must look like an email address.
     */
    @Transactional
    public void updateReplyToFallback(String value, String actorUserUuid) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessRuleViolation(
                    "The reply-to address must be a valid email address, or empty to send without one");
        }
        appSettingService.saveSetting(SETTING_REPLY_TO_FALLBACK, normalized,
                SETTING_CATEGORY, actorUserUuid);
    }

    // ------------------------------------------------------------------
    // Copy resolution
    // ------------------------------------------------------------------

    /**
     * The copies a template asks for, resolved against this candidate.
     * A null template (blank email) copies nobody.
     */
    public EmailCopies copiesFor(RecruitmentEmailTemplate template, RecruitmentCandidate candidate,
                                 String applicationUuid, String actorUserUuid) {
        if (template == null) {
            return EmailCopies.none();
        }
        Set<RecruitmentEmailCopyRole> roles =
                RecruitmentEmailCopyRole.parseCsv(template.getCopyRoles());
        return new EmailCopies(template.getCopyMode(),
                copyResolver.resolve(candidate, applicationUuid, roles, actorUserUuid));
    }

    /** An explicit per-send override: caller-chosen people, re-authorized here. */
    public EmailCopies copiesOf(RecruitmentCandidate candidate, String applicationUuid,
                                List<String> userUuids, RecruitmentEmailCopyMode mode) {
        return new EmailCopies(mode,
                copyResolver.resolveExplicit(candidate, applicationUuid, userUuids));
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
