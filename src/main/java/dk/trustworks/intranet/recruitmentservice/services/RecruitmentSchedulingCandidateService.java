package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.PublicSchedulingResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentOptionBatch;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.OptionBatchStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Method B's candidate-facing half (plan §11): sending the option batch,
 * the anonymous token-addressed public page, the concurrency-safe
 * selection, the none-work escalation and the deadline expiry.
 *
 * <h3>Token</h3>
 * 256-bit random, base64url (43 chars), minted at SEND time in the same
 * transaction that creates the batch, renders the mail and queues it —
 * the {@code {{consent_link}}} lesson: the raw token exists only in the
 * queued mail body; the DB stores its SHA-256 hex.
 *
 * <h3>Uniform failure</h3>
 * Every invalid public case — bad shape, unknown token, batch closed or
 * expired, request beyond the choosing states, flag off — resolves to
 * {@code null} here and a byte-identical 404 in the resource. The ONE
 * deliberate exception (plan §11.1): a repeat POST of an already-committed
 * action answers the committed outcome, so a double-click or a resumed
 * page never strands the candidate.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSchedulingCandidateService {

    /** 32 random bytes, base64url without padding — exactly 43 chars. */
    static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    public static final String TEMPLATE_KEY_OPTION_INVITATION = "OPTION_INVITATION";

    /** Default answer window: 3 business days, at this hour (defaults §29.2). */
    static final int DEADLINE_BUSINESS_DAYS = 3;
    static final int DEADLINE_HOUR = 16;

    /** Slot protection outlives the answer deadline by this buffer (plan §9.4). */
    static final int SLOT_EXPIRY_BUFFER_HOURS = 1;

    @Inject
    RecruitmentSchedulingService schedulingService;

    @Inject
    SchedulingOutboxService outboxService;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "dk.trustworks.recruitment.scheduling.options-base-url",
            defaultValue = "https://intra.trustworks.dk")
    String optionsBaseUrl;

    // ---- Sending the options (advance-sweep step) --------------------------

    /**
     * READY_FOR_CANDIDATE and the review gate is passed: mint the token,
     * create the ACTIVE batch, mark the held slots OFFERED (expiry =
     * deadline + buffer), queue the OPTION_INVITATION mail with the link
     * resolved, and move to WAITING_FOR_CANDIDATE — one transaction (the
     * caller's), so a deploy mid-step sends nothing twice.
     * <p>
     * Returns true when the batch went out. Recoverable obstacles
     * (template missing, mail infra) log + defer; unrecoverable ones
     * (candidate without email) hand back.
     */
    public boolean sendOptionsIfReady(RecruitmentSchedulingRequest request, LocalDateTime now) {
        if (request.getStatus() != SchedulingRequestStatus.READY_FOR_CANDIDATE) {
            return false;
        }
        if (request.isReviewRequired() && request.getOptionsApprovedAt() == null) {
            return false; // waiting for the recruiter's review & send (D11)
        }
        long activeBatches = RecruitmentOptionBatch.count(
                "requestUuid = ?1 and status = ?2",
                request.getUuid(), OptionBatchStatus.ACTIVE);
        if (activeBatches > 0) {
            return false; // already out — WAITING transition follows it atomically
        }
        List<RecruitmentProposedSlot> held = RecruitmentProposedSlot.list(
                "requestUuid = ?1 and status = ?2 order by slotStart",
                request.getUuid(), ProposedSlotStatus.HELD);
        if (held.isEmpty()) {
            return false; // the pipeline recompute will regress the status
        }

        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : RecruitmentCandidate.findById(application.getCandidateUuid());
        boolean manual = request.isManualCandidateDelivery();
        if (candidate == null || (!manual && (candidate.getEmail() == null
                || candidate.getEmail().isBlank()))) {
            // No way to reach the candidate — automation cannot proceed.
            // (Manual delivery needs no email: the recruiter carries the
            // link through their own channel.)
            schedulingService.handBack(request, null, "CANDIDATE_NO_EMAIL");
            return false;
        }
        RecruitmentEmailTemplate template = manual ? null
                : emailService.findActiveByKey(TEMPLATE_KEY_OPTION_INVITATION);
        if (!manual && template == null) {
            log.warnf("Method B request %s: no active %s template — options not sent, retrying",
                    request.getUuid(), TEMPLATE_KEY_OPTION_INVITATION);
            request.setNextActionAt(now.plusMinutes(15));
            return false;
        }

        LocalDateTime deadline = request.getCandidateDeadline() != null
                && request.getCandidateDeadline().isAfter(now)
                ? request.getCandidateDeadline()
                : defaultCandidateDeadline(now);
        request.setCandidateDeadline(deadline);

        String token = RecruitmentConsentService.generateToken();
        RecruitmentOptionBatch batch = new RecruitmentOptionBatch();
        batch.setRequestUuid(request.getUuid());
        batch.setTokenHash(RecruitmentConsentService.sha256Hex(token));
        batch.setSentAt(now);
        batch.setExpiresAt(deadline);
        batch.persist();

        for (RecruitmentProposedSlot slot : held) {
            slot.setStatus(ProposedSlotStatus.OFFERED);
            slot.setExpiresAt(deadline.plusHours(SLOT_EXPIRY_BUFFER_HOURS));
        }

        RecruitmentPosition position =
                RecruitmentPosition.findById(application.getPositionUuid());
        String optionsLink = optionsBaseUrl + "/interview-valg/" + token;
        if (manual) {
            // Recruiter-sends-the-link mode (owner request 2026-08-15):
            // the candidate hears NOTHING from the system — the recruiter
            // gets the link + a ready-to-send draft as a Slack DM. The
            // raw link rides in the outbox payload exactly as it rides in
            // the queued mail body on the email path.
            outboxService.enqueue(request.getUuid(), null,
                    dk.trustworks.intranet.recruitmentservice.model.enums
                            .SchedulingOutboxAction.SEND_RECRUITER_DM,
                    "MANUAL_LINK:" + batch.getUuid(),
                    manualLinkPayload(optionsLink, deadline));
        } else {
            Map<String, String> extras = new LinkedHashMap<>();
            extras.put("options_link", optionsLink);
            extras.put("options_deadline", SlackSchedulingViews.danishDayTime(deadline));
            extras.put("options_list", optionsList(held));
            RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                    template.getSubject(), template.getBody(), candidate, position,
                    extras, template.getBodyFormat());

            emailService.send(candidate, application.getUuid(), application.getPositionUuid(),
                    template.getTemplateKey(), template.getUuid(),
                    rendered.subject(), rendered.body(), template.getBodyFormat(),
                    "METHOD_B_OPTIONS", null,
                    RecruitmentEventBuilder.event(RecruitmentEventType.EMAIL_SENT)
                            .actorScheduler()
                            .payload("request_uuid", request.getUuid())
                            .payload("batch_uuid", batch.getUuid()),
                    emailService.visibilityFor(candidate.getUuid()),
                    emailService.replyToFor(request.getRecruiterUuid()),
                    emailService.copiesFor(template, candidate, application.getUuid(), null));
        }

        SchedulingStateMachine.require(request.getStatus(),
                SchedulingRequestStatus.WAITING_FOR_CANDIDATE);
        request.setStatus(SchedulingRequestStatus.WAITING_FOR_CANDIDATE);
        request.setNextActionAt(null);

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.OPTIONS_SENT)
                .application(request.getApplicationUuid())
                .candidate(candidate.getUuid())
                .position(application.getPositionUuid())
                .actorScheduler()
                .payload("request_uuid", request.getUuid())
                .payload("batch_uuid", batch.getUuid())
                .payload("option_slot_uuids",
                        held.stream().map(RecruitmentProposedSlot::getUuid).toList())
                .payload("delivery", manual ? "MANUAL" : "EMAIL")
                .payload("deadline", deadline.toString()));
        log.infof("Method B request %s: %d options %s (deadline %s)",
                request.getUuid(), held.size(),
                manual ? "handed to the recruiter for manual delivery"
                        : "sent to the candidate", deadline);
        return true;
    }

    /** The MANUAL_LINK recruiter-notice payload. */
    private String manualLinkPayload(String link, LocalDateTime deadline) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "notice", "MANUAL_LINK",
                    "link", link,
                    "deadline", deadline.toString()));
        } catch (Exception e) {
            return "{\"notice\":\"MANUAL_LINK\",\"link\":\"" + link
                    + "\",\"deadline\":\"" + deadline + "\"}";
        }
    }

    /** Danish numbered option lines for {@code {{options_list}}}. */
    static String optionsList(List<RecruitmentProposedSlot> offered) {
        StringBuilder lines = new StringBuilder();
        int no = 1;
        for (RecruitmentProposedSlot slot : offered) {
            if (no > 1) {
                lines.append('\n');
            }
            lines.append(no++).append(". ").append(
                    SlackSchedulingViews.danishInterval(slot.getSlotStart(), slot.getSlotEnd()));
        }
        return lines.toString();
    }

    /**
     * The default answer-by: {@value #DEADLINE_BUSINESS_DAYS} business
     * days after sending, at {@value #DEADLINE_HOUR}:00 (defaults §29.2).
     * Pure — DB-free tested.
     */
    static LocalDateTime defaultCandidateDeadline(LocalDateTime sentAt) {
        LocalDate date = sentAt.toLocalDate();
        int added = 0;
        while (added < DEADLINE_BUSINESS_DAYS) {
            date = date.plusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date.atTime(DEADLINE_HOUR, 0);
    }

    // ---- Public page reads / actions (token-addressed) ---------------------

    /** Resolve a presented token; {@code null} on EVERY invalid case. */
    record Resolved(RecruitmentOptionBatch batch, RecruitmentSchedulingRequest request) {
    }

    private Resolved resolve(String rawToken, LocalDateTime now) {
        if (rawToken == null || !TOKEN_SHAPE.matcher(rawToken).matches()) {
            return null;
        }
        RecruitmentOptionBatch batch = RecruitmentOptionBatch
                .<RecruitmentOptionBatch>find("tokenHash",
                        RecruitmentConsentService.sha256Hex(rawToken))
                .firstResult();
        if (batch == null || batch.getStatus() != OptionBatchStatus.ACTIVE
                || batch.getExpiresAt() == null || !batch.getExpiresAt().isAfter(now)) {
            return null;
        }
        RecruitmentSchedulingRequest request =
                RecruitmentSchedulingRequest.findById(batch.getRequestUuid());
        if (request == null
                || (request.getStatus() != SchedulingRequestStatus.WAITING_FOR_CANDIDATE
                && request.getStatus() != SchedulingRequestStatus.FINALIZING)) {
            return null;
        }
        return new Resolved(batch, request);
    }

    /** The page view, or {@code null} → uniform 404. */
    public PublicSchedulingResponse publicView(String rawToken) {
        LocalDateTime now = LocalDateTime.now();
        Resolved resolved = resolve(rawToken, now);
        return resolved == null ? null : view(resolved, now);
    }

    private PublicSchedulingResponse view(Resolved resolved, LocalDateTime now) {
        RecruitmentSchedulingRequest request = resolved.request();
        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        RecruitmentCandidate candidate = application == null ? null
                : RecruitmentCandidate.findById(application.getCandidateUuid());
        RecruitmentPosition position = application == null ? null
                : RecruitmentPosition.findById(application.getPositionUuid());

        RecruitmentProposedSlot selected = RecruitmentProposedSlot
                .<RecruitmentProposedSlot>find("requestUuid = ?1 and status = ?2",
                        request.getUuid(), ProposedSlotStatus.SELECTED)
                .firstResult();
        List<PublicSchedulingResponse.PublicOption> options = RecruitmentProposedSlot
                .<RecruitmentProposedSlot>list(
                        "requestUuid = ?1 and status = ?2 order by slotStart",
                        request.getUuid(), ProposedSlotStatus.OFFERED).stream()
                .filter(slot -> slot.getExpiresAt() == null
                        || slot.getExpiresAt().isAfter(now))
                .map(slot -> new PublicSchedulingResponse.PublicOption(
                        slot.getUuid(), slot.getSlotStart(), slot.getSlotEnd()))
                .toList();

        boolean online = request.isOnlineMeeting();
        return new PublicSchedulingResponse(
                selected != null
                        ? PublicSchedulingResponse.STATUS_SELECTED
                        : PublicSchedulingResponse.STATUS_OPEN,
                candidate != null ? candidate.getFirstName() : null,
                position != null ? position.getTitle() : null,
                request.getKind(), request.getRound(), request.getDurationMinutes(),
                "Europe/Copenhagen",
                online ? PublicSchedulingResponse.LOCATION_ONLINE
                        : PublicSchedulingResponse.LOCATION_IN_PERSON,
                online ? null : request.getLocation(),
                resolved.batch().getExpiresAt(),
                selected != null
                        ? List.of(new PublicSchedulingResponse.PublicOption(
                                selected.getUuid(), selected.getSlotStart(),
                                selected.getSlotEnd()))
                        : options,
                selected != null ? selected.getUuid() : null);
    }

    /** Selection outcome for the resource's response mapping. */
    public sealed interface SelectOutcome {
        record NotFound() implements SelectOutcome {
        }

        /** The named option is no longer valid — the page re-fetches and
         * asks the candidate to choose again. */
        record InvalidOption() implements SelectOutcome {
        }

        /** The committed state — a fresh selection or the earlier winner. */
        record Committed(PublicSchedulingResponse view) implements SelectOutcome {
        }
    }

    /**
     * Concurrency-safe selection (plan §11.1, spec §16.2): first committed
     * wins via the request row's optimistic {@code @Version}; the loser's
     * transaction rolls back and the retry answers the committed outcome.
     */
    public SelectOutcome select(String rawToken, String optionUuid) {
        try {
            return QuarkusTransaction.requiringNew()
                    .call(() -> selectOnce(rawToken, optionUuid));
        } catch (RuntimeException raced) {
            // Optimistic-lock loser (or any commit-time race): the state
            // that beat us is the answer. A second failure is a real error.
            log.debugf("Method B selection raced (%s) — answering committed state",
                    raced.getClass().getSimpleName());
            return QuarkusTransaction.requiringNew()
                    .call(() -> committedOutcome(rawToken));
        }
    }

    private SelectOutcome selectOnce(String rawToken, String optionUuid) {
        LocalDateTime now = LocalDateTime.now();
        Resolved resolved = resolve(rawToken, now);
        if (resolved == null) {
            return new SelectOutcome.NotFound();
        }
        RecruitmentSchedulingRequest request = resolved.request();
        if (request.getStatus() == SchedulingRequestStatus.FINALIZING) {
            // Already committed — duplicate submissions (same or different
            // option) answer the winner (spec §16.2).
            return new SelectOutcome.Committed(view(resolved, now));
        }
        RecruitmentProposedSlot slot = optionUuid == null ? null
                : RecruitmentProposedSlot.findById(optionUuid);
        if (slot == null || !request.getUuid().equals(slot.getRequestUuid())
                || slot.getStatus() != ProposedSlotStatus.OFFERED
                || (slot.getExpiresAt() != null && !slot.getExpiresAt().isAfter(now))) {
            return new SelectOutcome.InvalidOption();
        }

        SchedulingStateMachine.require(request.getStatus(),
                SchedulingRequestStatus.FINALIZING);
        slot.setStatus(ProposedSlotStatus.SELECTED);
        request.setStatus(SchedulingRequestStatus.FINALIZING);
        request.setNextActionAt(null);

        RecruitmentApplication application =
                RecruitmentApplication.findById(request.getApplicationUuid());
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.OPTION_SELECTED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorCandidate()
                .payload("request_uuid", request.getUuid())
                .payload("slot_uuid", slot.getUuid())
                .payload("batch_uuid", resolved.batch().getUuid())
                .payload("slot_start", slot.getSlotStart().toString())
                .payload("slot_end", slot.getSlotEnd().toString()));
        return new SelectOutcome.Committed(view(resolved, now));
    }

    /** The post-race read: whatever selection state actually committed. */
    private SelectOutcome committedOutcome(String rawToken) {
        LocalDateTime now = LocalDateTime.now();
        Resolved resolved = resolve(rawToken, now);
        if (resolved == null) {
            return new SelectOutcome.NotFound();
        }
        if (resolved.request().getStatus() == SchedulingRequestStatus.FINALIZING) {
            return new SelectOutcome.Committed(view(resolved, now));
        }
        return new SelectOutcome.InvalidOption();
    }

    /**
     * "None of these work" (defaults §29.17): terminal handback with the
     * candidate's optional note escalated to the recruiter. Idempotent:
     * a repeat POST after the close answers RECEIVED again.
     *
     * @return true = accepted (fresh or repeat); false → uniform 404
     */
    public boolean noneWork(String rawToken, String note) {
        return QuarkusTransaction.requiringNew().call(() -> {
            LocalDateTime now = LocalDateTime.now();
            Resolved resolved = resolve(rawToken, now);
            if (resolved != null && resolved.request().getStatus()
                    == SchedulingRequestStatus.WAITING_FOR_CANDIDATE) {
                schedulingService.candidateDeclinedOptions(resolved.request(), note);
                return true;
            }
            // Repeat POST: the batch is CLOSED and the request carries the
            // candidate-declined reason — same outcome, answered again.
            if (rawToken != null && TOKEN_SHAPE.matcher(rawToken).matches()) {
                RecruitmentOptionBatch batch = RecruitmentOptionBatch
                        .<RecruitmentOptionBatch>find("tokenHash",
                                RecruitmentConsentService.sha256Hex(rawToken))
                        .firstResult();
                if (batch != null) {
                    RecruitmentSchedulingRequest request =
                            RecruitmentSchedulingRequest.findById(batch.getRequestUuid());
                    return request != null
                            && RecruitmentSchedulingService.REASON_CANDIDATE_DECLINED
                                    .equals(request.getHandbackReason());
                }
            }
            return false;
        });
    }

    // ---- Deadline expiry (advance-sweep step) ------------------------------

    /**
     * WAITING_FOR_CANDIDATE past the batch deadline: release and expire
     * (plan §11.3). Runs in the caller's per-request transaction.
     */
    public void expireIfOverdue(RecruitmentSchedulingRequest request, LocalDateTime now) {
        if (request.getStatus() != SchedulingRequestStatus.WAITING_FOR_CANDIDATE) {
            return;
        }
        RecruitmentOptionBatch batch = RecruitmentOptionBatch
                .<RecruitmentOptionBatch>find("requestUuid = ?1 and status = ?2",
                        request.getUuid(), OptionBatchStatus.ACTIVE)
                .firstResult();
        LocalDateTime deadline = batch != null ? batch.getExpiresAt()
                : request.getCandidateDeadline();
        if (deadline == null || deadline.isAfter(now)) {
            return;
        }
        schedulingService.expireOptions(request);
    }
}
