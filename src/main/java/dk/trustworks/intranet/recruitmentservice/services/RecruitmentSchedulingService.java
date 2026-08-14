package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.SchedulingRequestCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SchedulingRequestResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCalendarHold;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentOptionBatch;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.model.enums.CalendarHoldStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.OptionBatchStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.ProposedSlotStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SlotApprovalStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Method B command service (plan §8.5): every recruiter-facing
 * mutation of a scheduling request runs through here — one command, one
 * transaction, one audit event, with {@link SchedulingStateMachine}
 * guarding every status write. The advance sweep
 * ({@code RecruitmentSchedulingOrchestrator}) reuses {@link #handBack}
 * and {@link #releasePipeline} so automation and manual actions
 * terminate a request identically.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSchedulingService {

    /** Structural reject/release reasons (never free text). */
    public static final String REASON_RECRUITER_RELEASED = "RECRUITER_RELEASED";
    public static final String REASON_REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String REASON_REQUEST_HANDED_BACK = "REQUEST_HANDED_BACK";

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    SchedulingOutboxService outboxService;

    @Inject
    ObjectMapper objectMapper;

    // ---- Create -----------------------------------------------------------

    /**
     * Create a request (validations already done by the resource). One
     * active request per application: two Method B runs would fight over
     * the same interviewers and candidate.
     */
    @Transactional
    public RecruitmentSchedulingRequest create(RecruitmentApplication application,
                                               SchedulingRequestCreateRequest request,
                                               LocalDateTime automationDeadline,
                                               String actorUuid) {
        long active = RecruitmentSchedulingRequest.count(
                "applicationUuid = ?1 and status not in ?2",
                application.getUuid(), terminalStatuses());
        if (active > 0) {
            throw new WebApplicationException(
                    "The application already has an active scheduling request",
                    Response.Status.CONFLICT);
        }
        RecruitmentSchedulingRequest row = new RecruitmentSchedulingRequest();
        row.setApplicationUuid(application.getUuid());
        row.setRecruiterUuid(actorUuid);
        row.setKind(request.kind());
        row.setRound(request.round());
        row.setDurationMinutes(request.durationMinutes() != null
                ? request.durationMinutes() : 60);
        row.setOnlineMeeting(Boolean.TRUE.equals(request.onlineMeeting()));
        row.setRequireRoom(Boolean.TRUE.equals(request.requireRoom()));
        row.setLocation(request.location());
        row.setInterviewerUuids(List.copyOf(request.interviewerUuids()));
        row.setOptionalInterviewerUuids(request.optionalInterviewerUuids() != null
                ? List.copyOf(request.optionalInterviewerUuids()) : List.of());
        row.setRequestedOptions(request.requestedOptions() != null
                ? request.requestedOptions() : 3);
        row.setWindowStart(request.windowStart());
        row.setWindowEnd(request.windowEnd());
        row.setPermittedStart(request.permittedStart());
        row.setPermittedEnd(request.permittedEnd());
        row.setMinSeparationHours(request.minSeparationHours() != null
                ? request.minSeparationHours() : 0);
        row.setDifferentDays(Boolean.TRUE.equals(request.differentDays()));
        row.setCandidateDeadline(request.candidateDeadline());
        row.setAutomationDeadline(automationDeadline);
        row.setReviewRequired(request.reviewRequired() == null
                || request.reviewRequired());
        row.persist();

        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.SCHEDULING_REQUEST_CREATED)
                .application(application.getUuid())
                .candidate(application.getCandidateUuid())
                .position(application.getPositionUuid())
                .actorUser(actorUuid)
                .payload("request_uuid", row.getUuid())
                .payload("kind", row.getKind().name())
                .payload("round", row.getRound())
                .payload("requested_options", row.getRequestedOptions())
                .payload("window_start", row.getWindowStart().toString())
                .payload("window_end", row.getWindowEnd().toString())
                .payload("review_required", row.isReviewRequired()));
        return row;
    }

    // ---- Read -------------------------------------------------------------

    /** The FE panel aggregate: request + slots + approvals + hold health
     * + batch state + cleanup warnings (plan §8.5). */
    public SchedulingRequestResponse aggregate(RecruitmentSchedulingRequest request) {
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());
        List<SchedulingRequestResponse.SlotView> slotViews = new ArrayList<>();
        for (RecruitmentProposedSlot slot : slots) {
            List<RecruitmentSlotApproval> approvals = RecruitmentSlotApproval
                    .list("slotUuid = ?1", slot.getUuid());
            List<RecruitmentCalendarHold> holds = RecruitmentCalendarHold
                    .list("slotUuid = ?1", slot.getUuid());
            slotViews.add(new SchedulingRequestResponse.SlotView(
                    slot.getUuid(), slot.getOptionNo(),
                    slot.getSlotStart(), slot.getSlotEnd(),
                    slot.getRoomEmail(), slot.getRoomName(),
                    slot.getStatus(), slot.getRejectReason(), slot.getExpiresAt(),
                    approvals.stream().map(a -> new SchedulingRequestResponse.ApprovalView(
                            a.getUuid(), a.getUserUuid(), a.getStatus(),
                            a.getRespondedAt())).toList(),
                    holds.stream().map(h -> new SchedulingRequestResponse.HoldView(
                            h.getUuid(), h.getOwnerKind(), h.getUserUuid(),
                            h.getStatus(), h.getGraphEventId() != null,
                            h.getLastVerifiedAt())).toList()));
        }
        RecruitmentOptionBatch batch = RecruitmentOptionBatch
                .find("requestUuid = ?1 and status = ?2",
                        request.getUuid(), OptionBatchStatus.ACTIVE)
                .firstResult();
        return new SchedulingRequestResponse(
                request.getUuid(), request.getApplicationUuid(),
                request.getRecruiterUuid(), request.getStatus(),
                request.getKind(), request.getRound(), request.getDurationMinutes(),
                request.isOnlineMeeting(), request.isRequireRoom(), request.getLocation(),
                request.getInterviewerUuids(), request.getOptionalInterviewerUuids(),
                request.getRequestedOptions(),
                request.getWindowStart(), request.getWindowEnd(),
                request.getPermittedStart(), request.getPermittedEnd(),
                request.getMinSeparationHours(), request.isDifferentDays(),
                request.getCandidateDeadline(), request.getAutomationDeadline(),
                request.isReviewRequired(), request.getOptionsApprovedAt(),
                request.getHandbackReason(),
                slotViews,
                batch != null ? new SchedulingRequestResponse.BatchView(
                        batch.getUuid(), batch.getSentAt(), batch.getExpiresAt(),
                        batch.getStatus()) : null,
                outboxService.failedCount(request.getUuid()),
                request.getCreatedAt(), request.getUpdatedAt());
    }

    /** Every request of an application, newest first, as panel aggregates —
     * the FE discovers the active request (and the history) through this
     * after a page load; request uuids are never persisted client-side. */
    public List<SchedulingRequestResponse> aggregatesForApplication(String applicationUuid) {
        List<RecruitmentSchedulingRequest> rows = RecruitmentSchedulingRequest
                .list("applicationUuid = ?1 order by createdAt desc", applicationUuid);
        return rows.stream().map(this::aggregate).toList();
    }

    // ---- Terminating commands ---------------------------------------------

    /** Recruiter cancel: release everything, terminal CANCELLED. */
    @Transactional
    public void cancel(RecruitmentSchedulingRequest request, String actorUuid) {
        SchedulingStateMachine.require(request.getStatus(), SchedulingRequestStatus.CANCELLED);
        releasePipeline(request, REASON_REQUEST_CANCELLED);
        request.setStatus(SchedulingRequestStatus.CANCELLED);
        recordLifecycleEvent(request, RecruitmentEventType.SCHEDULING_CANCELLED,
                actorUuid, null);
    }

    /**
     * Hand the request back to the recruiter — the shared exit for the
     * manual takeover, the automation deadline, window exhaustion and
     * unrecoverable pipeline failures (spec §19.4). {@code actorUuid}
     * null = automation.
     */
    @Transactional
    public void handBack(RecruitmentSchedulingRequest request, String actorUuid,
                         String reason) {
        SchedulingStateMachine.require(request.getStatus(), SchedulingRequestStatus.HANDED_BACK);
        releasePipeline(request, REASON_REQUEST_HANDED_BACK);
        request.setStatus(SchedulingRequestStatus.HANDED_BACK);
        request.setHandbackReason(reason);
        recordLifecycleEvent(request, RecruitmentEventType.SCHEDULING_HANDED_BACK,
                actorUuid, reason);
        outboxService.enqueue(request.getUuid(), null,
                SchedulingOutboxAction.SEND_RECRUITER_DM,
                "HANDBACK:" + request.getVersion(),
                noticePayload("HANDBACK"));
    }

    /**
     * Release every live slot and enqueue deletion of every hold that
     * reached Graph. Idempotent — re-running releases nothing twice.
     */
    public void releasePipeline(RecruitmentSchedulingRequest request, String reason) {
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());
        for (RecruitmentProposedSlot slot : slots) {
            if (slot.getStatus().isTerminal()) {
                continue;
            }
            slot.setStatus(ProposedSlotStatus.RELEASED);
            slot.setRejectReason(reason);
            releaseHolds(request, slot);
        }
        RecruitmentOptionBatch batch = RecruitmentOptionBatch
                .find("requestUuid = ?1 and status = ?2",
                        request.getUuid(), OptionBatchStatus.ACTIVE)
                .firstResult();
        if (batch != null) {
            batch.setStatus(OptionBatchStatus.CLOSED);
        }
    }

    /** Enqueue DELETE_HOLD for each of the slot's undeleted holds. */
    public void releaseHolds(RecruitmentSchedulingRequest request,
                             RecruitmentProposedSlot slot) {
        List<RecruitmentCalendarHold> holds = RecruitmentCalendarHold
                .list("slotUuid = ?1", slot.getUuid());
        for (RecruitmentCalendarHold hold : holds) {
            if (hold.getStatus() == CalendarHoldStatus.RELEASED) {
                continue;
            }
            if (hold.getGraphEventId() == null) {
                // Never reached Graph — nothing to delete out there.
                hold.setStatus(CalendarHoldStatus.RELEASED);
                hold.setReleasedAt(LocalDateTime.now());
                continue;
            }
            outboxService.enqueue(request.getUuid(), slot.getUuid(),
                    SchedulingOutboxAction.DELETE_HOLD, hold.getUuid(),
                    refPayload("holdUuid", hold.getUuid()));
        }
    }

    // ---- Live-request commands --------------------------------------------

    /** Extend the search window (and optionally the automation anchor). */
    @Transactional
    public void extendWindow(RecruitmentSchedulingRequest request, String actorUuid,
                             java.time.LocalDate newWindowEnd,
                             LocalDateTime newAutomationDeadline) {
        requireLive(request);
        if (newWindowEnd == null || newWindowEnd.isBefore(request.getWindowEnd())) {
            throw badRequest("windowEnd can only be extended");
        }
        java.time.LocalDate oldEnd = request.getWindowEnd();
        LocalDateTime oldDeadline = request.getAutomationDeadline();
        request.setWindowEnd(newWindowEnd);
        if (newAutomationDeadline != null) {
            if (newAutomationDeadline.isBefore(request.getAutomationDeadline())) {
                throw badRequest("automationDeadline can only be extended");
            }
            request.setAutomationDeadline(newAutomationDeadline);
        }
        request.setNextActionAt(null); // wake the sweep
        recordUpdateEvent(request, actorUuid, Map.of(
                "change", "EXTEND_WINDOW",
                "window_end_from", oldEnd.toString(),
                "window_end_to", newWindowEnd.toString(),
                "automation_deadline_from", oldDeadline.toString(),
                "automation_deadline_to", request.getAutomationDeadline().toString()));
    }

    /**
     * Swap one required interviewer for another (defaults §29.8: the
     * recruiter decides). Live slots get the seat's approval reset to
     * PENDING for the replacement (their proposal DM goes out via the
     * outbox); the replaced seat's hold, when one exists, is released.
     * Only before the candidate is involved — afterwards the recruiter
     * cancels or takes over instead.
     */
    @Transactional
    public void replaceInterviewer(RecruitmentSchedulingRequest request, String actorUuid,
                                   String fromUserUuid, String toUserUuid) {
        requireLive(request);
        if (request.getStatus() == SchedulingRequestStatus.WAITING_FOR_CANDIDATE
                || request.getStatus() == SchedulingRequestStatus.FINALIZING) {
            throw badRequest("Interviewers can no longer be replaced — the candidate is choosing");
        }
        List<String> interviewers = new ArrayList<>(request.getInterviewerUuids());
        if (!interviewers.contains(fromUserUuid)) {
            throw badRequest("fromUserUuid is not a required interviewer on the request");
        }
        if (interviewers.contains(toUserUuid)) {
            throw badRequest("toUserUuid is already a required interviewer on the request");
        }
        interviewers.set(interviewers.indexOf(fromUserUuid), toUserUuid);
        request.setInterviewerUuids(interviewers);
        request.setNextActionAt(null);

        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1", request.getUuid());
        for (RecruitmentProposedSlot slot : slots) {
            if (slot.getStatus().isTerminal()) {
                continue;
            }
            RecruitmentSlotApproval seat = RecruitmentSlotApproval
                    .<RecruitmentSlotApproval>find("slotUuid = ?1 and userUuid = ?2",
                            slot.getUuid(), fromUserUuid)
                    .firstResult();
            if (seat != null) {
                seat.delete();
            }
            RecruitmentCalendarHold hold = RecruitmentCalendarHold
                    .<RecruitmentCalendarHold>find(
                            "slotUuid = ?1 and userUuid = ?2 and status != ?3",
                            slot.getUuid(), fromUserUuid, CalendarHoldStatus.RELEASED)
                    .firstResult();
            if (hold != null) {
                if (hold.getGraphEventId() == null) {
                    hold.setStatus(CalendarHoldStatus.RELEASED);
                    hold.setReleasedAt(LocalDateTime.now());
                } else {
                    outboxService.enqueue(request.getUuid(), slot.getUuid(),
                            SchedulingOutboxAction.DELETE_HOLD, hold.getUuid(),
                            refPayload("holdUuid", hold.getUuid()));
                }
            }
            RecruitmentSlotApproval replacement = new RecruitmentSlotApproval();
            replacement.setSlotUuid(slot.getUuid());
            replacement.setUserUuid(toUserUuid);
            replacement.persist();
            outboxService.enqueue(request.getUuid(), slot.getUuid(),
                    SchedulingOutboxAction.SEND_PROPOSAL_DM, replacement.getUuid(),
                    refPayload("approvalUuid", replacement.getUuid()));
            // The new seat is unanswered — the slot cannot be further
            // along than partially approved now.
            if (slot.getStatus() == ProposedSlotStatus.APPROVED
                    || slot.getStatus() == ProposedSlotStatus.RECHECKING
                    || slot.getStatus() == ProposedSlotStatus.HELD) {
                slot.setStatus(recomputeSlotStatus(
                        RecruitmentSlotApproval.list("slotUuid = ?1", slot.getUuid())));
            }
        }
        recordUpdateEvent(request, actorUuid, Map.of(
                "change", "REPLACE_INTERVIEWER",
                "from_user_uuid", fromUserUuid,
                "to_user_uuid", toUserUuid));
    }

    /**
     * The D11 review action: optionally release options, then approve
     * the remaining batch for sending. The actual send (mail + token
     * batch) is the Phase 11 step that moves the request to
     * WAITING_FOR_CANDIDATE — this records the recruiter's go.
     */
    @Transactional
    public void approveOptions(RecruitmentSchedulingRequest request, String actorUuid,
                               List<String> releaseSlotUuids) {
        if (request.getStatus() != SchedulingRequestStatus.READY_FOR_CANDIDATE
                && request.getStatus() != SchedulingRequestStatus.HOLDING_OPTIONS) {
            throw badRequest("Options can only be reviewed once slots are secured");
        }
        List<String> released = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        List<RecruitmentProposedSlot> slots = RecruitmentProposedSlot
                .list("requestUuid = ?1 and status = ?2",
                        request.getUuid(), ProposedSlotStatus.HELD);
        for (RecruitmentProposedSlot slot : slots) {
            if (releaseSlotUuids != null && releaseSlotUuids.contains(slot.getUuid())) {
                slot.setStatus(ProposedSlotStatus.RELEASED);
                slot.setRejectReason(REASON_RECRUITER_RELEASED);
                releaseHolds(request, slot);
                released.add(slot.getUuid());
            } else {
                kept.add(slot.getUuid());
            }
        }
        if (kept.isEmpty()) {
            throw badRequest("At least one held option must remain to approve");
        }
        request.setOptionsApprovedAt(LocalDateTime.now());
        request.setNextActionAt(null);
        RecruitmentApplication application = application(request);
        eventRecorder.record(RecruitmentEventBuilder
                .event(RecruitmentEventType.SCHEDULING_OPTIONS_APPROVED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorUser(actorUuid)
                .payload("request_uuid", request.getUuid())
                .payload("kept_slot_uuids", kept)
                .payload("released_slot_uuids", released));
    }

    // ---- Shared helpers ----------------------------------------------------

    /** Slot status from its approval set (pure; DB-free tested). */
    public static ProposedSlotStatus recomputeSlotStatus(
            List<RecruitmentSlotApproval> approvals) {
        boolean anyDeclined = approvals.stream()
                .anyMatch(a -> a.getStatus() == SlotApprovalStatus.DECLINED);
        if (anyDeclined) {
            return ProposedSlotStatus.REJECTED;
        }
        long approved = approvals.stream()
                .filter(a -> a.getStatus() == SlotApprovalStatus.APPROVED)
                .count();
        if (approved == 0) {
            return ProposedSlotStatus.PROPOSED;
        }
        return approved == approvals.size()
                ? ProposedSlotStatus.APPROVED
                : ProposedSlotStatus.PARTIALLY_APPROVED;
    }

    public static Set<SchedulingRequestStatus> terminalStatuses() {
        return Set.of(SchedulingRequestStatus.SCHEDULED,
                SchedulingRequestStatus.HANDED_BACK,
                SchedulingRequestStatus.EXPIRED,
                SchedulingRequestStatus.CANCELLED);
    }

    private void requireLive(RecruitmentSchedulingRequest request) {
        if (request.getStatus().isTerminal()) {
            throw badRequest("The scheduling request is closed");
        }
    }

    private RecruitmentApplication application(RecruitmentSchedulingRequest request) {
        return RecruitmentApplication.findById(request.getApplicationUuid());
    }

    private void recordLifecycleEvent(RecruitmentSchedulingRequest request,
                                      RecruitmentEventType type, String actorUuid,
                                      String reason) {
        RecruitmentApplication application = application(request);
        RecruitmentEventBuilder builder = RecruitmentEventBuilder.event(type)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .payload("request_uuid", request.getUuid());
        if (actorUuid != null) {
            builder.actorUser(actorUuid);
        } else {
            builder.actorScheduler();
        }
        if (reason != null) {
            builder.payload("reason", reason);
        }
        eventRecorder.record(builder);
    }

    private void recordUpdateEvent(RecruitmentSchedulingRequest request, String actorUuid,
                                   Map<String, String> changes) {
        RecruitmentApplication application = application(request);
        RecruitmentEventBuilder builder = RecruitmentEventBuilder
                .event(RecruitmentEventType.SCHEDULING_REQUEST_UPDATED)
                .application(request.getApplicationUuid())
                .candidate(application != null ? application.getCandidateUuid() : null)
                .position(application != null ? application.getPositionUuid() : null)
                .actorUser(actorUuid)
                .payload("request_uuid", request.getUuid());
        changes.forEach(builder::payload);
        eventRecorder.record(builder);
    }

    String refPayload(String key, String value) {
        try {
            return objectMapper.writeValueAsString(Map.of(key, value));
        } catch (Exception e) {
            // uuids only — serialization cannot realistically fail.
            return "{\"" + key + "\":\"" + value + "\"}";
        }
    }

    private String noticePayload(String kind) {
        return refPayload("notice", kind);
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
