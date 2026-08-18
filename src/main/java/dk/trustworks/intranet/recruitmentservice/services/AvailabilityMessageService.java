package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceConfirmationStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import dk.trustworks.intranet.recruitmentservice.slack.SlackAvailabilityViews;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Method B free-text loop's worker (plan §12.2–§12.4). Runs on the
 * {@link SchedulingAsyncRunner}'s plain thread AFTER the inbound
 * dispatch committed (the extraction is an OpenAI round-trip that must
 * not ride a pooled connection or Slack's 3 s ack — and must never see
 * a propagated transaction context, finding F10):
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
     * calendar screenshots cover any realistic week). The S3 deletion sweep
     * derives its slot count from this — see
     * {@code SchedulingEvidenceStorageService.MAX_IMAGES_PER_EVIDENCE}. */
    public static final int IMAGES_PER_MESSAGE_MAX = 3;

    /**
     * Image submissions one scheduling request may run through the vision
     * pipeline. Security review 2026-08-18 (finding 4): each submission now
     * costs 2-4 reasoning-model calls carrying up to three high-detail images,
     * and nothing bounded how often a (possibly compromised) interviewer account
     * could repeat that for the days a request stays open.
     * <p>
     * Set FAR above real use — a genuine interviewer sends one to three images
     * once or twice — precisely so it never becomes friction for the people this
     * path is built for (owner decision 2026-08-18). It is a cost backstop, not
     * a throttle.
     */
    static final int IMAGE_SUBMISSIONS_PER_REQUEST_MAX = 20;

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
    RecruitmentSchedulingService schedulingService;

    @Inject
    dk.trustworks.intranet.recruitmentservice.slack.SlackProposalCardService cardService;

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

            processText(actorUuid, channelId, messageTs, text, correlation);
        } catch (Exception e) {
            // Content-free by the module's PII log discipline.
            log.errorf(e, "Method B availability message processing failed (channel kind=%s)",
                    channelId != null && channelId.startsWith("D") ? "dm" : "other");
        }
    }

    /**
     * The F7 entry: a note typed into the legacy "Foreslå anden tid"
     * modal is availability information exactly like a plain DM reply,
     * so it runs the same extraction pipeline — only the correlation is
     * already settled (the modal's approval claim names the request).
     * Called on the {@link SchedulingAsyncRunner} after the submit's
     * dispatch committed; replies land in the proposal DM's channel.
     */
    public void processNote(String actorUuid, String channelId, String requestUuid,
                            String text) {
        try {
            Correlation correlation = QuarkusTransaction.requiringNew().call(() -> {
                RecruitmentSchedulingRequest request =
                        RecruitmentSchedulingRequest.findById(requestUuid);
                if (request == null || request.getStatus().isTerminal()) {
                    return Correlation.NO_ACTIVE_RESULT;
                }
                return new Correlation(CorrelationOutcome.MATCHED, request.getUuid(),
                        request.getWindowStart(), request.getWindowEnd());
            });
            if (correlation.outcome() != CorrelationOutcome.MATCHED) {
                // The modal already answered the interviewer; a closed
                // request needs no second reply.
                return;
            }
            processText(actorUuid, channelId, null, text, correlation);
        } catch (Exception e) {
            log.error("Method B availability note processing failed", e);
        }
    }

    /** The shared text pipeline: extract → validate → dispose → answer. */
    private void processText(String actorUuid, String channelId, String messageTs,
                             String text, Correlation correlation) {
        AvailabilityExtractionService.Extraction extraction;
        try {
            extraction = extractionService.extract(
                    LocalDate.now(), correlation.windowStart(), correlation.windowEnd(), text);
        } catch (Exception e) {
            // AI down (§27 scenario 11): the failure becomes a REJECTED
            // evidence row + the deterministic template — never silence.
            log.warnf("Method B availability extraction failed — treating as UNKNOWN: %s",
                    e.getMessage());
            extraction = AvailabilityExtractionService.Extraction.unknown();
        }
        AvailabilityExtractionService.Validated validated =
                AvailabilityExtractionService.validate(extraction,
                        correlation.windowStart(), correlation.windowEnd());

        Outcome outcome = QuarkusTransaction.requiringNew()
                .call(() -> dispose(actorUuid, channelId, messageTs, text,
                        correlation.requestUuid(), validated));
        answer(channelId, outcome, validated, actorUuid,
                correlation.requestUuid(), text);
    }

    // ------------------------------------------------------------------
    // Phase 13 — calendar-image evidence (plan §13.1–§13.3)
    // ------------------------------------------------------------------

    /**
     * Ingest up to {@value IMAGES_PER_MESSAGE_MAX} attachments: download
     * with the bot token (participant already verified by correlation),
     * gate on MAGIC BYTES (Slack's mimetype is a claim) and the 20 MB
     * vision cap, extract, and only store to S3 once the extraction
     * produced a persistable reading. Extraction-first is the F11 fix:
     * the original order (store, then extract) meant every extraction
     * failure abandoned an interviewer's calendar screenshot as an
     * untracked PII object no deletion path could find. Every image
     * becomes its own evidence row; {@code requiresConfirmation} is
     * FORCED — an image never auto-confirms (D9). Image bytes touch the
     * vision call and S3, never logs, never event pii.
     */
    private void processImages(String actorUuid, String channelId, String messageTs,
                               String text, Correlation correlation,
                               List<SlackInboundRequest.FileRef> files) {
        // v2: ALL images of one message go into ONE model call and ONE evidence
        // row. Reading them together is what lets an all-day band that starts
        // in one crop and continues past the edge of the next resolve at all;
        // per-image calls structurally could not see it.
        List<byte[]> payloads = new ArrayList<>();
        List<String> mimes = new ArrayList<>();
        for (SlackInboundRequest.FileRef file : files) {
            if (payloads.size() >= IMAGES_PER_MESSAGE_MAX) {
                break;
            }
            if (file.urlPrivateDownload() == null || file.urlPrivateDownload().isBlank()) {
                continue;
            }
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
            payloads.add(bytes);
            mimes.add(mime);
        }
        if (payloads.isEmpty()) {
            // Every attachment failed; the per-file replies already said so.
            return;
        }
        // Cost backstop (security review finding 4). Counted BEFORE the model
        // calls, and deliberately generous: a real interviewer never reaches it.
        long alreadySubmitted = QuarkusTransaction.requiringNew().call(() ->
                RecruitmentAvailabilityEvidence.count(
                        "requestUuid = ?1 and userUuid = ?2 and sourceType = ?3",
                        correlation.requestUuid(), actorUuid, EvidenceSourceType.IMAGE));
        if (alreadySubmitted >= IMAGE_SUBMISSIONS_PER_REQUEST_MAX) {
            log.warnf("Method B image submission cap reached request=%s submissions=%d",
                    correlation.requestUuid(), alreadySubmitted);
            reply(channelId, SlackAvailabilityViews.imageCapReachedText());
            return;
        }

        String evidenceUuid = java.util.UUID.randomUUID().toString();
        String sha256 = combinedSha256Hex(payloads);

        List<OpenAIService.ImageInput> images = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            images.add(new OpenAIService.ImageInput(
                    java.util.Base64.getEncoder().encodeToString(payloads.get(i)), mimes.get(i)));
        }

        AvailabilityExtractionService.Validated validated;
        AvailabilityExtractionService.ImageReading reading = null;
        try {
            reading = extractionService.readImages(LocalDate.now(),
                    correlation.windowStart(), correlation.windowEnd(), text, images);
            validated = AvailabilityExtractionService.validateImage(reading,
                    correlation.windowStart(), correlation.windowEnd());
            if (reading.trust().suspicious()) {
                // Frictionless by owner decision (2026-08-18): a low-trust
                // reading costs the interviewer nothing extra — it is recorded
                // for the audit trail and shown on the card, never turned into
                // a question or a block.
                log.infof("Method B image reading flagged low-trust reasons=%s passes=%d",
                        reading.trust().reasons(), reading.passes());
            }
        } catch (Exception e) {
            // AI down (§27 scenario 11): a REJECTED row for the manual-review
            // list, the deterministic template, and — because nothing was
            // stored — no orphan (F11).
            log.warnf("Method B image extraction failed — treating as UNKNOWN: %s",
                    e.getMessage());
            validated = AvailabilityExtractionService.validate(
                    AvailabilityExtractionService.Extraction.unknown(),
                    correlation.windowStart(), correlation.windowEnd());
        }
        AvailabilityExtractionService.Validated disposable = validated;
        AvailabilityImageReading.Derived derived =
                reading == null ? null : reading.derived();

        Outcome outcome = QuarkusTransaction.requiringNew()
                .call(() -> disposeImage(actorUuid, channelId, messageTs, text,
                        correlation.requestUuid(), evidenceUuid, sha256, disposable, derived));
        if (outcome.disposition() == Disposition.SUMMARY_QUEUED) {
            // The reading is persisted and awaits confirmation — NOW the
            // originals earn their (evidence-row-tracked) S3 slots. A failed
            // put leaves a row without an object; the D10 deleter sweeps every
            // slot idempotently, so nothing dangles.
            for (int i = 0; i < payloads.size(); i++) {
                try {
                    storageService.store(evidenceUuid, i, payloads.get(i), mimes.get(i));
                } catch (Exception e) {
                    log.warnf("Method B evidence image store failed: %s", e.getMessage());
                }
            }
        }
        answer(channelId, outcome, disposable, actorUuid,
                correlation.requestUuid(), text);
    }

    /**
     * The image twin of {@link #dispose}: availability intents become
     * PENDING IMAGE evidence (never auto-confirmed); everything else —
     * non-calendar images, failed validation — becomes a REJECTED row
     * whose S3 object the deletion sweep removes. The pre-generated
     * evidence uuid IS the S3 key, so the row and the object stay
     * matched whatever the outcome.
     */
    Outcome disposeImage(String actorUuid, String channelId, String messageTs,
                         String text, String requestUuid, String evidenceUuid,
                         String sha256,
                         AvailabilityExtractionService.Validated validated,
                         AvailabilityImageReading.Derived derived) {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(requestUuid);
        if (request == null || request.getStatus().isTerminal()) {
            return Outcome.of(Disposition.USE_BUTTONS);
        }
        boolean availability = AvailabilitySchedulingPrompts.AVAILABILITY_INTENTS
                .contains(validated.intent());
        boolean rejected = !availability || validated.rejectReason() != null;

        RecruitmentAvailabilityEvidence evidence =
                newEvidence(request, actorUuid, channelId, messageTs, validated);
        evidence.setUuid(evidenceUuid);
        evidence.setSourceType(EvidenceSourceType.IMAGE);
        evidence.setFileSha256(sha256);
        if (derived != null && !derived.discardedDays().isEmpty()) {
            evidence.setUnreadableDays(derived.discardedDays().stream()
                    .map(LocalDate::toString)
                    .collect(java.util.stream.Collectors.joining(",")));
        }
        evidence.setReadTrust(validated.readTrust());
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
            return Outcome.of(validated.clarifyingQuestion() != null
                    ? Disposition.CLARIFY : Disposition.IMAGE_UNREADABLE);
        }
        outboxService.enqueue(request.getUuid(), null,
                SchedulingOutboxAction.SEND_EVIDENCE_DM, evidence.getUuid(),
                evidenceDmPayload(evidence.getUuid()));
        // F12: a calendar image answers the open proposals too.
        return new Outcome(Disposition.SUMMARY_QUEUED,
                resolveOpenProposals(request, actorUuid));
    }

    /**
     * One evidence row now owns several images, but {@code file_sha256} is a
     * single column. Hash the concatenation of the per-image digests: still a
     * fixed 64 hex chars, still provable after the originals are deleted (D10),
     * and order-sensitive so the set of images is pinned exactly.
     */
    static String combinedSha256Hex(List<byte[]> payloads) {
        if (payloads.size() == 1) {
            return sha256Hex(payloads.getFirst());
        }
        StringBuilder concatenated = new StringBuilder(payloads.size() * 64);
        for (byte[] bytes : payloads) {
            concatenated.append(sha256Hex(bytes));
        }
        return sha256Hex(concatenated.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    /** A disposition plus the card rewrites it earned (F12). */
    record Outcome(Disposition disposition,
                   List<dk.trustworks.intranet.recruitmentservice.slack
                           .SlackProposalCardService.ComputedUpdate> cardUpdates) {
        static Outcome of(Disposition disposition) {
            return new Outcome(disposition, List.of());
        }
    }

    Outcome dispose(String actorUuid, String channelId, String messageTs, String text,
                    String requestUuid,
                    AvailabilityExtractionService.Validated validated) {
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(requestUuid);
        if (request == null || request.getStatus().isTerminal()) {
            return Outcome.of(Disposition.USE_BUTTONS);
        }
        String intent = validated.intent();

        if (AvailabilitySchedulingPrompts.ROUTED_INTENTS.contains(intent)) {
            recordNoteRouted(request, actorUuid, intent, text);
            outboxService.enqueue(request.getUuid(), null,
                    SchedulingOutboxAction.SEND_RECRUITER_DM,
                    "NLU_NOTE:" + (messageTs != null ? messageTs : java.util.UUID.randomUUID()),
                    interviewerNotePayload(actorUuid, intent, text));
            return Outcome.of(Disposition.ROUTED);
        }

        if (AvailabilitySchedulingPrompts.INTENT_APPROVE_SLOT.equals(intent)
                || AvailabilitySchedulingPrompts.INTENT_DECLINE_SLOT.equals(intent)) {
            // Text never converts into approval actions — the buttons
            // re-authorize against the approval row; a sentence cannot
            // (spec §13.3's closing rule).
            recordReceived(request, actorUuid, null, validated, text, "USE_BUTTONS");
            return Outcome.of(Disposition.USE_BUTTONS);
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
            return Outcome.of(validated.clarifyingQuestion() != null
                    ? Disposition.CLARIFY : Disposition.UNPARSEABLE);
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
        // F12: answering by message settles the open proposals like a
        // button press would — the cards must not stay live.
        return new Outcome(Disposition.SUMMARY_QUEUED,
                resolveOpenProposals(request, actorUuid));
    }

    /**
     * The F12 rule (owner decision 2026-08-14): an availability message
     * IS the interviewer's answer to whatever proposals still await
     * them. Every PENDING approval of theirs on a live slot becomes a
     * decline — slot REJECTED ({@code INTERVIEWER_REPLIED}), holds
     * compensated, the combined cards re-rendered from state — and the
     * planner re-searches with the new information once it is
     * confirmed. Slots they already approved stay approved.
     */
    private List<dk.trustworks.intranet.recruitmentservice.slack
            .SlackProposalCardService.ComputedUpdate> resolveOpenProposals(
            RecruitmentSchedulingRequest request, String actorUuid) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        List<RecruitmentProposedSlot> slots =
                RecruitmentProposedSlot.list("requestUuid = ?1", request.getUuid());
        boolean resolvedAny = false;
        for (RecruitmentProposedSlot slot : slots) {
            if (!slot.getStatus().isLive()) {
                continue;
            }
            RecruitmentSlotApproval mine = RecruitmentSlotApproval
                    .<RecruitmentSlotApproval>find(
                            "slotUuid = ?1 and userUuid = ?2 and status = ?3",
                            slot.getUuid(), actorUuid, SlotApprovalStatus.PENDING)
                    .firstResult();
            if (mine == null) {
                continue;
            }
            mine.setStatus(SlotApprovalStatus.DECLINED);
            mine.setRespondedAt(LocalDateTime.now());
            slot.setStatus(ProposedSlotStatus.REJECTED);
            slot.setRejectReason(RecruitmentSchedulingService.REASON_INTERVIEWER_REPLIED);
            schedulingService.releaseHolds(request, slot);
            resolvedAny = true;
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.SLOT_DECLINED)
                    .application(request.getApplicationUuid())
                    .candidate(application != null ? application.getCandidateUuid() : null)
                    .position(application != null ? application.getPositionUuid() : null)
                    .actorUser(actorUuid)
                    .payload("request_uuid", request.getUuid())
                    .payload("slot_uuid", slot.getUuid())
                    .payload("origin", "slack_message"));
        }
        if (!resolvedAny) {
            return List.of();
        }
        // Wake the sweep for status recompute; the search itself waits
        // for the evidence to confirm (the orchestrator's
        // fresh-pending-evidence grace).
        request.setNextActionAt(null);
        return cardService.computeRequestRefresh(request);
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
     * Which confirmed statements REPLACE older overlapping evidence: a
     * full availability picture and an explicit correction. The additive
     * intents (a busy interval, an available interval, a preference)
     * LAYER on top instead — the retest showed a one-hour busy note
     * retiring a whole week's availability statement via the blind
     * range-overlap rule, which read as the system forgetting what it
     * was just told. Engine precedence (BUSY beats AVAILABLE) already
     * resolves genuine conflicts between layered statements.
     */
    static boolean replacesOlderEvidence(String intent) {
        return AvailabilitySchedulingPrompts.INTENT_PROVIDE_AVAILABILITY.equals(intent)
                || AvailabilitySchedulingPrompts.INTENT_CORRECT_PRIOR.equals(intent);
    }

    /**
     * The spec §12.3 supersede rule, applied on CONFIRM: older
     * PENDING/CONFIRMED evidence from the same interviewer on the same
     * request whose covered range overlaps (or is unbounded) yields to
     * the newer confirmed statement — when the newer statement is a
     * REPLACING one ({@link #replacesOlderEvidence}); additive
     * statements supersede nothing. Returns the superseded uuids for
     * the audit payload.
     */
    public static List<String> supersedeOlder(RecruitmentAvailabilityEvidence winner) {
        if (!replacesOlderEvidence(winner.getIntent())) {
            return List.of();
        }
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

    private void answer(String channelId, Outcome outcome,
                        AvailabilityExtractionService.Validated validated,
                        String actorUuid, String requestUuid, String text) {
        // F12 first: settle the proposal cards, THEN talk — the settled
        // card is what makes the conversational reply legible.
        cardService.perform(outcome.cardUpdates());
        switch (outcome.disposition()) {
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
