package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.slack.SlackAvailabilityViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The Method B free-text loop's worker (plan §12.2–§12.4). Runs on the
 * {@code ManagedExecutor} AFTER the inbound dispatch committed (the P25
 * offload shape — the extraction is an OpenAI round-trip that must not
 * ride a pooled connection or Slack's 3 s ack):
 * <ol>
 *   <li><b>Correlate</b> — the DM channel + thread ts against the
 *       stored proposal-card refs; fallback: the sender's single active
 *       request; no match → deterministic Danish template, done.</li>
 *   <li><b>Extract</b> (off-transaction) + <b>validate</b>
 *       deterministically.</li>
 *   <li><b>Disposition</b> in one short transaction: evidence +
 *       constraints persisted, events recorded, outbox actions
 *       enqueued — then the conversational replies, best-effort like
 *       the P25 assistant (a lost reply is re-askable; a lost summary
 *       card is not, which is why THAT goes through the outbox).</li>
 * </ol>
 * The message text is PII: it lands in event {@code pii} blocks and the
 * extraction call, NOWHERE else (plan §12.2) — evidence rows are pure
 * structure.
 */
@JBossLog
@ApplicationScoped
public class AvailabilityMessageService {

    /** Event-pii clamp — spot review needs the gist (P25 idiom). */
    static final int TEXT_LOG_MAX = 1000;

    /** PENDING evidence older than this is expired by the sweep. */
    static final int PENDING_TIMEOUT_HOURS = 48;

    /** Images processed per message (spec §11.1 allows several; three
     * calendar screenshots cover any realistic week). */
    static final int IMAGES_PER_MESSAGE_MAX = 3;

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    AvailabilityExtractionService extractionService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    SlackService slackService;

    @Inject
    SchedulingOutboxService outboxService;

    @Inject
    SchedulingEvidenceStorageService storageService;

    @Inject
    ObjectMapper objectMapper;

    /** How the message resolved to a request. */
    enum CorrelationOutcome {MATCHED, NO_ACTIVE, AMBIGUOUS}

    record Correlation(CorrelationOutcome outcome, String requestUuid,
                       LocalDate windowStart, LocalDate windowEnd) {
        static final Correlation NO_ACTIVE_RESULT =
                new Correlation(CorrelationOutcome.NO_ACTIVE, null, null, null);
        static final Correlation AMBIGUOUS_RESULT =
                new Correlation(CorrelationOutcome.AMBIGUOUS, null, null, null);
    }

    /**
     * Process one inbound DM message. Called on the executor — never
     * inside a caller transaction. Best-effort end to end; every
     * failure is logged content-free.
     */
    public void process(String actorUuid, String channelId, String messageTs, String text,
                        List<SlackInboundRequest.FileRef> files) {
        try {
            Correlation correlation = QuarkusTransaction.requiringNew()
                    .call(() -> correlate(actorUuid, channelId, messageTs));
            switch (correlation.outcome()) {
                case NO_ACTIVE -> {
                    reply(channelId, SlackAvailabilityViews.NO_ACTIVE_TEXT);
                    return;
                }
                case AMBIGUOUS -> {
                    reply(channelId, SlackAvailabilityViews.AMBIGUOUS_TEXT);
                    return;
                }
                case MATCHED -> {
                }
            }

            if (files != null && !files.isEmpty()) {
                // Phase 13: the message's images ARE the evidence; any
                // accompanying text rides along as explanation for each
                // vision call (spec §11.1 "text explaining the image").
                processImages(actorUuid, channelId, messageTs, text, correlation, files);
                return;
            }

            AvailabilityExtractionService.Extraction extraction = extractionService.extract(
                    LocalDate.now(), correlation.windowStart(), correlation.windowEnd(), text);
            AvailabilityExtractionService.Validated validated =
                    AvailabilityExtractionService.validate(extraction,
                            correlation.windowStart(), correlation.windowEnd());

            Disposition disposition = QuarkusTransaction.requiringNew()
                    .call(() -> dispose(actorUuid, channelId, messageTs, text,
                            correlation.requestUuid(), validated));
            answer(channelId, disposition, validated, actorUuid,
                    correlation.requestUuid(), text);
        } catch (Exception e) {
            // Content-free by the module's PII log discipline.
            log.errorf(e, "Method B availability message processing failed (channel kind=%s)",
                    channelId != null && channelId.startsWith("D") ? "dm" : "other");
        }
    }

    // ------------------------------------------------------------------
    // Phase 13 — calendar-image evidence (plan §13.1–§13.3)
    // ------------------------------------------------------------------

    /**
     * Ingest up to {@value IMAGES_PER_MESSAGE_MAX} attachments: download
     * with the bot token (participant already verified by correlation),
     * gate on MAGIC BYTES (Slack's mimetype is a claim) and the 20 MB
     * vision cap, store to S3 under the env-scoped prefix, THEN extract
     * (spec §11.2's order). Every image becomes its own evidence row;
     * {@code requiresConfirmation} is FORCED — an image never
     * auto-confirms (D9). Image bytes touch the vision call and S3,
     * never logs, never event pii.
     */
    private void processImages(String actorUuid, String channelId, String messageTs,
                               String text, Correlation correlation,
                               List<SlackInboundRequest.FileRef> files) {
        int processed = 0;
        for (SlackInboundRequest.FileRef file : files) {
            if (processed >= IMAGES_PER_MESSAGE_MAX) {
                break;
            }
            if (file.urlPrivateDownload() == null || file.urlPrivateDownload().isBlank()) {
                continue;
            }
            processed++;
            byte[] bytes;
            try {
                bytes = slackService.downloadFile(file.urlPrivateDownload(),
                        AvailabilityExtractionService.IMAGE_MAX_BYTES);
            } catch (Exception e) {
                log.warnf("Method B evidence image download failed: %s", e.getMessage());
                reply(channelId, SlackAvailabilityViews.imageFetchFailedText());
                continue;
            }
            String mime = AvailabilityExtractionService.sniffImageMime(bytes);
            if (mime == null) {
                reply(channelId, SlackAvailabilityViews.unsupportedImageText());
                continue;
            }
            String evidenceUuid = java.util.UUID.randomUUID().toString();
            String sha256 = sha256Hex(bytes);
            try {
                storageService.store(evidenceUuid, bytes, mime);
            } catch (Exception e) {
                log.warnf("Method B evidence image store failed: %s", e.getMessage());
                reply(channelId, SlackAvailabilityViews.imageFetchFailedText());
                continue;
            }

            AvailabilityExtractionService.Extraction extraction =
                    extractionService.extractFromImage(LocalDate.now(),
                            correlation.windowStart(), correlation.windowEnd(),
                            text, bytes, mime);
            AvailabilityExtractionService.Validated gated =
                    AvailabilityExtractionService.validate(extraction,
                            correlation.windowStart(), correlation.windowEnd());
            // D9: image-derived constraints ALWAYS confirm — no
            // confidence-threshold shortcut, whatever the model said.
            AvailabilityExtractionService.Validated validated = new
                    AvailabilityExtractionService.Validated(gated.intent(),
                    gated.language(), gated.timezone(), gated.coveredFrom(),
                    gated.coveredTo(), gated.constraints(), true,
                    gated.minConfidence(), gated.ambiguities(),
                    gated.clarifyingQuestion(), gated.rejectReason());

            Disposition disposition = QuarkusTransaction.requiringNew()
                    .call(() -> disposeImage(actorUuid, channelId, messageTs, text,
                            correlation.requestUuid(), evidenceUuid, sha256, validated));
            answer(channelId, disposition, validated, actorUuid,
                    correlation.requestUuid(), text);
        }
    }

    /**
     * The image twin of {@link #dispose}: availability intents become
     * PENDING IMAGE evidence (never auto-confirmed); everything else —
     * non-calendar images, failed validation — becomes a REJECTED row
     * whose S3 object the deletion sweep removes. The pre-generated
     * evidence uuid IS the S3 key, so the row and the object stay
     * matched whatever the outcome.
     */
    Disposition disposeImage(String actorUuid, String channelId, String messageTs,
                             String text, String requestUuid, String evidenceUuid,
                             String sha256,
                             AvailabilityExtractionService.Validated validated) {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(requestUuid);
        if (request == null || request.getStatus().isTerminal()) {
            return Disposition.USE_BUTTONS;
        }
        boolean availability = AvailabilitySchedulingPrompts.AVAILABILITY_INTENTS
                .contains(validated.intent());
        boolean rejected = !availability || validated.rejectReason() != null;

        RecruitmentAvailabilityEvidence evidence =
                newEvidence(request, actorUuid, channelId, messageTs, validated);
        evidence.setUuid(evidenceUuid);
        evidence.setSourceType(EvidenceSourceType.IMAGE);
        evidence.setFileSha256(sha256);
        if (rejected) {
            evidence.setConfirmationStatus(EvidenceConfirmationStatus.REJECTED);
        }
        evidence.persist();
        if (!rejected) {
            for (AvailabilityExtractionService.RawConstraint raw : validated.constraints()) {
                RecruitmentAvailabilityConstraint constraint =
                        new RecruitmentAvailabilityConstraint();
                constraint.setEvidenceUuid(evidence.getUuid());
                constraint.setType(raw.type());
                constraint.setStartAt(raw.start());
                constraint.setEndAt(raw.end());
                constraint.persist();
            }
        }
        recordReceived(request, actorUuid, evidence, validated, text,
                rejected
                        ? (validated.rejectReason() != null
                                ? validated.rejectReason()
                                : AvailabilityExtractionService.REJECT_UNKNOWN_INTENT)
                        : "STORED");
        if (rejected) {
            return validated.clarifyingQuestion() != null
                    ? Disposition.CLARIFY : Disposition.IMAGE_UNREADABLE;
        }
        outboxService.enqueue(request.getUuid(), null,
                SchedulingOutboxAction.SEND_EVIDENCE_DM, evidence.getUuid(),
                evidenceDmPayload(evidence.getUuid()));
        return Disposition.SUMMARY_QUEUED;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Step 1 — correlation (plan §12.2)
    // ------------------------------------------------------------------

    /**
     * Thread anchor first: a reply in a proposal card's thread carries
     * the card's ts, and the card's channel/ts is stored on the approval
     * row. Fallback: the sender's single active request (required OR
     * optional interviewer). Anything else is deterministic no-match.
     */
    Correlation correlate(String actorUuid, String channelId, String messageTs) {
        if (messageTs != null && channelId != null) {
            RecruitmentSlotApproval approval = RecruitmentSlotApproval
                    .<RecruitmentSlotApproval>find(
                            "slackChannelId = ?1 and slackMessageTs = ?2", channelId, messageTs)
                    .firstResult();
            if (approval != null && approval.getUserUuid().equals(actorUuid)) {
                RecruitmentProposedSlot slot =
                        RecruitmentProposedSlot.findById(approval.getSlotUuid());
                RecruitmentSchedulingRequest request = slot == null ? null
                        : RecruitmentSchedulingRequest.findById(slot.getRequestUuid());
                if (request != null && !request.getStatus().isTerminal()) {
                    return new Correlation(CorrelationOutcome.MATCHED, request.getUuid(),
                            request.getWindowStart(), request.getWindowEnd());
                }
            }
        }
        // Fallback: every non-terminal request the sender participates in.
        List<RecruitmentSchedulingRequest> active = RecruitmentSchedulingRequest
                .<RecruitmentSchedulingRequest>list("status not in ?1",
                        RecruitmentSchedulingService.terminalStatuses())
                .stream()
                .filter(request -> participates(request, actorUuid))
                .toList();
        if (active.isEmpty()) {
            return Correlation.NO_ACTIVE_RESULT;
        }
        if (active.size() > 1) {
            return Correlation.AMBIGUOUS_RESULT;
        }
        RecruitmentSchedulingRequest request = active.getFirst();
        return new Correlation(CorrelationOutcome.MATCHED, request.getUuid(),
                request.getWindowStart(), request.getWindowEnd());
    }

    /** Participant = required or optional interviewer (spec §11.2 step 1). */
    static boolean participates(RecruitmentSchedulingRequest request, String userUuid) {
        return (request.getInterviewerUuids() != null
                        && request.getInterviewerUuids().contains(userUuid))
                || (request.getOptionalInterviewerUuids() != null
                        && request.getOptionalInterviewerUuids().contains(userUuid));
    }

    // ------------------------------------------------------------------
    // Step 3 — disposition (one short transaction)
    // ------------------------------------------------------------------

    /** What the after-transaction reply step must do. */
    enum Disposition {SUMMARY_QUEUED, USE_BUTTONS, ROUTED, CLARIFY, UNPARSEABLE, IMAGE_UNREADABLE}

    Disposition dispose(String actorUuid, String channelId, String messageTs, String text,
                        String requestUuid,
                        AvailabilityExtractionService.Validated validated) {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(requestUuid);
        if (request == null || request.getStatus().isTerminal()) {
            return Disposition.USE_BUTTONS;
        }
        String intent = validated.intent();

        if (AvailabilitySchedulingPrompts.ROUTED_INTENTS.contains(intent)) {
            recordNoteRouted(request, actorUuid, intent, text);
            outboxService.enqueue(request.getUuid(), null,
                    SchedulingOutboxAction.SEND_RECRUITER_DM,
                    "NLU_NOTE:" + (messageTs != null ? messageTs : java.util.UUID.randomUUID()),
                    interviewerNotePayload(actorUuid, intent, text));
            return Disposition.ROUTED;
        }

        if (AvailabilitySchedulingPrompts.INTENT_APPROVE_SLOT.equals(intent)
                || AvailabilitySchedulingPrompts.INTENT_DECLINE_SLOT.equals(intent)) {
            // Text never converts into approval actions — the buttons
            // re-authorize against the approval row; a sentence cannot
            // (spec §13.3's closing rule).
            recordReceived(request, actorUuid, null, validated, text, "USE_BUTTONS");
            return Disposition.USE_BUTTONS;
        }

        boolean availability =
                AvailabilitySchedulingPrompts.AVAILABILITY_INTENTS.contains(intent);
        if (!availability || validated.rejectReason() != null) {
            // UNKNOWN or failed validation: a REJECTED evidence row is
            // the Phase 14 manual-review feed; the text stays in pii.
            RecruitmentAvailabilityEvidence evidence =
                    newEvidence(request, actorUuid, channelId, messageTs, validated);
            evidence.setConfirmationStatus(EvidenceConfirmationStatus.REJECTED);
            evidence.persist();
            recordReceived(request, actorUuid, evidence, validated, text,
                    validated.rejectReason() != null
                            ? validated.rejectReason()
                            : AvailabilityExtractionService.REJECT_UNKNOWN_INTENT);
            return validated.clarifyingQuestion() != null
                    ? Disposition.CLARIFY : Disposition.UNPARSEABLE;
        }

        // A valid availability submission: evidence + constraints.
        RecruitmentAvailabilityEvidence evidence =
                newEvidence(request, actorUuid, channelId, messageTs, validated);
        boolean autoConfirm = !validated.requiresConfirmation();
        if (autoConfirm) {
            evidence.setConfirmationStatus(EvidenceConfirmationStatus.CONFIRMED);
            evidence.setConfirmedAt(LocalDateTime.now());
        }
        evidence.persist();
        for (AvailabilityExtractionService.RawConstraint raw : validated.constraints()) {
            RecruitmentAvailabilityConstraint constraint =
                    new RecruitmentAvailabilityConstraint();
            constraint.setEvidenceUuid(evidence.getUuid());
            constraint.setType(raw.type());
            constraint.setStartAt(raw.start());
            constraint.setEndAt(raw.end());
            constraint.persist();
        }
        recordReceived(request, actorUuid, evidence, validated, text, "STORED");
        if (autoConfirm) {
            List<String> superseded = supersedeOlder(evidence);
            recordConfirmed(request, evidence, null, true, superseded);
            request.setNextActionAt(null); // wake the planner
        }
        outboxService.enqueue(request.getUuid(), null,
                SchedulingOutboxAction.SEND_EVIDENCE_DM, evidence.getUuid(),
                evidenceDmPayload(evidence.getUuid()));
        return Disposition.SUMMARY_QUEUED;
    }

    private RecruitmentAvailabilityEvidence newEvidence(
            RecruitmentSchedulingRequest request, String actorUuid, String channelId,
            String messageTs, AvailabilityExtractionService.Validated validated) {
        RecruitmentAvailabilityEvidence evidence = new RecruitmentAvailabilityEvidence();
        evidence.setRequestUuid(request.getUuid());
        evidence.setUserUuid(actorUuid);
        evidence.setSourceType(
                AvailabilitySchedulingPrompts.INTENT_CORRECT_PRIOR.equals(validated.intent())
                        ? EvidenceSourceType.CORRECTION : EvidenceSourceType.TEXT);
        evidence.setIntent(validated.intent());
        evidence.setSlackChannelId(channelId);
        evidence.setSlackMessageTs(messageTs);
        evidence.setCoveredFrom(validated.coveredFrom());
        evidence.setCoveredTo(validated.coveredTo());
        evidence.setTimezone(validated.timezone() != null
                ? validated.timezone() : "Europe/Copenhagen");
        evidence.setLanguage(validated.language());
        evidence.setConfidence(validated.minConfidence());
        evidence.setExpiresAt(expiryOf(validated.coveredTo()));
        return evidence;
    }

    /** Evidence expires at the end of its covered period (spec §23). */
    static LocalDateTime expiryOf(LocalDate coveredTo) {
        return coveredTo == null ? null : coveredTo.atTime(23, 59, 59);
    }

    /**
     * The spec §12.3 supersede rule, applied on CONFIRM: older
     * PENDING/CONFIRMED evidence from the same interviewer on the same
     * request whose covered range overlaps (or is unbounded) yields to
     * the newer confirmed statement. Returns the superseded uuids for
     * the audit payload.
     */
    public static List<String> supersedeOlder(RecruitmentAvailabilityEvidence winner) {
        List<RecruitmentAvailabilityEvidence> candidates = RecruitmentAvailabilityEvidence
                .list("requestUuid = ?1 and userUuid = ?2 and uuid != ?3 "
                                + "and confirmationStatus in ?4",
                        winner.getRequestUuid(), winner.getUserUuid(), winner.getUuid(),
                        List.of(EvidenceConfirmationStatus.PENDING,
                                EvidenceConfirmationStatus.CONFIRMED));
        List<String> superseded = new java.util.ArrayList<>();
        for (RecruitmentAvailabilityEvidence older : candidates) {
            if (older.getCreatedAt().isAfter(winner.getCreatedAt())) {
                continue;
            }
            if (rangesDisjoint(winner.getCoveredFrom(), winner.getCoveredTo(),
                    older.getCoveredFrom(), older.getCoveredTo())) {
                continue;
            }
            older.setConfirmationStatus(EvidenceConfirmationStatus.SUPERSEDED);
            superseded.add(older.getUuid());
        }
        return superseded;
    }

    /** Null ranges are unbounded — they overlap everything. */
    static boolean rangesDisjoint(LocalDate aFrom, LocalDate aTo,
                                  LocalDate bFrom, LocalDate bTo) {
        if (aFrom == null || aTo == null || bFrom == null || bTo == null) {
            return false;
        }
        return aTo.isBefore(bFrom) || bTo.isBefore(aFrom);
    }

    // ------------------------------------------------------------------
    // Step 4 — the conversational replies (best-effort, after commit)
    // ------------------------------------------------------------------

    private void answer(String channelId, Disposition disposition,
                        AvailabilityExtractionService.Validated validated,
                        String actorUuid, String requestUuid, String text) {
        switch (disposition) {
            case SUMMARY_QUEUED -> {
                // The consequential message is the outbox-delivered
                // summary card — nothing conversational to add here.
            }
            case USE_BUTTONS -> reply(channelId,
                    SlackAvailabilityViews.useButtonsText(validated.language()));
            case ROUTED -> reply(channelId,
                    SlackAvailabilityViews.routedAckText(validated.language()));
            case UNPARSEABLE -> reply(channelId,
                    SlackAvailabilityViews.unparseableText(validated.language()));
            case IMAGE_UNREADABLE -> reply(channelId,
                    SlackAvailabilityViews.imageUnreadableText(validated.language()));
            case CLARIFY -> {
                // The ONE place model prose reaches the interviewer —
                // visibly marked and audited (D6, plan §12.4).
                String question = SlackAvailabilityViews.AI_PREFIX
                        + validated.clarifyingQuestion();
                reply(channelId, SlackAvailabilityViews.clamp(question));
                try {
                    QuarkusTransaction.requiringNew().run(() ->
                            recordExchange(requestUuid, actorUuid, validated, text));
                } catch (Exception e) {
                    log.error("AI_SCHEDULING_EXCHANGE could not be recorded", e);
                }
            }
        }
    }

    private void reply(String channelId, String text) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }
        slackService.sendMessage(channelId, text);
    }

    // ------------------------------------------------------------------
    // Audit events
    // ------------------------------------------------------------------

    private void recordReceived(RecruitmentSchedulingRequest request, String actorUuid,
                                RecruitmentAvailabilityEvidence evidence,
                                AvailabilityExtractionService.Validated validated,
                                String text, String disposition) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.AVAILABILITY_EVIDENCE_RECEIVED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorUser(actorUuid)
                .payload("request_uuid", request.getUuid())
                .payload("source_type", evidence != null
                        ? evidence.getSourceType().name() : EvidenceSourceType.TEXT.name())
                .payload("intent", validated.intent())
                .payload("disposition", disposition)
                .payload("constraint_count", validated.constraints().size())
                .payload("requires_confirmation", validated.requiresConfirmation())
                .payload("prompt_version", AvailabilitySchedulingPrompts.PROMPT_VERSION);
        if (evidence != null) {
            builder.payload("evidence_uuid", evidence.getUuid());
        }
        if (validated.minConfidence() != null) {
            builder.payload("confidence_min", validated.minConfidence().toPlainString());
        }
        if (text != null && !text.isBlank()) {
            builder.pii("message", text.length() <= TEXT_LOG_MAX
                    ? text : text.substring(0, TEXT_LOG_MAX));
        }
        eventRecorder.record(builder);
    }

    /** Shared by auto-confirm here and the Bekræft handler. */
    public void recordConfirmed(RecruitmentSchedulingRequest request,
                                RecruitmentAvailabilityEvidence evidence,
                                String actorUuid, boolean auto,
                                List<String> supersededUuids) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.AVAILABILITY_EVIDENCE_CONFIRMED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .payload("request_uuid", request.getUuid())
                .payload("evidence_uuid", evidence.getUuid())
                .payload("auto", auto);
        if (actorUuid != null) {
            builder.actorUser(actorUuid);
        } else {
            builder.actorScheduler();
        }
        if (supersededUuids != null && !supersededUuids.isEmpty()) {
            builder.payload("superseded_evidence_uuids", supersededUuids);
        }
        eventRecorder.record(builder);
    }

    private void recordNoteRouted(RecruitmentSchedulingRequest request, String actorUuid,
                                  String intent, String text) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.SCHEDULING_NOTE_ROUTED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorUser(actorUuid)
                .payload("request_uuid", request.getUuid())
                .payload("note_kind", "NLU_" + intent);
        if (text != null && !text.isBlank()) {
            builder.pii("note", text.length() <= TEXT_LOG_MAX
                    ? text : text.substring(0, TEXT_LOG_MAX));
        }
        eventRecorder.record(builder);
    }

    private void recordExchange(String requestUuid, String actorUuid,
                                AvailabilityExtractionService.Validated validated,
                                String text) {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(requestUuid);
        RecruitmentApplication application = request == null ? null
                : RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_SCHEDULING_EXCHANGE)
                .application(request != null ? request.getApplicationUuid() : null)
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorUser(actorUuid)
                .payload("request_uuid", requestUuid)
                .payload("intent", validated.intent())
                .payload("prompt_version", AvailabilitySchedulingPrompts.PROMPT_VERSION);
        if (validated.clarifyingQuestion() != null) {
            builder.pii("question", validated.clarifyingQuestion());
        }
        if (text != null && !text.isBlank()) {
            builder.pii("source_message", text.length() <= TEXT_LOG_MAX
                    ? text : text.substring(0, TEXT_LOG_MAX));
        }
        eventRecorder.record(builder);
    }

    // ------------------------------------------------------------------
    // Outbox payloads
    // ------------------------------------------------------------------

    private String evidenceDmPayload(String evidenceUuid) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("evidenceUuid", evidenceUuid, "kind", "SUMMARY"));
        } catch (Exception e) {
            return "{\"evidenceUuid\":\"" + evidenceUuid + "\",\"kind\":\"SUMMARY\"}";
        }
    }

    /** The note rides to the recruiter DM only (the Ph11 none-work idiom). */
    private String interviewerNotePayload(String interviewerUuid, String intent, String text) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "notice", "INTERVIEWER_NOTE",
                    "interviewerUuid", interviewerUuid,
                    "intent", intent,
                    "note", text == null ? "" : text));
        } catch (Exception e) {
            return "{\"notice\":\"INTERVIEWER_NOTE\"}";
        }
    }
}
